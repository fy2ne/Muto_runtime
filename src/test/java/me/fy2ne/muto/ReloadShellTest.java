package me.fy2ne.muto;

import me.fy2ne.muto.api.ModDiff;
import me.fy2ne.muto.api.MutoEvents;
import me.fy2ne.muto.engine.*;
import me.fy2ne.muto.engine.mixin.DynamicMixinEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class ReloadShellTest {

    @BeforeEach
    void resetEngine() {
        ReloadEngine.INSTANCE.recordInitialSnapshot(null);
    }

    @Test
    void testModDiffBehavior() {
        ModDiff empty = ModDiff.EMPTY;
        Assertions.assertFalse(empty.hasChanges());

        ModDiff modified = new ModDiff(
                Set.of("sodium"),
                Set.of("broken_mod"),
                Set.of("jei"),
                Set.of("muto")
        );
        Assertions.assertTrue(modified.hasChanges());
        Assertions.assertTrue(modified.added().contains("sodium"));
        Assertions.assertTrue(modified.removed().contains("broken_mod"));
        Assertions.assertTrue(modified.updated().contains("jei"));
        Assertions.assertTrue(modified.unchanged().contains("muto"));
    }

    @Test
    void testReloadEventPipeline() {
        AtomicBoolean startFired = new AtomicBoolean(false);
        AtomicBoolean finishFired = new AtomicBoolean(false);
        AtomicLong recordedDuration = new AtomicLong(0);

        MutoEvents.RELOAD_START.register((epochMs, expectedDiff) -> {
            startFired.set(true);
        });

        MutoEvents.RELOAD_FINISH.register((epochMs, diff, success, durationMs, error) -> {
            finishFired.set(true);
            recordedDuration.set(durationMs);
        });

        long start = System.currentTimeMillis();
        MutoEvents.RELOAD_START.invoker().onReloadStart(start, ModDiff.EMPTY);
        Assertions.assertTrue(startFired.get());

        MutoEvents.RELOAD_FINISH.invoker().onReloadFinish(
                start + 50, ModDiff.EMPTY, true, 50L, null);
        Assertions.assertTrue(finishFired.get());
        Assertions.assertEquals(50L, recordedDuration.get());
    }

    @Test
    void testModScannerEmptyDirectory(@TempDir Path tempDir) {
        List<ScannedMod> mods = ModScanner.scan(tempDir);
        Assertions.assertTrue(mods.isEmpty());
    }

    @Test
    void testModScannerWithValidMod(@TempDir Path tempDir) throws IOException {
        createMockJar(tempDir, "test-mod-1.0.jar", "test_mod", "1.0.0", "me.fy2ne.test.TestMod");

        List<ScannedMod> mods = ModScanner.scan(tempDir);
        Assertions.assertEquals(1, mods.size());

        ScannedMod mod = mods.get(0);
        Assertions.assertEquals("test_mod", mod.id());
        Assertions.assertEquals("1.0.0", mod.version());
        Assertions.assertFalse(mod.hash().isBlank());
        Assertions.assertTrue(mod.hasEntrypoint("main"));
        Assertions.assertEquals(List.of("me.fy2ne.test.TestMod"), mod.getClassesFor("main"));
    }

    @Test
    void testModScannerSkipsCorruptFile(@TempDir Path tempDir) throws IOException {
        Path corrupt = tempDir.resolve("corrupt.jar");
        Files.writeString(corrupt, "this is definitely not a zip file");

        List<ScannedMod> mods = ModScanner.scan(tempDir);
        Assertions.assertTrue(mods.isEmpty());
    }

    @Test
    void testMutoClassLoaderIsolatesThirdPartyClass(@TempDir Path tempDir) throws Exception {
        Path mockJar = createMockJar(tempDir, "sample-mod-1.0.jar", "sample", "1.0.0", null);

        MutoClassLoader loader = new MutoClassLoader(
                List.of(mockJar),
                Set.of("sample"),
                ReloadShellTest.class.getClassLoader()
        );

        Assertions.assertTrue(loader.modIds().contains("sample"));
        Assertions.assertEquals(1, loader.jarPaths().size());

        loader.close();
        Assertions.assertTrue(loader.isClosed());
    }

    @Test
    void testReloadEnginePlanAndExecution(@TempDir Path tempDir) throws IOException {
        createMockJar(tempDir, "dynamic-mod-1.0.jar", "dynamic_mod", "1.0.0", "java.lang.Object");

        ReloadPlan plan = ReloadEngine.INSTANCE.plan(tempDir);
        Assertions.assertTrue(plan.hasWork());
        Assertions.assertEquals(1, plan.reloadable().size());
        Assertions.assertTrue(plan.diff().added().contains("dynamic_mod"));

        List<String> stages = new ArrayList<>();
        ReloadResult result = ReloadEngine.INSTANCE.execute(plan, stages::add);

        Assertions.assertTrue(result.success());
        Assertions.assertNull(result.error());
        Assertions.assertTrue(result.durationMs() >= 0);
        Assertions.assertFalse(stages.isEmpty());
        Assertions.assertEquals(1, ReloadEngine.INSTANCE.currentSnapshot().mods().size());
    }

    @Test
    void testNoChangesReloadIsNoOp(@TempDir Path tempDir) throws IOException {
        createMockJar(tempDir, "stable-mod.jar", "stable_mod", "1.0.0", "java.lang.Object");

        ReloadPlan first = ReloadEngine.INSTANCE.plan(tempDir);
        Assertions.assertTrue(first.hasWork());
        Assertions.assertTrue(ReloadEngine.INSTANCE.execute(first, s -> {}).success());

        ReloadPlan second = ReloadEngine.INSTANCE.plan(tempDir);
        Assertions.assertFalse(second.hasWork());
        Assertions.assertFalse(second.diff().hasChanges());

        ReloadResult result = ReloadEngine.INSTANCE.execute(second, s -> {});
        Assertions.assertTrue(result.success());
        Assertions.assertFalse(result.diff().hasChanges());
    }

    @Test
    void testReloadFailsSafelyOnCorruptJar(@TempDir Path tempDir) throws IOException {
        Path bad = tempDir.resolve("bad.jar");
        Files.writeString(bad, "corrupted");

        ReloadPlan plan = ReloadEngine.INSTANCE.plan(tempDir);
        Assertions.assertFalse(plan.hasWork());
        Assertions.assertTrue(plan.diff().added().isEmpty());
    }

    @Test
    void testProbeMixinTargets() throws Exception {
        Path jar = Path.of("Modsfortesting/skinlayers3d-fabric-1.11.3-mc26.3.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(jar), "Optional local test jar not present in CI");

        List<String> configs = DynamicMixinEngine.extractConfigs(jar);
        System.out.println("Configs extracted: " + configs);
        Assertions.assertFalse(configs.isEmpty());

        Set<String> targets = DynamicMixinEngine.scanJarMixinTargets(jar);
        System.out.println("Bytecode mixin targets resolved: " + targets);
        Assertions.assertFalse(targets.isEmpty());
    }

    @Test
    void testExactMixinConfigMapping(@TempDir Path tempDir) throws IOException {
        Path jar1 = createMockJarWithMixins(tempDir, "lib-1.0.jar", "lib", "1.0", List.of("lib.mixins.json"));
        Path jar2 = createMockJarWithMixins(tempDir, "library_addon-1.0.jar", "library_addon", "1.0", List.of("library_addon.mixins.json"));

        DynamicMixinEngine.injectAndRetransform("lib", jar1);
        DynamicMixinEngine.injectAndRetransform("library_addon", jar2);

        Map<String, Set<String>> configs = DynamicMixinEngine.getModIdToConfigs();
        Assertions.assertTrue(configs.containsKey("lib"));
        Assertions.assertTrue(configs.containsKey("library_addon"));
        Assertions.assertTrue(configs.get("lib").contains("lib.mixins.json"));
        Assertions.assertTrue(configs.get("library_addon").contains("library_addon.mixins.json"));

        // Unweaving "lib" must NOT purge "library_addon" even though "library_addon" contains "lib"
        DynamicMixinEngine.unweave("lib", jar1);

        Assertions.assertFalse(configs.containsKey("lib"));
        Assertions.assertTrue(configs.containsKey("library_addon"));
        Assertions.assertTrue(configs.get("library_addon").contains("library_addon.mixins.json"));

        DynamicMixinEngine.unweave("library_addon", jar2);
        Assertions.assertFalse(configs.containsKey("library_addon"));
    }

    private static Path createMockJarWithMixins(Path dir, String fileName, String modId, String version, List<String> mixinConfigs) throws IOException {
        Path jarPath = dir.resolve(fileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new ZipEntry("fabric.mod.json"));
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"schemaVersion\": 1,\n");
            sb.append("  \"id\": \"").append(modId).append("\",\n");
            sb.append("  \"version\": \"").append(version).append("\",\n");
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

    private static Path createMockJar(Path dir, String fileName, String modId, String version, String mainClass) throws IOException {
        Path jarPath = dir.resolve(fileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new ZipEntry("fabric.mod.json"));
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"schemaVersion\": 1,\n");
            sb.append("  \"id\": \"").append(modId).append("\",\n");
            sb.append("  \"version\": \"").append(version).append("\",\n");
            sb.append("  \"name\": \"").append(modId).append("\"");
            if (mainClass != null) {
                sb.append(",\n  \"entrypoints\": {\n");
                sb.append("    \"main\": [\"").append(mainClass).append("\"]\n");
                sb.append("  }\n");
            } else {
                sb.append("\n");
            }
            sb.append("}");

            jos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jarPath;
    }
}
