package me.fy2ne.muto.engine.mixin;

import me.fy2ne.muto.MutoLog;
import me.fy2ne.muto.engine.ScannedMod;
import me.fy2ne.muto.engine.agent.MutoAgentBootstrap;
import me.fy2ne.muto.engine.knot.KnotInjector;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;

import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class DynamicMixinEngine {
    private static final MutoClassFileTransformer TRANSFORMER = new MutoClassFileTransformer();
    private static volatile boolean transformerInstalled = false;

    private static final Map<String, Set<String>> modIdToConfigs = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> KNOWN_MOD_TARGETS = new ConcurrentHashMap<>();

    private DynamicMixinEngine() {}

    public static Map<String, Set<String>> getModIdToConfigs() {
        return Collections.unmodifiableMap(modIdToConfigs);
    }

    public static MutoClassFileTransformer getTransformer() {
        return TRANSFORMER;
    }

    public static boolean hasActiveConfigsFor(String dotted) {
        if (dotted == null || dotted.isEmpty()) return false;
        for (Set<String> targets : KNOWN_MOD_TARGETS.values()) {
            if (targets.contains(dotted)) {
                return true;
            }
        }
        try {
            Set<Config> allConfigs = Mixins.getConfigs();
            if (allConfigs != null && !allConfigs.isEmpty()) {
                for (Config cfg : allConfigs) {
                    var iConfig = cfg.getConfig();
                    if (iConfig != null && iConfig.getTargets() != null && iConfig.getTargets().contains(dotted)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static synchronized void recordBaseline(List<ScannedMod> scanned) {
        modIdToConfigs.clear();
        KNOWN_MOD_TARGETS.clear();
        if (scanned == null) return;
        for (ScannedMod m : scanned) {
            if (m.hasMixins() && m.jarPath() != null) {
                List<String> configs = extractConfigs(m.jarPath());
                if (!configs.isEmpty()) {
                    modIdToConfigs.put(m.id(), new HashSet<>(configs));
                    Set<String> targets = resolveTargets(configs);
                    if (targets.isEmpty()) {
                        targets = scanJarMixinTargets(m.jarPath());
                    }
                    if (!targets.isEmpty()) {
                        KNOWN_MOD_TARGETS.put(m.id(), targets);
                    }
                }
            }
        }
        MutoLog.info("baseline mixin configs indexed for {} mods", modIdToConfigs.size());
    }

    public static synchronized int injectAndRetransform(Path jarPath) {
        return injectAndRetransform(extractModId(jarPath), jarPath);
    }

    public static synchronized int injectAndRetransform(String modId, Path jarPath) {
        if (jarPath == null) return 0;

        List<String> configs = extractConfigs(jarPath);
        if (configs.isEmpty()) {
            return 0;
        }

        if (modId != null) {
            modIdToConfigs.computeIfAbsent(modId, k -> ConcurrentHashMap.newKeySet()).addAll(configs);
        }

        for (String cfg : configs) {
            try {
                Mixins.addConfiguration(cfg);
                MutoLog.info("registered dynamic mixin config: {}", cfg);
            } catch (Throwable t) {
                MutoLog.warn("error registering mixin config {}: {}", cfg, t.getMessage());
            }
        }

        Set<String> targets = resolveTargets(configs);
        if (targets.isEmpty()) {
            targets = scanJarMixinTargets(jarPath);
            if (!targets.isEmpty()) {
                MutoLog.info("resolved {} mixin targets from bytecode scan for {}", targets.size(), jarPath.getFileName());
            }
        }

        if (modId != null && !targets.isEmpty()) {
            KNOWN_MOD_TARGETS.computeIfAbsent(modId, k -> ConcurrentHashMap.newKeySet()).addAll(targets);
        }

        if (targets.isEmpty()) {
            MutoLog.info("no loaded target classes discovered for configs in {}", jarPath.getFileName());
            return 0;
        }

        return retransformClasses(targets);
    }

    public static synchronized int unweave(String modId, Path jarPath) {
        if (modId == null) return 0;
        modIdToConfigs.remove(modId);
        KNOWN_MOD_TARGETS.remove(modId);
        MutoLog.info("unweave state cleared for {} (safe teardown mode)", modId);
        return 0;
    }

    public static String extractModId(Path jarPath) {
        if (jarPath == null || !java.nio.file.Files.exists(jarPath)) return null;
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            JarEntry entry = jf.getJarEntry("fabric.mod.json");
            if (entry != null) {
                try (var is = jf.getInputStream(entry);
                     var reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    var root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("id")) {
                        return root.get("id").getAsString();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static List<String> extractConfigs(Path jarPath) {
        List<String> configs = new ArrayList<>();
        if (jarPath == null || !java.nio.file.Files.exists(jarPath)) return configs;

        try (JarFile jf = new JarFile(jarPath.toFile())) {
            JarEntry entry = jf.getJarEntry("fabric.mod.json");
            if (entry != null) {
                try (var is = jf.getInputStream(entry);
                     var reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("mixins")) {
                        var mixinsEl = root.get("mixins");
                        if (mixinsEl.isJsonArray()) {
                            for (var el : mixinsEl.getAsJsonArray()) {
                                if (el.isJsonPrimitive()) {
                                    configs.add(el.getAsString());
                                } else if (el.isJsonObject() && el.getAsJsonObject().has("config")) {
                                    configs.add(el.getAsJsonObject().get("config").getAsString());
                                }
                            }
                        }
                    }

                    if (root.has("jars") && root.get("jars").isJsonArray()) {
                        for (var je : root.getAsJsonArray("jars")) {
                            if (je.isJsonObject() && je.getAsJsonObject().has("file")) {
                                String nPath = je.getAsJsonObject().get("file").getAsString();
                                JarEntry ne = jf.getJarEntry(nPath);
                                if (ne != null) {
                                    try (var nIs = jf.getInputStream(ne);
                                         java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(nIs)) {
                                        java.util.zip.ZipEntry ze;
                                        while ((ze = zis.getNextEntry()) != null) {
                                            if ("fabric.mod.json".equals(ze.getName())) {
                                                var nRoot = com.google.gson.JsonParser.parseReader(new InputStreamReader(zis, StandardCharsets.UTF_8)).getAsJsonObject();
                                                if (nRoot.has("mixins") && nRoot.get("mixins").isJsonArray()) {
                                                    for (var el : nRoot.getAsJsonArray("mixins")) {
                                                        if (el.isJsonPrimitive()) configs.add(el.getAsString());
                                                        else if (el.isJsonObject() && el.getAsJsonObject().has("config")) {
                                                            configs.add(el.getAsJsonObject().get("config").getAsString());
                                                        }
                                                    }
                                                }
                                                break;
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }
            }

            if (configs.isEmpty()) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    var e = entries.nextElement();
                    String name = e.getName();
                    if (!name.contains("/") && name.endsWith(".mixins.json")) {
                        configs.add(name);
                    }
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("failed parsing fabric.mod.json mixins from {}: {}", jarPath.getFileName(), t.getMessage());
        }

        return configs;
    }

    private static Set<String> resolveTargets(List<String> configs) {
        Set<String> targets = new HashSet<>();
        try {
            Set<Config> allConfigs = Mixins.getConfigs();
            if (allConfigs == null) return targets;

            for (Config cfg : allConfigs) {
                if (configs.contains(cfg.getName()) || configs.stream().anyMatch(c -> c.endsWith(cfg.getName()))) {
                    var iConfig = cfg.getConfig();
                    if (iConfig != null && iConfig.getTargets() != null) {
                        targets.addAll(iConfig.getTargets());
                    }
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("error resolving target classes from mixin configs: {}", t.getMessage());
        }
        return targets;
    }

    public static int retransformClasses(Set<String> targetNames) {
        return retransformTargetsInternal(targetNames);
    }

    public static int retransformTargets(Set<String> targetNames) {
        return retransformTargetsInternal(targetNames);
    }

    public static int retransformTargets(Set<String> targetNames, boolean isUnweaving) {
        // isUnweaving param kept for signature compat but ignored — unweaving is deprecated
        return retransformTargets(targetNames);
    }

    private static int retransformTargetsInternal(Set<String> targetNames) {
        if (targetNames == null || targetNames.isEmpty()) return 0;

        Instrumentation inst = MutoAgentBootstrap.getInstrumentation();
        if (inst == null) {
            MutoLog.warn("instrumentation unavailable, cannot trigger bytecode retransformation");
            return 0;
        }

        if (!inst.isRetransformClassesSupported()) {
            MutoLog.warn("jvm does not support retransformClasses");
            return 0;
        }

        if (!transformerInstalled) {
            synchronized (DynamicMixinEngine.class) {
                if (!transformerInstalled) {
                    inst.addTransformer(TRANSFORMER, true);
                    transformerInstalled = true;
                    MutoLog.info("muto classfile transformer installed");
                }
            }
        }

        TRANSFORMER.addTargets(targetNames);

        Class<?>[] loaded = inst.getAllLoadedClasses();
        List<Class<?>> matched = new ArrayList<>();
        for (Class<?> clazz : loaded) {
            if (targetNames.contains(clazz.getName()) && inst.isModifiableClass(clazz)) {
                matched.add(clazz);
            }
        }

        if (matched.isEmpty()) {
            MutoLog.info("none of {} mixin target classes are currently loaded", targetNames.size());
            return 0;
        }

        int successCount = 0;
        for (Class<?> c : matched) {
            try {
                inst.retransformClasses(c);
                successCount++;
                MutoLog.info("retransformed {} successfully", c.getName());
            } catch (Throwable err) {
                MutoLog.warn("skipping schema-restricted target {}: {}", c.getName(), err.getMessage());
            }
        }
        MutoLog.info("isolated retransformation complete: {}/{} classes updated", successCount, matched.size());
        return successCount;
    }

    public static Set<String> scanJarMixinTargets(Path jarPath) {
        Set<String> targets = new HashSet<>();
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                    try (var is = jf.getInputStream(entry)) {
                        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(is);
                        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                                if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) {
                                    return new org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
                                        @Override
                                        public void visit(String name, Object value) {
                                            if (value instanceof org.objectweb.asm.Type t) {
                                                targets.add(t.getClassName());
                                            } else if (value instanceof String s) {
                                                targets.add(s.replace('/', '.'));
                                            }
                                        }

                                        @Override
                                        public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
                                            return new org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
                                                @Override
                                                public void visit(String n, Object v) {
                                                    if (v instanceof org.objectweb.asm.Type t) {
                                                        targets.add(t.getClassName());
                                                    } else if (v instanceof String s) {
                                                        targets.add(s.replace('/', '.'));
                                                    }
                                                }
                                            };
                                        }
                                    };
                                }
                                return null;
                            }
                        }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            MutoLog.warn("error scanning jar mixin targets for {}: {}", jarPath.getFileName(), t.getMessage());
        }
        return targets;
    }
}
