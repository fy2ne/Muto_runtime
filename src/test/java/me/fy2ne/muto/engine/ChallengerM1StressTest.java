package me.fy2ne.muto.engine;

import me.fy2ne.muto.engine.di.DiContainerGuard;
import me.fy2ne.muto.engine.mixin.DynamicMixinEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class ChallengerM1StressTest implements Opcodes {

    // =========================================================================
    // 1. Empirical Verification: DiContainerGuard against simulated Distant Horizons
    // =========================================================================

    private byte[] generateDistantHorizonsInjectorBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "com/seibel/distanthorizons/core/dependencyInjection/SingletonInjector", null, "java/lang/Object", null);

        // public static SingletonInjector INSTANCE;
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "INSTANCE", "Lcom/seibel/distanthorizons/core/dependencyInjection/SingletonInjector;", null, null).visitEnd();

        // public boolean allowDuplicateBindings;
        cw.visitField(ACC_PUBLIC, "allowDuplicateBindings", "Z", null, null).visitEnd();

        // public Map dependencies;
        cw.visitField(ACC_PUBLIC, "dependencies", "Ljava/util/Map;", null, null).visitEnd();

        // Constructor
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        // this.allowDuplicateBindings = false;
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_0);
        mv.visitFieldInsn(PUTFIELD, "com/seibel/distanthorizons/core/dependencyInjection/SingletonInjector", "allowDuplicateBindings", "Z");

        // this.dependencies = new HashMap();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, "java/util/HashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        mv.visitFieldInsn(PUTFIELD, "com/seibel/distanthorizons/core/dependencyInjection/SingletonInjector", "dependencies", "Ljava/util/Map;");

        // INSTANCE = this;
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(PUTSTATIC, "com/seibel/distanthorizons/core/dependencyInjection/SingletonInjector", "INSTANCE", "Lcom/seibel/distanthorizons/core/dependencyInjection/SingletonInjector;");

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // public void clear() { dependencies.clear(); }
        MethodVisitor clearMv = cw.visitMethod(ACC_PUBLIC, "clear", "()V", null, null);
        clearMv.visitCode();
        clearMv.visitVarInsn(ALOAD, 0);
        clearMv.visitFieldInsn(GETFIELD, "com/seibel/distanthorizons/core/dependencyInjection/SingletonInjector", "dependencies", "Ljava/util/Map;");
        clearMv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "clear", "()V", true);
        clearMv.visitInsn(RETURN);
        clearMv.visitMaxs(0, 0);
        clearMv.visitEnd();

        // public void bind(Class clazz, Object obj)
        MethodVisitor bindMv = cw.visitMethod(ACC_PUBLIC, "bind", "(Ljava/lang/Class;Ljava/lang/Object;)V", null, null);
        bindMv.visitCode();
        // if (dependencies.containsKey(clazz) && !allowDuplicateBindings) throw new IllegalStateException("Duplicate binding!");
        bindMv.visitVarInsn(ALOAD, 0);
        bindMv.visitFieldInsn(GETFIELD, "com/seibel/distanthorizons/core/dependencyInjection/SingletonInjector", "dependencies", "Ljava/util/Map;");
        bindMv.visitVarInsn(ALOAD, 1);
        bindMv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z", true);
        org.objectweb.asm.Label okLabel = new org.objectweb.asm.Label();
        bindMv.visitJumpInsn(IFEQ, okLabel);

        bindMv.visitVarInsn(ALOAD, 0);
        bindMv.visitFieldInsn(GETFIELD, "com/seibel/distanthorizons/core/dependencyInjection/SingletonInjector", "allowDuplicateBindings", "Z");
        bindMv.visitJumpInsn(IFNE, okLabel);

        bindMv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        bindMv.visitInsn(DUP);
        bindMv.visitLdcInsn("Duplicate binding!");
        bindMv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        bindMv.visitInsn(ATHROW);

        bindMv.visitLabel(okLabel);
        bindMv.visitVarInsn(ALOAD, 0);
        bindMv.visitFieldInsn(GETFIELD, "com/seibel/distanthorizons/core/dependencyInjection/SingletonInjector", "dependencies", "Ljava/util/Map;");
        bindMv.visitVarInsn(ALOAD, 1);
        bindMv.visitVarInsn(ALOAD, 2);
        bindMv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        bindMv.visitInsn(POP);
        bindMv.visitInsn(RETURN);
        bindMv.visitMaxs(0, 0);
        bindMv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void testEmpiricalDistantHorizonsDiGuardExecution() throws Exception {
        DynamicBytecodeLoader cl = new DynamicBytecodeLoader(getClass().getClassLoader());
        byte[] bytes = generateDistantHorizonsInjectorBytes();
        cl.register("com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector", bytes);

        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(cl);
            Class<?> injectorClass = Class.forName("com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector", true, cl);
            Object injector = injectorClass.getDeclaredConstructor().newInstance();

            Method bindMethod = injectorClass.getMethod("bind", Class.class, Object.class);
            Field allowDupField = injectorClass.getField("allowDuplicateBindings");
            Field depsField = injectorClass.getField("dependencies");

            // Initial state: allowDuplicateBindings == false
            Assertions.assertFalse(allowDupField.getBoolean(injector));

            // First bind succeeds
            bindMethod.invoke(injector, String.class, "first");

            // Second bind throws IllegalStateException wrapped in InvocationTargetException
            java.lang.reflect.InvocationTargetException ex = Assertions.assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> bindMethod.invoke(injector, String.class, "second")
            );
            Assertions.assertInstanceOf(IllegalStateException.class, ex.getCause());

            // NOW TRIGGER DiContainerGuard.prepare("distanthorizons")!
            DiContainerGuard.prepare("distanthorizons");

            // Verify allowDuplicateBindings was changed to true by DiContainerGuard!
            Assertions.assertTrue(allowDupField.getBoolean(injector), "allowDuplicateBindings must be true after DiContainerGuard.prepare");

            // Now second bind must succeed without throwing!
            Assertions.assertDoesNotThrow(() -> bindMethod.invoke(injector, String.class, "second"));

            // NOW TRIGGER DiContainerGuard.teardown("distanthorizons")!
            DiContainerGuard.teardown("distanthorizons");

            // Verify dependencies map was cleared!
            Map<?, ?> deps = (Map<?, ?>) depsField.get(injector);
            Assertions.assertTrue(deps.isEmpty(), "dependencies map must be cleared after DiContainerGuard.teardown");

            // Test alternative casing "DISTANT_HORIZONS"
            DiContainerGuard.prepare("DISTANT_HORIZONS");
            Assertions.assertTrue(allowDupField.getBoolean(injector));
        } finally {
            Thread.currentThread().setContextClassLoader(oldCl);
        }
    }

    // =========================================================================
    // 2. Empirical Verification: DiContainerGuard against simulated Essential
    // =========================================================================

    private byte[] generateClassWithStaticBooleans(String internalName, String... fieldNames) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        for (String f : fieldNames) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, f, "Z", null, null).visitEnd();
        }

        // Static initializer to set them true initially
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (String f : fieldNames) {
            clinit.visitInsn(ICONST_1);
            clinit.visitFieldInsn(PUTSTATIC, internalName, f, "Z");
        }
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void testEmpiricalEssentialFlagResetExecution() throws Exception {
        DynamicBytecodeLoader cl = new DynamicBytecodeLoader(getClass().getClassLoader());
        cl.register("gg.essential.Essential", generateClassWithStaticBooleans("gg/essential/Essential", "initialized", "getInstanceIsLocked"));
        cl.register("gg.essential.DI", generateClassWithStaticBooleans("gg/essential/DI", "hasStarted"));
        cl.register("gg.essential.api.utils.DiKt", generateClassWithStaticBooleans("gg/essential/api/utils/DiKt", "initialised"));

        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(cl);

            Class<?> essentialClass = Class.forName("gg.essential.Essential", true, cl);
            Class<?> diClass = Class.forName("gg.essential.DI", true, cl);
            Class<?> diKtClass = Class.forName("gg.essential.api.utils.DiKt", true, cl);

            Field initF = essentialClass.getField("initialized");
            Field lockF = essentialClass.getField("getInstanceIsLocked");
            Field startedF = diClass.getField("hasStarted");
            Field initialisedF = diKtClass.getField("initialised");

            // Verify they are initially TRUE
            Assertions.assertTrue(initF.getBoolean(null));
            Assertions.assertTrue(lockF.getBoolean(null));
            Assertions.assertTrue(startedF.getBoolean(null));
            Assertions.assertTrue(initialisedF.getBoolean(null));

            // NOW TRIGGER DiContainerGuard.teardown("essential")!
            DiContainerGuard.teardown("essential");

            // Verify all 4 flags were reset to FALSE!
            Assertions.assertFalse(initF.getBoolean(null), "Essential.initialized must be reset to false");
            Assertions.assertFalse(lockF.getBoolean(null), "Essential.getInstanceIsLocked must be reset to false");
            Assertions.assertFalse(startedF.getBoolean(null), "DI.hasStarted must be reset to false");
            Assertions.assertFalse(initialisedF.getBoolean(null), "DiKt.initialised must be reset to false");

            // Test prepare("ESSENTIAL") with uppercase mod ID
            initF.setBoolean(null, true);
            DiContainerGuard.prepare("ESSENTIAL");
            Assertions.assertFalse(initF.getBoolean(null));
        } finally {
            Thread.currentThread().setContextClassLoader(oldCl);
        }
    }

    // =========================================================================
    // 3. Empirical Stress: Adversarial inputs and malicious hooks in DiContainerGuard
    // =========================================================================

    @Test
    void testDiContainerGuardAdversarialInputs() {
        Assertions.assertDoesNotThrow(() -> {
            DiContainerGuard.prepare("");
            DiContainerGuard.teardown("");
            DiContainerGuard.prepare("   ");
            DiContainerGuard.teardown("   ");
            DiContainerGuard.prepare(null);
            DiContainerGuard.teardown(null);
            DiContainerGuard.prepare("weird/path/..//test.mod");
            DiContainerGuard.teardown("weird/path/..//test.mod");
            DiContainerGuard.prepare("mod_with_unicode_\u2603_\uD83D\uDE80");
            DiContainerGuard.teardown("mod_with_unicode_\u2603_\uD83D\uDE80");
            DiContainerGuard.prepare("a".repeat(1000));
            DiContainerGuard.teardown("a".repeat(1000));
        });

        // Register a hook that maliciously throws
        DiContainerGuard.registerHook(new DiContainerGuard.DiGuardHook() {
            @Override
            public boolean matches(String modId) {
                return "exploding_mod".equals(modId);
            }

            @Override
            public void prepare(String modId) {
                throw new RuntimeException("Kaboom in prepare");
            }

            @Override
            public void teardown(String modId) {
                throw new IllegalStateException("Kaboom in teardown");
            }
        });

        // DiContainerGuard must swallow and log, NOT crash the caller
        Assertions.assertDoesNotThrow(() -> {
            DiContainerGuard.prepare("exploding_mod");
            DiContainerGuard.teardown("exploding_mod");
        });
    }

    // =========================================================================
    // 4. Empirical Verification: DynamicMixinEngine exact unweaving (foo vs foobar)
    // =========================================================================

    @Test
    void testDynamicMixinEngineExactPrefixIsolation(@TempDir Path tempDir) throws IOException {
        Path fooJar = createJar(tempDir, "foo-1.0.jar", "foo", List.of("foo.mixins.json"));
        Path foobarJar = createJar(tempDir, "foobar-1.0.jar", "foobar", List.of("foobar.mixins.json"));
        Path fooAddonJar = createJar(tempDir, "foo_addon-1.0.jar", "foo_addon", List.of("foo_addon.mixins.json"));
        Path barJar = createJar(tempDir, "bar-1.0.jar", "bar", List.of("bar.mixins.json"));
        Path singleCharJar = createJar(tempDir, "o-1.0.jar", "o", List.of("o.mixins.json"));

        DynamicMixinEngine.injectAndRetransform("foo", fooJar);
        DynamicMixinEngine.injectAndRetransform("foobar", foobarJar);
        DynamicMixinEngine.injectAndRetransform("foo_addon", fooAddonJar);
        DynamicMixinEngine.injectAndRetransform("bar", barJar);
        DynamicMixinEngine.injectAndRetransform("o", singleCharJar);

        Map<String, Set<String>> cfgMap = DynamicMixinEngine.getModIdToConfigs();
        Assertions.assertEquals(5, cfgMap.size());
        Assertions.assertTrue(cfgMap.get("foo").contains("foo.mixins.json"));
        Assertions.assertTrue(cfgMap.get("foobar").contains("foobar.mixins.json"));
        Assertions.assertTrue(cfgMap.get("foo_addon").contains("foo_addon.mixins.json"));
        Assertions.assertTrue(cfgMap.get("bar").contains("bar.mixins.json"));
        Assertions.assertTrue(cfgMap.get("o").contains("o.mixins.json"));

        // UNWEAVE 'foo' — must NOT unweave foobar or foo_addon!
        DynamicMixinEngine.unweave("foo", fooJar);

        Assertions.assertFalse(cfgMap.containsKey("foo"), "foo must be unwove");
        Assertions.assertTrue(cfgMap.containsKey("foobar"), "foobar must REMAIN");
        Assertions.assertTrue(cfgMap.containsKey("foo_addon"), "foo_addon must REMAIN");
        Assertions.assertTrue(cfgMap.containsKey("bar"), "bar must REMAIN");
        Assertions.assertTrue(cfgMap.containsKey("o"), "o must REMAIN");

        // UNWEAVE 'o' (substring of foo and foobar) — must NOT touch others
        DynamicMixinEngine.unweave("o", singleCharJar);

        Assertions.assertFalse(cfgMap.containsKey("o"), "o must be unwove");
        Assertions.assertTrue(cfgMap.containsKey("foobar"), "foobar must REMAIN");
        Assertions.assertTrue(cfgMap.containsKey("foo_addon"), "foo_addon must REMAIN");
        Assertions.assertTrue(cfgMap.containsKey("bar"), "bar must REMAIN");

        // UNWEAVE with null or missing jar
        Assertions.assertDoesNotThrow(() -> DynamicMixinEngine.unweave("non_existent_mod", null));
        Assertions.assertDoesNotThrow(() -> DynamicMixinEngine.unweave(null, null));

        // Clean up remaining
        DynamicMixinEngine.unweave("foobar", foobarJar);
        DynamicMixinEngine.unweave("foo_addon", fooAddonJar);
        DynamicMixinEngine.unweave("bar", barJar);
        Assertions.assertTrue(cfgMap.isEmpty());
    }

    private static Path createJar(Path dir, String fileName, String modId, List<String> mixinConfigs) throws IOException {
        Path jarPath = dir.resolve(fileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new ZipEntry("fabric.mod.json"));
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"schemaVersion\": 1,\n");
            sb.append("  \"id\": \"").append(modId).append("\",\n");
            sb.append("  \"version\": \"1.0.0\",\n");
            sb.append("  \"name\": \"").append(modId).append("\",\n");
            sb.append("  \"mixins\": [");
            for (int i = 0; i < mixinConfigs.size(); i++) {
                sb.append("\"").append(mixinConfigs.get(i)).append("\"");
                if (i < mixinConfigs.size() - 1) sb.append(", ");
            }
            sb.append("]\n}");
            jos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jarPath;
    }
}
