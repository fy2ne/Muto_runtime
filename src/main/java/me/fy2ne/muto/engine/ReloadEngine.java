package me.fy2ne.muto.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import me.fy2ne.muto.MutoLog;
import me.fy2ne.muto.api.ModDiff;
import me.fy2ne.muto.api.MutoEvents;
import me.fy2ne.muto.engine.di.DiContainerGuard;
import me.fy2ne.muto.engine.knot.KnotInjector;
import me.fy2ne.muto.engine.mixin.DynamicMixinEngine;
import me.fy2ne.muto.engine.resource.ResourceHotMount;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Closeable;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ReloadEngine {
    public static final ReloadEngine INSTANCE = new ReloadEngine();

    private static final int MAX_HISTORY = 50;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type HISTORY_LIST_TYPE = new TypeToken<ArrayList<ReloadHistoryEntry>>() {}.getType();

    private final AtomicBoolean reloading = new AtomicBoolean(false);
    private volatile ModSnapshot currentSnapshot = ModSnapshot.empty();
    private volatile boolean autoReloadResources = true;
    private volatile Path lastModsDir;
    private final Map<String, List<Object>> instancesByMod = new LinkedHashMap<>();

    private static final List<ReloadHistoryEntry> HISTORY = new CopyOnWriteArrayList<>();
    private static boolean historyLoaded = false;

    private ReloadEngine() {}

    public boolean isReloading() {
        return reloading.get();
    }

    public boolean isAutoReloadResources() {
        return autoReloadResources;
    }

    public void setAutoReloadResources(boolean enabled) {
        this.autoReloadResources = enabled;
    }

    public ModSnapshot currentSnapshot() {
        return currentSnapshot;
    }

    public void recordInitialSnapshot(Path modsDir) {
        this.lastModsDir = modsDir;
        ensureHistoryLoaded();
        List<ScannedMod> scanned = (modsDir != null && java.nio.file.Files.isDirectory(modsDir))
                ? ModScanner.scan(modsDir, false)
                : Collections.emptyList();
        currentSnapshot = new ModSnapshot(scanned);
        DynamicMixinEngine.recordBaseline(scanned);
        me.fy2ne.muto.dev.MutoDev.log("SNAPSHOT", String.format("baseline snapshot recorded: %d mods", currentSnapshot.mods().size()));
        MutoLog.info("baseline snapshot: {} mods ({} reloadable, {} stubborn)",
                currentSnapshot.mods().size(),
                currentSnapshot.reloadableMods().size(),
                currentSnapshot.stubbornMods().size());
    }

    public ClassLoader currentLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    public ReloadPlan plan(Path modsDir) {
        this.lastModsDir = modsDir;
        List<ScannedMod> scanned = ModScanner.scan(modsDir, true);
        ModSnapshot target = new ModSnapshot(scanned);
        ModDiff diff = currentSnapshot.diffAgainst(target);
        return new ReloadPlan(
                currentSnapshot,
                target,
                diff,
                target.reloadableMods(),
                target.stubbornMods()
        );
    }

    public CompletableFuture<ReloadResult> reloadAsync(Path modsDir, Consumer<String> stageNotifier) {
        return CompletableFuture.supplyAsync(() -> execute(plan(modsDir), stageNotifier));
    }

    public CompletableFuture<ReloadResult> loadModAsync(Path jarPath, Consumer<String> stageNotifier) {
        return CompletableFuture.supplyAsync(() -> {
            if (jarPath == null || !Files.exists(jarPath)) {
                return ReloadResult.fail(new ModDiff(Set.of(), Set.of(), Set.of(), Set.of()), 0L, "jar does not exist: " + jarPath);
            }
            ScannedMod probed = ModScanner.probe(jarPath);
            if (probed == null) {
                return ReloadResult.fail(new ModDiff(Set.of(), Set.of(), Set.of(), Set.of()), 0L, "invalid mod jar (no fabric.mod.json): " + jarPath.getFileName());
            }

            Map<String, ScannedMod> map = new LinkedHashMap<>(currentSnapshot.mods());
            map.put(probed.id(), probed);
            ModSnapshot target = new ModSnapshot(new ArrayList<>(map.values()));
            ModDiff diff = currentSnapshot.diffAgainst(target);
            ReloadPlan plan = new ReloadPlan(currentSnapshot, target, diff, List.of(probed), Collections.emptyList());
            return execute(plan, stageNotifier);
        });
    }

    public CompletableFuture<ReloadResult> unloadModAsync(String modId, Consumer<String> stageNotifier) {
        return CompletableFuture.supplyAsync(() -> {
            if (modId == null || modId.isBlank()) {
                return ReloadResult.fail(new ModDiff(Set.of(), Set.of(), Set.of(), Set.of()), 0L, "empty mod id");
            }
            if (!currentSnapshot.mods().containsKey(modId)) {
                // If not in currentSnapshot, try to locate in FabricLoader
                boolean inLoader = false;
                try {
                    inLoader = FabricLoader.getInstance() != null && FabricLoader.getInstance().getModContainer(modId).isPresent();
                } catch (Throwable ignored) {}
                if (!inLoader) {
                    return ReloadResult.fail(new ModDiff(Set.of(), Set.of(), Set.of(), Set.of()), 0L, "mod is not loaded: " + modId);
                }
            }

            Map<String, ScannedMod> map = new LinkedHashMap<>(currentSnapshot.mods());
            map.remove(modId);
            ModSnapshot target = new ModSnapshot(new ArrayList<>(map.values()));
            ModDiff diff = currentSnapshot.diffAgainst(target);
            ReloadPlan plan = new ReloadPlan(currentSnapshot, target, diff, Collections.emptyList(), Collections.emptyList());
            return execute(plan, stageNotifier);
        });
    }

    public List<ReloadHistoryEntry> history() {
        ensureHistoryLoaded();
        return Collections.unmodifiableList(HISTORY);
    }

    private static synchronized void ensureHistoryLoaded() {
        if (historyLoaded) return;
        historyLoaded = true;
        try {
            Path file = resolveHistoryPath();
            if (file != null && Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    List<ReloadHistoryEntry> saved = GSON.fromJson(reader, HISTORY_LIST_TYPE);
                    if (saved != null) {
                        HISTORY.addAll(saved);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static synchronized void appendHistory(ReloadHistoryEntry entry) {
        ensureHistoryLoaded();
        HISTORY.add(entry);
        while (HISTORY.size() > MAX_HISTORY) {
            HISTORY.removeFirst();
        }
        try {
            Path file = resolveHistoryPath();
            if (file == null) return;
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("history.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(new ArrayList<>(HISTORY), HISTORY_LIST_TYPE, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {}
    }

    private static Path resolveHistoryPath() {
        try {
            FabricLoader fl = FabricLoader.getInstance();
            if (fl != null && fl.getConfigDir() != null) {
                return fl.getConfigDir().resolve("muto").resolve("history.json");
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public synchronized ReloadResult execute(ReloadPlan plan, Consumer<String> stageNotifier) {
        if (!reloading.compareAndSet(false, true)) {
            MutoLog.warn("reload already in progress, rejecting request");
            return ReloadResult.fail(plan.diff(), 0L, "reload in progress");
        }

        long startMs = System.currentTimeMillis();
        me.fy2ne.muto.dev.MutoDev.notifyReloadStart();
        me.fy2ne.muto.dev.MutoDev.log("RELOAD", "execute started. Diff: " + plan.diff());
        MutoEvents.RELOAD_START.invoker().onReloadStart(startMs, plan.diff());
        List<String> stepLogs = new ArrayList<>();

        try {
            ModDiff diff = plan.diff();

            if (!plan.hasWork()) {
                me.fy2ne.muto.dev.MutoDev.diagnoseNoOp(plan.currentSnapshot(), plan.targetSnapshot(), lastModsDir);
                notify(stageNotifier, stepLogs, "Scanning /mods: all mod files are up to date.");
                notify(stageNotifier, stepLogs, "No mod changes detected — reload is a no-op.");
                long noopMs = System.currentTimeMillis() - startMs;
                MutoLog.info("no mod changes detected, reload is a no-op ({}ms)", noopMs);
                MutoEvents.RELOAD_FINISH.invoker().onReloadFinish(
                        System.currentTimeMillis(), diff, true, noopMs, null);
                appendHistory(new ReloadHistoryEntry(startMs, noopMs, diff, true, stepLogs));
                return ReloadResult.ok(diff, noopMs);
            }

            notify(stageNotifier, stepLogs, String.format("Scanning /mods: %d active mods detected in directory.", plan.targetSnapshot().mods().size()));

            if (!diff.added().isEmpty()) {
                notify(stageNotifier, stepLogs, String.format("Detected +%d new mod(s): %s", diff.added().size(), String.join(", ", diff.added())));
            }
            if (!diff.removed().isEmpty()) {
                notify(stageNotifier, stepLogs, String.format("Detected -%d removed mod(s): %s", diff.removed().size(), String.join(", ", diff.removed())));
            }
            if (!diff.updated().isEmpty()) {
                notify(stageNotifier, stepLogs, String.format("Detected ~%d modified mod(s): %s", diff.updated().size(), String.join(", ", diff.updated())));
            }

            List<ScannedMod> initializable = new ArrayList<>();
            for (ScannedMod mod : plan.reloadable()) {
                if (diff.added().contains(mod.id()) || diff.updated().contains(mod.id())) {
                    initializable.add(mod);
                }
            }

            ClassLoader knotCl = KnotInjector.getKnotClassLoader();
            ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(knotCl);

            Set<String> affectedNamespaces = new LinkedHashSet<>();
            affectedNamespaces.addAll(diff.added());
            affectedNamespaces.addAll(diff.removed());
            affectedNamespaces.addAll(diff.updated());

            Map<String, List<Object>> backupInstances = new LinkedHashMap<>();
            for (String ns : affectedNamespaces) {
                List<Object> existing = instancesByMod.get(ns);
                if (existing != null) {
                    backupInstances.put(ns, new ArrayList<>(existing));
                }
            }
            var registryBackup = RegistryFreezer.snapshotNamespaces(affectedNamespaces);

            try {
                // ── PHASE A: TEARDOWN (removed + updated mods) ──
                if (!diff.removed().isEmpty()) {
                    notify(stageNotifier, stepLogs, "Tearing down removed mods...");
                    for (String rem : diff.removed()) {
                        teardownMod(rem);
                        RegistryFreezer.pruneNamespace(rem);
                        notify(stageNotifier, stepLogs, "torn down: " + rem);
                    }
                }
                for (String upd : diff.updated()) {
                    teardownMod(upd);
                    RegistryFreezer.pruneNamespace(upd);
                    notify(stageNotifier, stepLogs, "torn down for update: " + upd);
                }

                // ── PHASE B: FABRIC LOADER SYNC ──
                notify(stageNotifier, stepLogs, "Synchronizing Fabric Loader mod metadata...");
                syncFabricLoader(plan.targetSnapshot(), diff);
                if (!diff.removed().isEmpty()) {
                    me.fy2ne.muto.mods.ModStateManager.INSTANCE.retryPendingDisables();
                }

                // ── PHASE C: FORWARD INJECTION (added + updated mods) ──
                List<Path> allInjectedPaths = new ArrayList<>();
                if (!initializable.isEmpty()) {
                    notify(stageNotifier, stepLogs, "Injecting mod jars into KnotClassLoader...");
                    for (ScannedMod m : initializable) {
                        List<Path> injected = KnotInjector.injectWithNested(m.jarPath());
                        allInjectedPaths.addAll(injected);
                        notify(stageNotifier, stepLogs, String.format("Knot classpath registered: %s (%d jars)", m.id(), injected.size()));
                    }
                }

                for (ScannedMod mod : initializable) {
                    preflightCheck(knotCl, mod);
                }

                if (!allInjectedPaths.isEmpty()) {
                    notify(stageNotifier, stepLogs, "Weaving dynamic mixins...");
                    for (Path p : allInjectedPaths) {
                        int retransformed = DynamicMixinEngine.injectAndRetransform(p);
                        if (retransformed > 0) {
                            notify(stageNotifier, stepLogs, String.format("Retransformed %d classes for %s", retransformed, p.getFileName()));
                        }
                    }
                }

                notify(stageNotifier, stepLogs, "Unfreezing registries & invoking entrypoints in Knot...");
                RegistryFreezer.unfreezeAll();

                for (ScannedMod mod : initializable) {
                    notify(stageNotifier, stepLogs, String.format("Invoking entrypoints for %s...", mod.id()));
                    initializeMod(mod);
                }

                notify(stageNotifier, stepLogs, "Finalizing engine state & freezing registries...");
                RegistryFreezer.freezeAll();

                syncKeyMappings();

                if (autoReloadResources && (!initializable.isEmpty() || !diff.removed().isEmpty())) {
                    notify(stageNotifier, stepLogs, "Mounting resources & reloading OpenGL shaders...");
                    try {
                        ResourceHotMount.remountAndReload().get(120, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Throwable t) {
                        MutoLog.warn("resource hot mount wait warning: {}", t.getMessage());
                    }
                }

                currentSnapshot = plan.targetSnapshot();
                long durationMs = System.currentTimeMillis() - startMs;
                String finishMsg = String.format("Reload finished cleanly in %dms (+%d -%d ~%d)",
                        durationMs, diff.added().size(), diff.removed().size(), diff.updated().size());
                notify(stageNotifier, stepLogs, finishMsg);
                MutoLog.info(finishMsg);

                MutoEvents.RELOAD_FINISH.invoker().onReloadFinish(
                        System.currentTimeMillis(),
                        diff,
                        true,
                        durationMs,
                        null
                );

                appendHistory(new ReloadHistoryEntry(startMs, durationMs, diff, true, stepLogs));
                return ReloadResult.ok(diff, durationMs);

            } catch (Throwable err) {
                long durationMs = System.currentTimeMillis() - startMs;
                String failMsg = "Reload failed (rolled back): " + err.getMessage();
                me.fy2ne.muto.dev.MutoDev.log("RELOAD-FAIL", failMsg);
                notify(stageNotifier, stepLogs, failMsg);
                MutoLog.error("reload failed, executing transaction rollback: {}", err.getMessage(), err);

                // roll back newly created instances and restore prior state
                for (ScannedMod m : initializable) {
                    teardownMod(m.id());
                }
                instancesByMod.putAll(backupInstances);
                RegistryFreezer.restoreSnapshot(affectedNamespaces, registryBackup);
                RegistryFreezer.freezeAll();
                try {
                    syncFabricLoader(plan.currentSnapshot(), new ModDiff(diff.removed(), diff.added(), diff.updated(), diff.unchanged()));
                } catch (Throwable ignored) {}

                MutoEvents.RELOAD_FINISH.invoker().onReloadFinish(
                        System.currentTimeMillis(),
                        diff,
                        false,
                        durationMs,
                        err
                );

                appendHistory(new ReloadHistoryEntry(startMs, durationMs, diff, false, stepLogs));
                return ReloadResult.fail(diff, durationMs, err.getMessage());
            } finally {
                Thread.currentThread().setContextClassLoader(prevCl);
            }
        } finally {
            reloading.set(false);
        }
    }

    private void preflightCheck(ClassLoader loader, ScannedMod mod) throws ClassNotFoundException {
        for (String epClass : mod.getClassesFor("main")) {
            Class.forName(epClass, false, loader);
        }
        for (String epClass : mod.getClassesFor("client")) {
            Class.forName(epClass, false, loader);
        }
    }

    private void teardownMod(String modId) {
        List<Object> instances = instancesByMod.remove(modId);
        if (instances != null) {
            for (Object inst : instances) {
                closeQuietly(inst);
            }
        }
        DynamicMixinEngine.unweave(modId, null);
        purgeKeyMappings(modId);
        purgeModOptions(modId);
        teardownNetworking(modId);
        DiContainerGuard.teardown(modId);
    }

    private void purgeKeyMappings(String modId) {
        if (modId == null) return;
        try {
            net.minecraft.client.Minecraft mc = null;
            try { mc = net.minecraft.client.Minecraft.getInstance(); } catch (Throwable ignored) {}
            if (mc == null) return;

            final String lowerId = modId.toLowerCase(Locale.ROOT);
            final net.minecraft.client.Minecraft client = mc;
            Runnable purge = () -> {
                try {
                    try {
                        Class<?> regClass = Class.forName("net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl");
                        Field f = regClass.getDeclaredField("MODDED_KEY_BINDINGS");
                        f.setAccessible(true);
                        List<?> list = (List<?>) f.get(null);
                        if (list != null) {
                            synchronized (list) {
                                list.removeIf(item -> isModKeyMapping(item, lowerId));
                            }
                        }
                    } catch (Throwable ignored) {}

                    try {
                        Field allField = net.minecraft.client.KeyMapping.class.getDeclaredField("ALL");
                        allField.setAccessible(true);
                        Map<?, ?> allMap = (Map<?, ?>) allField.get(null);
                        if (allMap != null) {
                            allMap.entrySet().removeIf(e -> {
                                String k = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
                                if (k.contains("." + lowerId + ".") || k.startsWith(lowerId + ".") || k.contains(lowerId)) {
                                    return true;
                                }
                                return isModKeyMapping(e.getValue(), lowerId);
                            });
                        }

                        Field mapField = net.minecraft.client.KeyMapping.class.getDeclaredField("MAP");
                        mapField.setAccessible(true);
                        Map<?, ?> keyMap = (Map<?, ?>) mapField.get(null);
                        if (keyMap != null) {
                            for (Object listObj : keyMap.values()) {
                                if (listObj instanceof List<?> kmList) {
                                    kmList.removeIf(km -> isModKeyMapping(km, lowerId));
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    if (client.options != null && client.options.keyMappings != null) {
                        List<net.minecraft.client.KeyMapping> retained = new ArrayList<>();
                        for (net.minecraft.client.KeyMapping km : client.options.keyMappings) {
                            if (!isModKeyMapping(km, lowerId)) {
                                retained.add(km);
                            }
                        }
                        net.minecraft.client.KeyMapping[] arr = retained.toArray(new net.minecraft.client.KeyMapping[0]);
                        try {
                            ((me.fy2ne.muto.mixin.OptionsAccessor) client.options).muto$setKeyMappings(arr);
                        } catch (Throwable fallback) {
                            try {
                                Field uField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                                uField.setAccessible(true);
                                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uField.get(null);
                                Field kmField = net.minecraft.client.Options.class.getDeclaredField("keyMappings");
                                long offset = unsafe.objectFieldOffset(kmField);
                                unsafe.putObject(client.options, offset, arr);
                            } catch (Throwable ignored) {}
                        }
                    }

                    net.minecraft.client.KeyMapping.resetMapping();
                    MutoLog.info("purged keymappings for mod: {}", lowerId);
                } catch (Throwable t) {
                    MutoLog.warn("error purging keymappings for {}: {}", lowerId, t.getMessage());
                }
            };

            if (!client.isSameThread()) {
                client.submit(purge).get();
            } else {
                purge.run();
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isModKeyMapping(Object obj, String modId) {
        if (!(obj instanceof net.minecraft.client.KeyMapping km)) return false;
        String name = km.getName() != null ? km.getName().toLowerCase(Locale.ROOT) : "";
        if (name.contains("." + modId + ".") || name.startsWith(modId + ".") || name.contains(modId) || name.endsWith("." + modId)) return true;
        try {
            if (km.getCategory() != null) {
                String cat = String.valueOf(km.getCategory()).toLowerCase(Locale.ROOT);
                if (cat.contains(modId)) return true;
            }
        } catch (Throwable ignored) {}
        String clsName = km.getClass().getName().toLowerCase(Locale.ROOT);
        return clsName.contains("." + modId + ".") || clsName.contains(modId);
    }

    private void purgeModOptions(String modId) {
        if (modId == null) return;
        final String lowerId = modId.toLowerCase(Locale.ROOT);

        try {
            Class<?> mmClass = Class.forName("com.terraformersmc.modmenu.ModMenu");
            Field factField = null;
            try {
                factField = mmClass.getDeclaredField("configScreenFactories");
                factField.setAccessible(true);
            } catch (NoSuchFieldException ignored) {}
            if (factField != null) {
                Map<?, ?> factories = (Map<?, ?>) factField.get(null);
                if (factories != null) {
                    factories.remove(lowerId);
                    factories.remove(modId);
                }
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> cmClass = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager");
            Field cuField = null;
            try {
                cuField = cmClass.getDeclaredField("configUsers");
                cuField.setAccessible(true);
            } catch (NoSuchFieldException ignored) {}
            if (cuField != null) {
                Object cu = cuField.get(null);
                if (cu instanceof Collection<?> col) {
                    col.removeIf(item -> item != null && item.toString().toLowerCase(Locale.ROOT).contains(lowerId));
                }
            }

            Field cfgField = cmClass.getDeclaredField("CONFIG");
            cfgField.setAccessible(true);
            Object cfg = cfgField.get(null);
            if (cfg != null) {
                Field moField = cfg.getClass().getDeclaredField("modOptions");
                moField.setAccessible(true);
                List<?> current = (List<?>) moField.get(cfg);
                if (current != null) {
                    List<Object> filtered = new ArrayList<>();
                    for (Object mo : current) {
                        boolean match = false;
                        try {
                            Method cidM = mo.getClass().getMethod("configId");
                            String cid = (String) cidM.invoke(mo);
                            if (cid != null && (cid.equalsIgnoreCase(modId) || cid.equalsIgnoreCase(lowerId))) {
                                match = true;
                            }
                        } catch (Throwable ignored) {}
                        if (!match) {
                            filtered.add(mo);
                        }
                    }
                    if (filtered.size() != current.size()) {
                        Field uField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                        uField.setAccessible(true);
                        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uField.get(null);
                        long offset = unsafe.objectFieldOffset(moField);
                        unsafe.putObject(cfg, offset, Collections.unmodifiableList(filtered));
                        MutoLog.info("purged {} from sodium modOptions ({} -> {})", modId, current.size(), filtered.size());
                    }
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("sodium mod options purge skipped for {}: {}", modId, t.getMessage());
        }
    }

    private void teardownNetworking(String modId) {
        if (modId == null) return;
        // purge C2S and S2C payload type registrations
        try {
            Class<?> ptrClass = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl");
            for (var f : ptrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    Object registry = f.get(null);
                    if (registry != null) {
                        purgePayloadRegistry(registry, modId);
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Fabric networking API not present
        } catch (Throwable t) {
            MutoLog.warn("payload registry purge skipped for {}: {}", modId, t.getMessage());
        }

        // purge global receiver handlers
        try {
            Class<?> grrClass = Class.forName("net.fabricmc.fabric.impl.networking.GlobalReceiverRegistry");
            for (var field : grrClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!java.util.Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var map = (java.util.Map<?, ?>) field.get(null);
                if (map == null) continue;
                int before = map.size();
                map.entrySet().removeIf(e -> matchesModNamespace(e.getKey(), modId));
                int pruned = before - map.size();
                if (pruned > 0) {
                    MutoLog.info("pruned {} global receivers from {} for {}", pruned, field.getName(), modId);
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            MutoLog.warn("global receiver purge skipped for {}: {}", modId, t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void purgePayloadRegistry(Object registry, String modId) {
        try {
            for (var field : registry.getClass().getDeclaredFields()) {
                if (!java.util.Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                var map = (java.util.Map<?, ?>) field.get(registry);
                if (map == null) continue;
                int before = map.size();
                map.entrySet().removeIf(entry ->
                        matchesModNamespace(entry.getKey(), modId) || matchesModNamespace(entry.getValue(), modId));
                int pruned = before - map.size();
                if (pruned > 0) {
                    MutoLog.info("pruned {} payload mappings from {} for {}", pruned, field.getName(), modId);
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("payload field scan exception for {}: {}", modId, t.getMessage());
        }
    }

    private boolean matchesModNamespace(Object obj, String modId) {
        if (obj == null) return false;
        // Try getNamespace() directly — works for ResourceLocation, Identifier, etc.
        try {
            var ns = (String) obj.getClass().getMethod("getNamespace").invoke(obj);
            return modId.equalsIgnoreCase(ns);
        } catch (Throwable ignored) {}
        // CustomPacketPayload.Type or wrapper: call id() then getNamespace()
        try {
            var res = obj.getClass().getMethod("id").invoke(obj);
            if (res != null) {
                var ns = (String) res.getClass().getMethod("getNamespace").invoke(res);
                return modId.equalsIgnoreCase(ns);
            }
        } catch (Throwable ignored) {}
        // getId() then getNamespace()
        try {
            var res = obj.getClass().getMethod("getId").invoke(obj);
            if (res != null) {
                var ns = (String) res.getClass().getMethod("getNamespace").invoke(res);
                return modId.equalsIgnoreCase(ns);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void closeQuietly(Object inst) {
        try {
            if (inst instanceof Closeable c) {
                c.close();
            } else if (inst instanceof AutoCloseable ac) {
                ac.close();
            }
        } catch (Exception ex) {
            MutoLog.warn("error closing mod instance {}: {}", inst.getClass().getName(), ex.getMessage());
        }
    }

    private void initializeMod(ScannedMod mod) throws Exception {
        DiContainerGuard.prepare(mod.id());
        List<Object> created = new ArrayList<>();
        ClassLoader knotCl = Thread.currentThread().getContextClassLoader();

        for (String clsName : mod.getClassesFor("preLaunch")) {
            try {
                Class<?> cls = Class.forName(clsName, true, knotCl);
                Object obj = cls.getDeclaredConstructor().newInstance();
                created.add(obj);
                if (obj instanceof net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint pre) {
                    pre.onPreLaunch();
                    MutoLog.info("initialized preLaunch in knot: {}", clsName);
                }
            } catch (Throwable epErr) {
                MutoLog.warn("preLaunch entrypoint notice in {} ({}): {}", mod.id(), clsName, epErr.getMessage());
            }
        }

        for (String clsName : mod.getClassesFor("main")) {
            try {
                Class<?> cls = Class.forName(clsName, true, knotCl);
                Object obj = cls.getDeclaredConstructor().newInstance();
                created.add(obj);

                if (obj instanceof ModInitializer init) {
                    init.onInitialize();
                    MutoLog.info("initialized main in knot: {}", clsName);
                }
            } catch (Throwable epErr) {
                MutoLog.warn("main entrypoint notice in {} ({}): {}", mod.id(), clsName, epErr.getMessage());
            }
        }

        for (String clsName : mod.getClassesFor("client")) {
            try {
                Class<?> cls = Class.forName(clsName, true, knotCl);
                Object obj = cls.getDeclaredConstructor().newInstance();
                created.add(obj);

                if (obj instanceof ClientModInitializer clientInit) {
                    net.minecraft.client.Minecraft mc = null;
                    try {
                        mc = net.minecraft.client.Minecraft.getInstance();
                    } catch (Throwable ignored) {}
                    if (mc != null && !mc.isSameThread()) {
                        mc.submit(clientInit::onInitializeClient).get();
                    } else {
                        clientInit.onInitializeClient();
                    }
                    MutoLog.info("initialized client in knot: {}", clsName);
                }
            } catch (Throwable epErr) {
                MutoLog.warn("client entrypoint notice in {} ({}): {}", mod.id(), clsName, epErr.getMessage());
            }
        }

        for (String clsName : mod.getClassesFor("server")) {
            try {
                Class<?> cls = Class.forName(clsName, true, knotCl);
                Object obj = cls.getDeclaredConstructor().newInstance();
                created.add(obj);

                if (obj instanceof net.fabricmc.api.DedicatedServerModInitializer serverInit) {
                    serverInit.onInitializeServer();
                    MutoLog.info("initialized server in knot: {}", clsName);
                }
            } catch (Throwable epErr) {
                MutoLog.warn("server entrypoint notice in {} ({}): {}", mod.id(), clsName, epErr.getMessage());
            }
        }

        List<String> sodiumConfigs = mod.getClassesFor("sodium:config_api_user");
        if (!sodiumConfigs.isEmpty()) {
            try {
                Class<?> cmCls = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager", true, knotCl);
                java.lang.reflect.Method regMethod = cmCls.getMethod("registerConfigEntryPoint", String.class, String.class);
                java.lang.reflect.Method lateMethod = cmCls.getMethod("registerConfigsLate");
                for (String cfgCls : sodiumConfigs) {
                    regMethod.invoke(null, cfgCls, mod.id());
                    MutoLog.info("re-registered sodium:config_api_user for {}: {}", mod.id(), cfgCls);
                }
                lateMethod.invoke(null);
                MutoLog.info("invoked sodium registerConfigsLate for {}", mod.id());
            } catch (Throwable t) {
                MutoLog.warn("failed re-registering sodium config user for {}: {}", mod.id(), t.getMessage());
            }
        }

        List<String> modmenuEps = mod.getClassesFor("modmenu");
        if (!modmenuEps.isEmpty()) {
            try {
                Class<?> mmCls = Class.forName("com.terraformersmc.modmenu.ModMenu", true, knotCl);
                java.lang.reflect.Field factField = mmCls.getDeclaredField("configScreenFactories");
                factField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Object> factories = (Map<String, Object>) factField.get(null);

                Class<?> apiCls = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi", true, knotCl);
                java.lang.reflect.Method getFactory = apiCls.getMethod("getModConfigScreenFactory");
                java.lang.reflect.Method getProvided = null;
                try {
                    getProvided = apiCls.getMethod("getProvidedConfigScreenFactories");
                } catch (Throwable ignored) {}

                for (String epCls : modmenuEps) {
                    try {
                        Class<?> cls = Class.forName(epCls, true, knotCl);
                        if (apiCls.isAssignableFrom(cls)) {
                            Object apiInst = cls.getDeclaredConstructor().newInstance();
                            created.add(apiInst);
                            Object fact = getFactory.invoke(apiInst);
                            if (fact != null && factories != null) {
                                factories.put(mod.id(), fact);
                                MutoLog.info("registered modmenu configScreenFactory for {}: {}", mod.id(), epCls);
                            }
                            if (getProvided != null && factories != null) {
                                Map<?, ?> prov = (Map<?, ?>) getProvided.invoke(apiInst);
                                if (prov != null) {
                                    for (var e : prov.entrySet()) {
                                        if (e.getKey() != null && e.getValue() != null) {
                                            factories.put(String.valueOf(e.getKey()), e.getValue());
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable epErr) {
                        MutoLog.warn("modmenu entrypoint notice in {} ({}): {}", mod.id(), epCls, epErr.getMessage());
                    }
                }
            } catch (Throwable t) {
                MutoLog.warn("failed registering modmenu api entrypoint for {}: {}", mod.id(), t.getMessage());
            }
        }

        if (!created.isEmpty()) {
            instancesByMod.computeIfAbsent(mod.id(), k -> new ArrayList<>()).addAll(created);
        }
    }

    @SuppressWarnings("unchecked")
    private void syncFabricLoader(ModSnapshot snapshot, ModDiff diff) {
        try {
            FabricLoader fl = FabricLoader.getInstance();
            Field modMapField = null;
            Field modsListField = null;
            Class<?> cur = fl.getClass();
            while (cur != null && (modMapField == null || modsListField == null)) {
                try {
                    if (modMapField == null) modMapField = cur.getDeclaredField("modMap");
                } catch (NoSuchFieldException ignored) {}
                try {
                    if (modsListField == null) modsListField = cur.getDeclaredField("mods");
                } catch (NoSuchFieldException ignored) {}
                cur = cur.getSuperclass();
            }

            if (modMapField != null) {
                modMapField.setAccessible(true);
                Map<String, Object> map = (Map<String, Object>) modMapField.get(fl);
                List<Object> list = null;
                if (modsListField != null) {
                    modsListField.setAccessible(true);
                    list = (List<Object>) modsListField.get(fl);
                }

                Field epStorageField = null;
                Field adapterMapField = null;
                cur = fl.getClass();
                while (cur != null && (epStorageField == null || adapterMapField == null)) {
                    try {
                        if (epStorageField == null) epStorageField = cur.getDeclaredField("entrypointStorage");
                    } catch (NoSuchFieldException ignored) {}
                    try {
                        if (adapterMapField == null) adapterMapField = cur.getDeclaredField("adapterMap");
                    } catch (NoSuchFieldException ignored) {}
                    cur = cur.getSuperclass();
                }

                Object epStorage = null;
                Map<?, ?> adapterMap = null;
                Map<?, ?> entryMap = null;
                Method addEpMethod = null;

                if (epStorageField != null) {
                    epStorageField.setAccessible(true);
                    epStorage = epStorageField.get(fl);
                    if (epStorage != null) {
                        try {
                            Field emField = epStorage.getClass().getDeclaredField("entryMap");
                            emField.setAccessible(true);
                            entryMap = (Map<?, ?>) emField.get(epStorage);
                        } catch (Throwable ignored) {}
                        for (Method m : epStorage.getClass().getMethods()) {
                            if (m.getName().equals("add") && m.getParameterCount() == 4) {
                                addEpMethod = m;
                                break;
                            }
                        }
                    }
                }
                if (adapterMapField != null) {
                    adapterMapField.setAccessible(true);
                    adapterMap = (Map<?, ?>) adapterMapField.get(fl);
                }

                if (diff != null) {
                    for (String removedId : diff.removed()) {
                        if (map != null) map.remove(removedId);
                        if (list != null) {
                            list.removeIf(item -> {
                                if (item instanceof net.fabricmc.loader.api.ModContainer mc) {
                                    return mc.getMetadata().getId().equals(removedId);
                                }
                                return false;
                            });
                        }
                        if (entryMap != null) {
                            for (Object entryListObj : entryMap.values()) {
                                if (entryListObj instanceof List<?> epList) {
                                    epList.removeIf(entryItem -> {
                                        try {
                                            Method gmc = entryItem.getClass().getMethod("getModContainer");
                                            Object modc = gmc.invoke(entryItem);
                                            if (modc instanceof net.fabricmc.loader.api.ModContainer mc) {
                                                return mc.getMetadata().getId().equals(removedId);
                                            }
                                        } catch (Throwable ignored) {}
                                        return false;
                                    });
                                }
                            }
                        }
                    }

                    for (String addedId : diff.added()) {
                        ScannedMod mod = snapshot.mods().get(addedId);
                        if (mod == null) continue;
                        try {
                            Object container = createContainer(mod);
                            if (container != null) {
                                if (map != null) map.put(mod.id(), container);
                                if (list != null && !list.contains(container)) list.add(container);
                                if (epStorage != null && addEpMethod != null && container instanceof net.fabricmc.loader.api.ModContainer) {
                                    try {
                                        Method getMetaMethod = container.getClass().getMethod("getMetadata");
                                        Object meta = getMetaMethod.invoke(container);
                                        Method getKeysMethod = meta.getClass().getMethod("getEntrypointKeys");
                                        Collection<?> keys = (Collection<?>) getKeysMethod.invoke(meta);
                                        Method getEpsMethod = meta.getClass().getMethod("getEntrypoints", String.class);
                                        for (Object keyObj : keys) {
                                            String k = String.valueOf(keyObj);
                                            List<?> eps = (List<?>) getEpsMethod.invoke(meta, k);
                                            if (eps != null) {
                                                for (Object ep : eps) {
                                                    try {
                                                        addEpMethod.invoke(epStorage, container, k, ep, adapterMap);
                                                    } catch (Throwable ignored) {}
                                                }
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                MutoLog.info("synced {} into FabricLoader", mod.id());
                            }
                        } catch (Throwable ex) {
                            MutoLog.warn("failed registering container for {}: {}", mod.id(), ex.getMessage());
                        }
                    }
                }

                MutoLog.info("fabric loader modMap synced (tracked mods: {})", snapshot.mods().size());
            }
        } catch (Throwable t) {
            MutoLog.warn("fabric loader modMap sync skipped: {}", t.getMessage());
        }
    }

    private Object createContainer(ScannedMod mod) {
        try {
            Optional<net.fabricmc.loader.api.ModContainer> existing = FabricLoader.getInstance().getModContainer(mod.id());
            if (existing.isPresent()) return existing.get();

            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(mod.jarPath().toFile())) {
                java.util.jar.JarEntry entry = jf.getJarEntry("fabric.mod.json");
                if (entry != null) {
                    try (java.io.InputStream is = jf.getInputStream(entry)) {
                        Class<?> parserCls = Class.forName("net.fabricmc.loader.impl.metadata.ModMetadataParser");
                        java.lang.reflect.Method parseMethod = null;
                        for (java.lang.reflect.Method m : parserCls.getMethods()) {
                            if (m.getName().equals("parseMetadata")) {
                                parseMethod = m;
                                break;
                            }
                        }
                        if (parseMethod != null) {
                            Object vOverrides = null;
                            try {
                                Class<?> voCls = Class.forName("net.fabricmc.loader.impl.metadata.VersionOverrides");
                                vOverrides = voCls.getDeclaredConstructor().newInstance();
                            } catch (Throwable ignored) {}

                            Object dOverrides = null;
                            try {
                                Class<?> doCls = Class.forName("net.fabricmc.loader.impl.metadata.DependencyOverrides");
                                dOverrides = doCls.getDeclaredConstructor(java.nio.file.Path.class).newInstance(FabricLoader.getInstance().getConfigDir());
                            } catch (Throwable ignored) {}

                            Class<?>[] pTypes = parseMethod.getParameterTypes();
                            Object[] args = new Object[pTypes.length];
                            for (int i = 0; i < pTypes.length; i++) {
                                Class<?> pt = pTypes[i];
                                if (java.io.InputStream.class.isAssignableFrom(pt)) {
                                    args[i] = is;
                                } else if (String.class.isAssignableFrom(pt)) {
                                    args[i] = mod.jarPath().toString();
                                } else if (List.class.isAssignableFrom(pt)) {
                                    args[i] = List.of();
                                } else if (pt.getName().contains("VersionOverrides")) {
                                    args[i] = vOverrides;
                                } else if (pt.getName().contains("DependencyOverrides")) {
                                    args[i] = dOverrides;
                                } else if (pt == boolean.class) {
                                    args[i] = Boolean.FALSE;
                                } else if (pt == int.class) {
                                    args[i] = 0;
                                } else if (pt.isPrimitive()) {
                                    args[i] = 0;
                                } else {
                                    args[i] = null;
                                }
                            }
                            Object meta = parseMethod.invoke(null, args);

                            Class<?> candidateCls = Class.forName("net.fabricmc.loader.impl.discovery.ModCandidateImpl");
                            java.lang.reflect.Method createPlain = null;
                            for (java.lang.reflect.Method m : candidateCls.getDeclaredMethods()) {
                                if (m.getName().equals("createPlain")) {
                                    createPlain = m;
                                    break;
                                }
                            }
                            if (createPlain != null) {
                                createPlain.setAccessible(true);
                                Class<?>[] cpTypes = createPlain.getParameterTypes();
                                Object[] cArgs = new Object[cpTypes.length];
                                for (int i = 0; i < cpTypes.length; i++) {
                                    Class<?> pt = cpTypes[i];
                                    if (List.class.isAssignableFrom(pt)) {
                                        cArgs[i] = List.of(mod.jarPath());
                                    } else if (Collection.class.isAssignableFrom(pt)) {
                                        cArgs[i] = List.of();
                                    } else if (pt.isInstance(meta) || pt.getName().contains("Metadata")) {
                                        cArgs[i] = meta;
                                    } else if (pt == boolean.class) {
                                        cArgs[i] = Boolean.FALSE;
                                    } else if (pt == int.class) {
                                        cArgs[i] = 0;
                                    } else if (pt.isPrimitive()) {
                                        cArgs[i] = 0;
                                    } else {
                                        cArgs[i] = null;
                                    }
                                }
                                Object candidate = createPlain.invoke(null, cArgs);

                                Class<?> containerCls = Class.forName("net.fabricmc.loader.impl.ModContainerImpl");
                                java.lang.reflect.Constructor<?> ctor = containerCls.getDeclaredConstructor(candidateCls);
                                ctor.setAccessible(true);
                                return ctor.newInstance(candidate);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ex) {
            Throwable root = ex.getCause() != null ? ex.getCause() : ex;
            MutoLog.warn("reflection ModContainer creation error for {}: {}, using dynamic proxy", mod.id(), root.toString());
        }

        return createFallbackProxyContainer(mod);
    }

    private Object createFallbackProxyContainer(ScannedMod mod) {
        try {
            ClassLoader cl = getClass().getClassLoader();
            Class<?> modContainerInterface = net.fabricmc.loader.api.ModContainer.class;
            Class<?> modMetadataInterface = net.fabricmc.loader.api.metadata.ModMetadata.class;

            me.fy2ne.muto.mods.DiskMod dm = me.fy2ne.muto.mods.ModStateManager.INSTANCE.findDiskMod(mod.id());
            Object metaProxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{modMetadataInterface}, (proxy, method, args) -> {
                String name = method.getName();
                Class<?> ret = method.getReturnType();
                if ("getId".equals(name)) return mod.id();
                if ("getName".equals(name)) return dm != null ? dm.name() : mod.name();
                if ("getVersion".equals(name)) {
                    return net.fabricmc.loader.api.Version.parse(mod.version().isBlank() ? "1.0.0" : mod.version());
                }
                if ("getType".equals(name)) return "fabric";
                if ("getDescription".equals(name)) return dm != null && dm.description() != null ? dm.description() : "";
                if ("getAuthors".equals(name) || "getContributors".equals(name) || "getLicense".equals(name)) {
                    return List.of();
                }
                if ("getContact".equals(name)) return net.fabricmc.loader.api.metadata.ContactInformation.EMPTY;
                if ("getProvides".equals(name) || "getDependencies".equals(name)) return List.of();
                if ("getCustomValues".equals(name)) return Map.of();
                if ("getEnvironment".equals(name)) return net.fabricmc.api.EnvType.CLIENT;
                if ("loadsInEnvironment".equals(name)) return Boolean.TRUE;
                if ("isBuiltin".equals(name)) return Boolean.FALSE;
                if ("containsCustomValue".equals(name)) return Boolean.FALSE;
                if ("getIconPath".equals(name)) {
                    return dm != null && dm.iconPath() != null && !dm.iconPath().isBlank() ? Optional.of(dm.iconPath()) : Optional.empty();
                }
                if ("getIcons".equals(name)) return Optional.empty();
                if ("toString".equals(name)) return mod.id() + " " + mod.version();

                if (ret == boolean.class) return Boolean.FALSE;
                if (ret == int.class) return 0;
                if (ret == long.class) return 0L;
                if (ret == float.class) return 0.0f;
                if (ret == double.class) return 0.0d;
                if (List.class.isAssignableFrom(ret) || Collection.class.isAssignableFrom(ret)) return List.of();
                if (Set.class.isAssignableFrom(ret)) return Set.of();
                if (Map.class.isAssignableFrom(ret)) return Map.of();
                if (Optional.class.isAssignableFrom(ret)) return Optional.empty();
                return null;
            });

            Class<?> modOriginInterface = net.fabricmc.loader.api.metadata.ModOrigin.class;
            Object originProxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{modOriginInterface}, (proxy, method, mArgs) -> {
                String mName = method.getName();
                Class<?> ret = method.getReturnType();
                if ("getPaths".equals(mName)) return List.of(mod.jarPath());
                if ("getKind".equals(mName)) {
                    for (Object c : ret.getEnumConstants()) {
                        if ("PATH".equals(c.toString())) return c;
                    }
                }
                if (ret == boolean.class) return Boolean.FALSE;
                if (ret == int.class) return 0;
                if (List.class.isAssignableFrom(ret)) return List.of();
                return null;
            });

            return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{modContainerInterface}, (proxy, method, args) -> {
                String name = method.getName();
                Class<?> ret = method.getReturnType();
                if ("getMetadata".equals(name)) return metaProxy;
                if ("getRootPaths".equals(name)) return List.of(mod.jarPath());
                if ("getRootPath".equals(name)) return mod.jarPath();
                if ("getOrigin".equals(name)) return originProxy;
                if ("getContainingMod".equals(name)) return Optional.empty();
                if ("getContainedMods".equals(name)) return List.of();
                if ("findPath".equals(name) || "getPath".equals(name)) return Optional.empty();
                if ("toString".equals(name)) return mod.id() + " " + mod.version();

                if (ret == boolean.class) return Boolean.FALSE;
                if (ret == int.class) return 0;
                if (ret == long.class) return 0L;
                if (ret == float.class) return 0.0f;
                if (ret == double.class) return 0.0d;
                if (List.class.isAssignableFrom(ret) || Collection.class.isAssignableFrom(ret)) return List.of();
                if (Set.class.isAssignableFrom(ret)) return Set.of();
                if (Map.class.isAssignableFrom(ret)) return Map.of();
                if (Optional.class.isAssignableFrom(ret)) return Optional.empty();
                return null;
            });
        } catch (Throwable t) {
            MutoLog.warn("failed creating fallback proxy container: {}", t.getMessage());
            return null;
        }
    }

    public void syncKeyMappings() {
        try {
            net.minecraft.client.Minecraft mc = null;
            try {
                mc = net.minecraft.client.Minecraft.getInstance();
            } catch (Throwable ignored) {}
            if (mc == null || mc.options == null) return;

            final net.minecraft.client.Minecraft client = mc;
            Runnable sync = () -> {
                try {
                    Class<?> regClass = Class.forName("net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl");
                    java.lang.reflect.Method processMethod = regClass.getMethod("process", net.minecraft.client.KeyMapping[].class);
                    net.minecraft.client.KeyMapping[] updated = (net.minecraft.client.KeyMapping[]) processMethod.invoke(null, (Object) client.options.keyMappings);
                    if (updated != null) {
                        try {
                            ((me.fy2ne.muto.mixin.OptionsAccessor) client.options).muto$setKeyMappings(updated);
                        } catch (Throwable fallback) {
                            java.lang.reflect.Field uField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                            uField.setAccessible(true);
                            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uField.get(null);
                            java.lang.reflect.Field kmField = net.minecraft.client.Options.class.getDeclaredField("keyMappings");
                            long offset = unsafe.objectFieldOffset(kmField);
                            unsafe.putObject(client.options, offset, updated);
                        }
                        net.minecraft.client.KeyMapping.resetMapping();
                        MutoLog.info("synced {} total key mappings into active game options", updated.length);
                    }
                } catch (Throwable t) {
                    MutoLog.warn("keymapping sync note: {}", t.getMessage());
                }
            };
            if (!client.isSameThread()) {
                client.submit(sync).get();
            } else {
                sync.run();
            }
        } catch (Throwable t) {
            MutoLog.warn("failed dispatching keymapping sync: {}", t.getMessage());
        }
    }

    private Path resolveJarForMod(String modId) {
        if (modId == null) return null;
        try {
            var dm = me.fy2ne.muto.mods.ModStateManager.INSTANCE.findDiskMod(modId);
            if (dm != null && dm.filePath() != null && java.nio.file.Files.exists(dm.filePath())) {
                return dm.filePath();
            }
        } catch (Throwable ignored) {}

        if (currentSnapshot != null) {
            ScannedMod sm = currentSnapshot.mods().get(modId);
            if (sm != null && sm.jarPath() != null) {
                if (java.nio.file.Files.exists(sm.jarPath())) {
                    return sm.jarPath();
                }
                String name = sm.jarPath().getFileName().toString();
                for (String ext : List.of(".disabled", ".disactivation", ".discavation", ".deactivation", ".disable", ".off")) {
                    Path p = sm.jarPath().resolveSibling(name + ext);
                    if (java.nio.file.Files.exists(p)) return p;
                }
            }
        }
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (java.nio.file.Files.isDirectory(modsDir)) {
            try (var stream = java.nio.file.Files.list(modsDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String fn = p.getFileName().toString();
                    if (fn.endsWith(".jar") || me.fy2ne.muto.mods.ModStateManager.isDisabledFileName(fn)) {
                        ScannedMod probed = ModScanner.probe(p);
                        if (probed != null && modId.equals(probed.id())) {
                            return p;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void notify(Consumer<String> notifier, List<String> logs, String msg) {
        MutoLog.info("stage: {}", msg);
        me.fy2ne.muto.dev.MutoDev.log("STAGE", msg);
        if (logs != null) logs.add(msg);
        if (notifier != null) {
            notifier.accept(msg);
        }
    }
}
