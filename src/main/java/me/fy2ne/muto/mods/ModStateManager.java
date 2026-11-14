package me.fy2ne.muto.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import me.fy2ne.muto.MutoLog;
import me.fy2ne.muto.dev.MutoDev;
import me.fy2ne.muto.engine.ModDependencyAnalyzer;
import me.fy2ne.muto.engine.knot.JarUnlocker;
import net.fabricmc.loader.api.FabricLoader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ModStateManager {
    public static final ModStateManager INSTANCE = new ModStateManager();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SET_TYPE = new TypeToken<HashSet<String>>() {}.getType();

    private static final Set<String> CORE_MOD_IDS = Set.of(
            "minecraft",
            "fabricloader",
            "fabric",
            "fabric-api",
            "java",
            "muto",
            "muto_menu",
            "modmenu",
            "fabric-api-base",
            "fabric-lifecycle-events-v1",
            "fabric-resource-loader-v0"
    );

    private final Set<String> stagedDisables = new HashSet<>();
    private final Set<String> stagedEnables = new HashSet<>();
    private final Set<String> sessionDisabledMods = ConcurrentHashMap.newKeySet();
    private final Map<Path, Path> deferredShutdownRenames = new ConcurrentHashMap<>();

    private final Map<String, DiskMod> diskCache = new ConcurrentHashMap<>();
    private volatile long lastScanTime = 0L;
    private volatile boolean registryLoaded = false;

    private ModStateManager() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (hasStagedChanges()) {
                applyFileRenames();
            }
            flushDeferredRenames();
        }, "Muto-Shutdown-Renamer"));
    }

    private synchronized void ensureRegistryLoaded() {
        if (registryLoaded) return;
        registryLoaded = true;
        try {
            Path cfg = getDisabledRegistryPath();
            if (cfg != null && Files.exists(cfg)) {
                try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                    Set<String> saved = GSON.fromJson(r, SET_TYPE);
                    if (saved != null) {
                        sessionDisabledMods.addAll(saved);
                    }
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("failed reading disabled_mods.json: {}", t.getMessage());
        }
    }

    private synchronized void saveDisabledRegistry() {
        try {
            Path cfg = getDisabledRegistryPath();
            if (cfg == null) return;
            if (cfg.getParent() != null) Files.createDirectories(cfg.getParent());
            try (Writer w = Files.newBufferedWriter(cfg, StandardCharsets.UTF_8)) {
                GSON.toJson(new HashSet<>(sessionDisabledMods), SET_TYPE, w);
            }
        } catch (Throwable t) {
            MutoLog.warn("failed saving disabled_mods.json: {}", t.getMessage());
        }
    }

    private Path getDisabledRegistryPath() {
        try {
            if (FabricLoader.getInstance() != null && FabricLoader.getInstance().getConfigDir() != null) {
                return FabricLoader.getInstance().getConfigDir().resolve("muto").resolve("disabled_mods.json");
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public synchronized Set<String> reconcileFileRenames(Path modsDir) {
        ensureRegistryLoaded();
        Set<String> disabledFound = new LinkedHashSet<>();
        if (modsDir == null || !Files.isDirectory(modsDir)) return disabledFound;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            Set<String> foundDiskModIds = new HashSet<>();
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                String fn = path.getFileName().toString();
                boolean isDis = isDisabledFileName(fn);
                boolean isNorm = isNormalJar(fn);
                if (!isDis && !isNorm) continue;

                DiskMod dm = parseModMetadata(path, isDis, Collections.emptySet());
                if (dm == null) continue;

                foundDiskModIds.add(dm.id());
                if (dm.aliases() != null) foundDiskModIds.addAll(dm.aliases());

                boolean modShouldBeDisabled = sessionDisabledMods.contains(dm.id());
                if (!modShouldBeDisabled && dm.aliases() != null) {
                    for (String a : dm.aliases()) {
                        if (sessionDisabledMods.contains(a)) {
                            modShouldBeDisabled = true;
                            break;
                        }
                    }
                }

                if (isNorm && modShouldBeDisabled) {
                    disabledFound.add(dm.id());
                    Path target = path.resolveSibling(toDisabledJarName(fn));
                    try {
                        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
                        MutoLog.info("reconciled disabled jar on startup: {} -> {}", fn, target.getFileName());
                    } catch (Throwable locked) {
                        boolean ok = JarUnlocker.unlockAndMove(path, target);
                        if (!ok) {
                            deferredShutdownRenames.put(path, target);
                        }
                    }
                } else if (isDis) {
                    disabledFound.add(dm.id());
                    sessionDisabledMods.add(dm.id());
                    if (dm.aliases() != null) sessionDisabledMods.addAll(dm.aliases());
                }
            }

            sessionDisabledMods.removeIf(id -> !foundDiskModIds.contains(id) && !CORE_MOD_IDS.contains(id));
            saveDisabledRegistry();
        } catch (Throwable t) {
            MutoLog.warn("reconcile renames skipped: {}", t.getMessage());
        }
        return disabledFound;
    }

    public synchronized void retryPendingDisables() {
        if (deferredShutdownRenames.isEmpty()) return;
        for (var it = deferredShutdownRenames.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Path src = e.getKey();
            Path dst = e.getValue();
            if (!Files.exists(src)) {
                it.remove();
                continue;
            }
            if (JarUnlocker.unlockAndMove(src, dst)) {
                it.remove();
            }
        }
        invalidateCache();
    }

    private void scheduleDetachedRename(Path src, Path dst) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win") && src != null && dst != null) {
                String s = src.toAbsolutePath().normalize().toString().replace("'", "''");
                String d = dst.toAbsolutePath().normalize().toString().replace("'", "''");
                String ps = "for ($i=0; $i -lt 25; $i++) { if (Test-Path -LiteralPath '" + s + "') { Move-Item -LiteralPath '" + s + "' -Destination '" + d + "' -Force -ErrorAction SilentlyContinue; if (Test-Path -LiteralPath '" + s + "') { Start-Sleep -Milliseconds 750 } else { break } } else { break } }";
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps);
                Path parent = src.toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    pb.directory(parent.toFile());
                }
                pb.start();
            }
        } catch (Throwable ignored) {}
    }

    private void flushDeferredRenames() {
        for (var e : deferredShutdownRenames.entrySet()) {
            Path src = e.getKey();
            Path dst = e.getValue();
            try {
                if (Files.exists(src)) {
                    try {
                        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Throwable locked) {
                        boolean ok = JarUnlocker.unlockAndMove(src, dst);
                        if (!ok) {
                            scheduleDetachedRename(src, dst);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static boolean isDisabledFileName(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".disabled")
                || lower.endsWith(".disactivation")
                || lower.endsWith(".discavation")
                || lower.endsWith(".deactivation")
                || lower.endsWith(".disable")
                || lower.endsWith(".off")
                || (lower.contains(".jar.") && !lower.endsWith(".jar"));
    }

    public static boolean isNormalJar(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") && !isDisabledFileName(filename);
    }

    public static String toEnabledJarName(String filename) {
        if (filename == null) return null;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar") && !isDisabledFileName(filename)) {
            return filename;
        }

        int jarIdx = lower.indexOf(".jar.");
        if (jarIdx != -1) {
            return filename.substring(0, jarIdx + 4);
        }

        for (String ext : List.of(".disabled", ".disactivation", ".discavation", ".deactivation", ".disable", ".off")) {
            if (lower.endsWith(ext)) {
                String base = filename.substring(0, filename.length() - ext.length());
                return base.endsWith(".jar") ? base : base + ".jar";
            }
        }

        return filename.endsWith(".jar") ? filename : filename + ".jar";
    }

    public static String toDisabledJarName(String filename) {
        if (filename == null) return null;
        if (isDisabledFileName(filename)) return filename;
        return filename + ".disabled";
    }

    public boolean isCore(String modId) {
        if (modId == null) return false;
        String lower = modId.toLowerCase(Locale.ROOT);
        return CORE_MOD_IDS.contains(lower) || lower.startsWith("fabric-api") || lower.equals("muto");
    }

    public boolean isProtectedMod(String modId) {
        return isCore(modId);
    }

    public synchronized void invalidateCache() {
        diskCache.clear();
        lastScanTime = 0L;
    }

    public DiskMod findDiskMod(String modId) {
        if (modId == null) return null;
        if (diskCache.isEmpty()) {
            scanDiskMods();
        }
        DiskMod found = diskCache.get(modId);
        if (found != null) return found;
        for (DiskMod m : diskCache.values()) {
            if (m.aliases() != null && m.aliases().contains(modId)) {
                return m;
            }
        }
        return null;
    }

    public boolean isModDisabled(String modId) {
        if (modId == null || isCore(modId)) return false;
        ensureRegistryLoaded();
        if (sessionDisabledMods.contains(modId)) return true;
        DiskMod m = findDiskMod(modId);
        if (m != null) {
            if (m.disabled() || sessionDisabledMods.contains(m.id())) return true;
            if (m.aliases() != null) {
                for (String a : m.aliases()) {
                    if (sessionDisabledMods.contains(a)) return true;
                }
            }
        }
        return false;
    }

    public Set<String> getDisabledModIds() {
        ensureRegistryLoaded();
        Set<String> set = new HashSet<>(sessionDisabledMods);
        for (DiskMod m : diskCache.values()) {
            if (m.disabled()) {
                set.add(m.id());
                if (m.aliases() != null) set.addAll(m.aliases());
            }
        }
        return Collections.unmodifiableSet(set);
    }

    public boolean isModLoaded(String modId) {
        if (modId == null) return false;
        if (isModDisabled(modId)) return false;
        try {
            if (FabricLoader.getInstance() != null && FabricLoader.getInstance().getModContainer(modId).isPresent()) return true;
        } catch (Throwable ignored) {}
        DiskMod m = findDiskMod(modId);
        if (m != null) {
            try {
                if (FabricLoader.getInstance() != null && FabricLoader.getInstance().getModContainer(m.id()).isPresent()) return true;
                if (m.aliases() != null && FabricLoader.getInstance() != null) {
                    for (String a : m.aliases()) {
                        if (FabricLoader.getInstance().getModContainer(a).isPresent()) return true;
                    }
                }
            } catch (Throwable ignored) {}
            return m.activeInLoader();
        }
        return false;
    }

    public boolean isModUnloaded(String modId) {
        return !isModDisabled(modId) && !isModLoaded(modId);
    }

    public synchronized boolean isStaged(String modId) {
        if (modId == null) return false;
        if (stagedDisables.contains(modId) || stagedEnables.contains(modId)) return true;
        DiskMod m = findDiskMod(modId);
        if (m != null && !m.id().equals(modId)) {
            return stagedDisables.contains(m.id()) || stagedEnables.contains(m.id());
        }
        return false;
    }

    public synchronized boolean getStagedState(String modId) {
        DiskMod m = findDiskMod(modId);
        String targetId = (m != null) ? m.id() : modId;
        if (stagedEnables.contains(targetId)) return true;
        if (stagedDisables.contains(targetId)) return false;
        return !isModDisabled(targetId);
    }

    public synchronized void stageDisable(String modId) {
        if (isCore(modId)) return;
        DiskMod m = findDiskMod(modId);
        String targetId = (m != null) ? m.id() : modId;
        if (stagedDisables.contains(targetId)) return;

        stagedDisables.add(targetId);
        stagedEnables.remove(targetId);

        List<DiskMod> allMods = scanDiskMods();
        Set<String> checkIds = new HashSet<>();
        checkIds.add(targetId);
        checkIds.add(modId);
        if (m != null && m.aliases() != null) {
            checkIds.addAll(m.aliases());
        }

        for (DiskMod other : allMods) {
            if (isCore(other.id()) || other.id().equals(targetId)) continue;
            boolean dependsOnTarget = false;
            for (String cid : checkIds) {
                if (other.depends().containsKey(cid)) {
                    dependsOnTarget = true;
                    break;
                }
            }
            if (dependsOnTarget && !stagedDisables.contains(other.id())) {
                stageDisable(other.id());
            }
        }
    }

    public synchronized void stageEnable(String modId) {
        if (isCore(modId)) return;
        DiskMod m = findDiskMod(modId);
        String targetId = (m != null) ? m.id() : modId;
        if (stagedEnables.contains(targetId)) return;

        stagedEnables.add(targetId);
        stagedDisables.remove(targetId);

        if (m != null && !m.depends().isEmpty()) {
            for (String depId : m.depends().keySet()) {
                if (isCore(depId) || depId.startsWith("fabric-api") || depId.startsWith("fabric-")) continue;
                DiskMod depMod = findDiskMod(depId);
                if (depMod != null && !isCore(depMod.id())) {
                    if (isModDisabled(depMod.id()) || stagedDisables.contains(depMod.id())) {
                        stageEnable(depMod.id());
                    }
                }
            }
        }
    }

    public synchronized void revertStaging(String modId) {
        if (modId == null) return;
        DiskMod m = findDiskMod(modId);
        String targetId = (m != null) ? m.id() : modId;
        stagedDisables.remove(targetId);
        stagedEnables.remove(targetId);
        if (m != null && m.aliases() != null) {
            for (String a : m.aliases()) {
                stagedDisables.remove(a);
                stagedEnables.remove(a);
            }
        }
    }

    public synchronized void stageToggle(String modId) {
        if (isCore(modId)) return;
        DiskMod m = findDiskMod(modId);
        String targetId = (m != null) ? m.id() : modId;
        boolean loaded = isModLoaded(targetId);
        boolean disabled = isModDisabled(targetId);

        if (disabled || !loaded) {
            if (stagedEnables.contains(targetId)) {
                revertStaging(targetId);
            } else {
                stageEnable(targetId);
            }
        } else {
            if (stagedDisables.contains(targetId)) {
                revertStaging(targetId);
            } else {
                stageDisable(targetId);
            }
        }
    }

    public synchronized void toggleMod(DiskMod mod) {
        if (mod == null || isCore(mod.id())) return;
        diskCache.put(mod.id(), mod);
        stageToggle(mod.id());
    }

    public synchronized boolean isStagedForDisable(String modId) {
        return stagedDisables.contains(modId);
    }

    public synchronized boolean isStagedForEnable(String modId) {
        return stagedEnables.contains(modId);
    }

    public synchronized boolean hasStagedChanges() {
        return !stagedDisables.isEmpty() || !stagedEnables.isEmpty();
    }

    public synchronized int getStagedCount() {
        return stagedDisables.size() + stagedEnables.size();
    }

    public synchronized void clearStaged() {
        stagedDisables.clear();
        stagedEnables.clear();
    }

    public synchronized Set<String> getStagedDisables() {
        return new HashSet<>(stagedDisables);
    }

    public synchronized Set<String> getStagedEnables() {
        return new HashSet<>(stagedEnables);
    }

    public synchronized List<DiskMod> getDependents(String targetModId, List<DiskMod> diskMods) {
        if (targetModId == null || diskMods == null) return Collections.emptyList();
        List<DiskMod> broken = new ArrayList<>();
        for (DiskMod m : diskMods) {
            if (m.id().equals(targetModId)) continue;
            if (!getStagedState(m.id())) continue;
            if (m.depends().containsKey(targetModId)) {
                broken.add(m);
            }
        }
        return broken;
    }

    public synchronized List<String> getMissingDependencies(DiskMod mod, List<DiskMod> diskMods) {
        if (mod == null || mod.depends().isEmpty()) return Collections.emptyList();
        Set<String> enabled = new HashSet<>(List.of("minecraft", "fabricloader", "fabric", "fabric-api", "java", "muto"));
        if (diskMods != null) {
            for (DiskMod m : diskMods) {
                if (getStagedState(m.id()) || m.id().equals(mod.id())) {
                    enabled.add(m.id());
                }
            }
        }
        try (JarFile jf = new JarFile(mod.filePath().toFile())) {
            enabled.addAll(ModDependencyAnalyzer.scanInternalJijIds(jf));
        } catch (Throwable ignored) {}

        List<String> missing = new ArrayList<>();
        for (String dep : mod.depends().keySet()) {
            if (dep.startsWith("fabric-api") || dep.startsWith("fabric-")) continue;
            if (!enabled.contains(dep)) {
                missing.add(dep);
            }
        }
        return missing;
    }

    public synchronized List<DiskMod> scanDiskMods() {
        Path modsDir = null;
        try {
            if (FabricLoader.getInstance() != null && FabricLoader.getInstance().getGameDir() != null) {
                modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            }
        } catch (Throwable ignored) {}
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return Collections.emptyList();
        }

        Set<String> activeLoaderIds = new HashSet<>();
        try {
            if (FabricLoader.getInstance() != null) {
                for (var c : FabricLoader.getInstance().getAllMods()) {
                    activeLoaderIds.add(c.getMetadata().getId());
                }
            }
        } catch (Throwable ignored) {}

        Map<String, DiskMod> scanned = new LinkedHashMap<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                String filename = path.getFileName().toString();

                boolean isDisabled = isDisabledFileName(filename);
                boolean isNormal = isNormalJar(filename);

                if (!isDisabled && !isNormal) continue;

                DiskMod parsed = parseModMetadata(path, isDisabled, activeLoaderIds);
                if (parsed != null) {
                    scanned.put(parsed.id(), parsed);
                    if (parsed.aliases() != null) {
                        for (String alias : parsed.aliases()) {
                            scanned.putIfAbsent(alias, parsed);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            MutoLog.warn("failed scanning disk mods: {}", ex.getMessage());
        }

        diskCache.clear();
        diskCache.putAll(scanned);
        lastScanTime = System.currentTimeMillis();

        if (!scanned.isEmpty()) {
            boolean changed = sessionDisabledMods.removeIf(id -> !scanned.containsKey(id) && !CORE_MOD_IDS.contains(id));
            if (changed) {
                saveDisabledRegistry();
            }
        }

        List<DiskMod> list = new ArrayList<>(new LinkedHashSet<>(scanned.values()));
        list.sort(Comparator.comparing(DiskMod::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private DiskMod parseModMetadata(Path path, boolean disabled, Set<String> activeLoaderIds) {
        try (JarFile jf = new JarFile(path.toFile())) {
            JarEntry fmj = jf.getJarEntry("fabric.mod.json");
            if (fmj == null) return null;

            try (InputStream in = jf.getInputStream(fmj);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("id")) return null;

                String id = root.get("id").getAsString();
                String name = root.has("name") ? root.get("name").getAsString() : id;
                String version = root.has("version") ? root.get("version").getAsString() : "?";

                String iconPath = null;
                if (root.has("icon")) {
                    com.google.gson.JsonElement iconElem = root.get("icon");
                    if (iconElem.isJsonPrimitive()) {
                        iconPath = iconElem.getAsString();
                    } else if (iconElem.isJsonObject()) {
                        com.google.gson.JsonObject iconObj = iconElem.getAsJsonObject();
                        for (var entry : iconObj.entrySet()) {
                            if (entry.getValue().isJsonPrimitive()) {
                                iconPath = entry.getValue().getAsString();
                            }
                        }
                    }
                }

                Set<String> aliases = new HashSet<>();
                if (id.endsWith("-container")) {
                    aliases.add(id.substring(0, id.length() - "-container".length()));
                }
                if (root.has("custom") && root.get("custom").isJsonObject()) {
                    JsonObject custom = root.getAsJsonObject("custom");
                    if (custom.has("modmenu") && custom.get("modmenu").isJsonObject()) {
                        JsonObject mm = custom.getAsJsonObject("modmenu");
                        if (mm.has("parent")) {
                            com.google.gson.JsonElement p = mm.get("parent");
                            if (p.isJsonPrimitive()) {
                                aliases.add(p.getAsString());
                            } else if (p.isJsonObject() && p.getAsJsonObject().has("id")) {
                                aliases.add(p.getAsJsonObject().get("id").getAsString());
                            }
                        }
                    }
                }

                Map<String, String> depends = new LinkedHashMap<>();
                if (root.has("depends") && root.get("depends").isJsonObject()) {
                    for (var e : root.getAsJsonObject("depends").entrySet()) {
                        depends.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "*");
                    }
                }

                boolean active = activeLoaderIds.contains(id);
                if (!active) {
                    for (String a : aliases) {
                        if (activeLoaderIds.contains(a)) {
                            active = true;
                            break;
                        }
                    }
                }

                boolean core = isCore(id);
                for (String a : aliases) {
                    if (isCore(a)) core = true;
                }

                boolean isGloballyDisabled = disabled || sessionDisabledMods.contains(id);
                if (!isGloballyDisabled) {
                    for (String a : aliases) {
                        if (sessionDisabledMods.contains(a)) {
                            isGloballyDisabled = true;
                            break;
                        }
                    }
                }
                if (isGloballyDisabled) {
                    active = false;
                }

                String desc = root.has("description") ? root.get("description").getAsString() : "";

                List<String> authors = new ArrayList<>();
                if (root.has("authors")) {
                    JsonElement authElem = root.get("authors");
                    if (authElem.isJsonArray()) {
                        for (JsonElement a : authElem.getAsJsonArray()) {
                            if (a.isJsonPrimitive()) {
                                authors.add(a.getAsString());
                            } else if (a.isJsonObject() && a.getAsJsonObject().has("name")) {
                                authors.add(a.getAsJsonObject().get("name").getAsString());
                            }
                        }
                    } else if (authElem.isJsonPrimitive()) {
                        authors.add(authElem.getAsString());
                    }
                }

                Map<String, String> links = new LinkedHashMap<>();
                if (root.has("contact") && root.get("contact").isJsonObject()) {
                    JsonObject contact = root.getAsJsonObject("contact");
                    for (String key : List.of("homepage", "sources", "issues", "irc", "email")) {
                        if (contact.has(key)) links.put(key, contact.get(key).getAsString());
                    }
                }

                Set<String> license = new LinkedHashSet<>();
                if (root.has("license")) {
                    JsonElement lic = root.get("license");
                    if (lic.isJsonPrimitive()) {
                        license.add(lic.getAsString());
                    } else if (lic.isJsonArray()) {
                        for (JsonElement l : lic.getAsJsonArray()) {
                            if (l.isJsonPrimitive()) license.add(l.getAsString());
                        }
                    }
                }

                return new DiskMod(id, name, version, path, isGloballyDisabled, active, core,
                        Collections.unmodifiableMap(depends), aliases, iconPath,
                        desc, List.copyOf(authors), Collections.unmodifiableMap(links), Set.copyOf(license));
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public synchronized List<String> applyFileRenames() {
        ensureRegistryLoaded();
        List<String> logMessages = new ArrayList<>();
        MutoDev.log("STAGING", "applying renames. disables=" + stagedDisables + ", enables=" + stagedEnables);

        List<DiskMod> all = scanDiskMods();
        Map<String, DiskMod> byId = new HashMap<>();
        for (DiskMod m : all) {
            byId.put(m.id(), m);
            if (m.aliases() != null) {
                for (String a : m.aliases()) byId.putIfAbsent(a, m);
            }
        }

        for (String id : stagedDisables) {
            sessionDisabledMods.add(id);
            DiskMod m = byId.get(id);
            if (m == null) continue;
            sessionDisabledMods.add(m.id());
            if (m.aliases() != null) sessionDisabledMods.addAll(m.aliases());
            Path curr = m.filePath();
            String currName = curr.getFileName().toString();
            if (!isDisabledFileName(currName)) {
                String targetName = toDisabledJarName(currName);
                Path target = curr.resolveSibling(targetName);
                try {
                    Files.move(curr, target, StandardCopyOption.REPLACE_EXISTING);
                    deferredShutdownRenames.remove(curr);
                    logMessages.add("Disabled: " + id + " -> " + targetName);
                    MutoDev.log("RENAME", "renamed " + currName + " -> " + targetName);
                } catch (Throwable locked) {
                    boolean ok = JarUnlocker.unlockAndMove(curr, target);
                    if (ok) {
                        deferredShutdownRenames.remove(curr);
                        logMessages.add("Disabled: " + id + " -> " + targetName);
                        MutoDev.log("RENAME", "renamed " + currName + " -> " + targetName);
                    } else {
                        deferredShutdownRenames.put(curr, target);
                        MutoLog.info("jar {} locked, queued for post-teardown and shutdown rename", currName);
                        logMessages.add("Disabled: " + id);
                    }
                }
            } else {
                logMessages.add("Disabled: " + id);
            }
        }

        for (String id : stagedEnables) {
            sessionDisabledMods.remove(id);
            DiskMod m = byId.get(id);
            if (m == null) continue;
            sessionDisabledMods.remove(m.id());
            if (m.aliases() != null) sessionDisabledMods.removeAll(m.aliases());
            Path curr = m.filePath();
            String currName = curr.getFileName().toString();
            deferredShutdownRenames.keySet().removeIf(k -> k.equals(curr) || k.getFileName().toString().equals(currName));
            if (isDisabledFileName(currName)) {
                String targetName = toEnabledJarName(currName);
                Path target = curr.resolveSibling(targetName);
                try {
                    Files.move(curr, target, StandardCopyOption.REPLACE_EXISTING);
                    logMessages.add("Enabled: " + id + " -> " + targetName);
                    MutoDev.log("RENAME", "renamed " + currName + " -> " + targetName);
                } catch (Throwable locked) {
                    boolean ok = JarUnlocker.unlockAndMove(curr, target);
                    if (ok) {
                        logMessages.add("Enabled: " + id + " -> " + targetName);
                    } else {
                        scheduleDetachedRename(curr, target);
                        logMessages.add("Enabled: " + id);
                    }
                }
            } else {
                logMessages.add("Enabled: " + id);
            }
        }

        saveDisabledRegistry();
        stagedDisables.clear();
        stagedEnables.clear();
        invalidateCache();
        scanDiskMods();
        return logMessages;
    }
}
