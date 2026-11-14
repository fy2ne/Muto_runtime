package me.fy2ne.muto.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.fy2ne.muto.api.ModDiff;
import me.fy2ne.muto.engine.ModScanner;
import me.fy2ne.muto.engine.ModSnapshot;
import me.fy2ne.muto.engine.ReloadEngine;
import me.fy2ne.muto.engine.ScannedMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class MutoDevSimulation {
    private MutoDevSimulation() {}

    public static String runReloadSimulation(Path modsDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MUTO RELOAD DRY-RUN SIMULATION ===\n");

        if (modsDir == null || !Files.isDirectory(modsDir)) {
            String err = "Mods directory not found: " + modsDir;
            MutoDev.log("SIMULATION", err);
            return err;
        }

        // 1. Inspect disk files
        Map<String, Path> activeJars = new HashMap<>();
        Map<String, Path> disabledJars = new HashMap<>();
        List<String> lockedFiles = new ArrayList<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String fn = p.getFileName().toString();
                String modId = readModId(p);
                boolean locked = !MutoDev.canOpenWrite(p);
                if (locked) lockedFiles.add(fn);

                if (fn.endsWith(".jar")) {
                    activeJars.put(modId != null ? modId : fn, p);
                } else if (fn.endsWith(".disabled") || fn.endsWith(".deactivation")) {
                    disabledJars.put(modId != null ? modId : fn, p);
                }
            }
        } catch (Throwable t) {
            sb.append("Error reading disk files: ").append(t.getMessage()).append("\n");
        }

        sb.append(String.format("Disk Jars: %d active, %d disabled\n", activeJars.size(), disabledJars.size()));
        for (var e : disabledJars.entrySet()) {
            sb.append(String.format("  * Disabled file: %s -> Mod ID: [%s]\n", e.getValue().getFileName(), e.getKey()));
        }

        if (!lockedFiles.isEmpty()) {
            sb.append(String.format("WARNING: %d locked jar(s) on Windows:\n", lockedFiles.size()));
            for (String l : lockedFiles) {
                sb.append(String.format("  ! %s (file locked by process)\n", l));
            }
        }

        // 2. Disabled Files Analysis
        sb.append(String.format("Disabled Jars on Disk: %d\n", disabledJars.size()));
        for (var e : disabledJars.entrySet()) {
            sb.append(String.format("  * %s (id: %s)\n", e.getValue().getFileName(), e.getKey()));
        }

        // 3. Simulated Snapshot Diff
        ModSnapshot current = ReloadEngine.INSTANCE.currentSnapshot();
        List<ScannedMod> targetScanned = ModScanner.scan(modsDir);
        ModSnapshot target = new ModSnapshot(targetScanned);
        ModDiff diff = current.diffAgainst(target);

        sb.append("\n--- SIMULATED DIFF ---\n");
        sb.append(String.format("Baseline Mods: %d | Target Mods: %d\n", current.mods().size(), target.mods().size()));
        sb.append(String.format("Added:    %s\n", diff.added()));
        sb.append(String.format("Removed:  %s\n", diff.removed()));
        sb.append(String.format("Updated:  %s\n", diff.updated()));
        sb.append(String.format("Unchanged: %d mod(s)\n", diff.unchanged().size()));

        if (!diff.hasChanges()) {
            sb.append("\nDIAGNOSIS: RELOAD WOULD BE A NO-OP!\n");
            sb.append("Reason: Current snapshot matches target snapshot.\n");
        } else {
            sb.append("\nDIAGNOSIS: RELOAD WOULD EXECUTE\n");
            sb.append(String.format("Would affect %d mod context(s).\n", diff.added().size() + diff.removed().size() + diff.updated().size()));
        }

        String report = sb.toString();
        MutoDev.log("SIMULATION", "simulation executed:\n" + report);
        return report;
    }

    private static String readModId(Path path) {
        try (JarFile jf = new JarFile(path.toFile())) {
            JarEntry entry = jf.getJarEntry("fabric.mod.json");
            if (entry == null) return null;
            try (InputStream in = jf.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("id")) {
                    return root.get("id").getAsString();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
