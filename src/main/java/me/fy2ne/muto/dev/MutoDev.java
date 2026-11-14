package me.fy2ne.muto.dev;

import me.fy2ne.muto.MutoLog;
import me.fy2ne.muto.engine.ModSnapshot;
import me.fy2ne.muto.engine.ScannedMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MutoDev {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int MAX_BUFFER = 120;
    private static final Deque<String> LOG_BUFFER = new ConcurrentLinkedDeque<>();
    private static final Deque<ScreenTransition> SCREEN_BUFFER = new ConcurrentLinkedDeque<>();
    private static final AtomicBoolean DEV_MODE = new AtomicBoolean(true);

    private static Path logPath;
    private static volatile long lastReloadTime = 0L;

    private MutoDev() {}

    public static boolean isDevMode() {
        return DEV_MODE.get();
    }

    public static void setDevMode(boolean active) {
        DEV_MODE.set(active);
        log("DEV_MODE", "dev mode toggled: " + active);
    }

    public static void notifyReloadStart() {
        lastReloadTime = System.currentTimeMillis();
    }

    private static synchronized Path resolveLogPath() {
        if (logPath != null) return logPath;
        try {
            var fl = FabricLoader.getInstance();
            if (fl != null && fl.getGameDir() != null) {
                Path dir = fl.getGameDir().resolve("logs");
                if (!Files.exists(dir)) Files.createDirectories(dir);
                logPath = dir.resolve("muto-dev.log");
            }
        } catch (Throwable ignored) {}
        return logPath;
    }

    public static void log(String tag, String msg) {
        if (!DEV_MODE.get()) return;

        String ts = LocalDateTime.now().format(TIME_FMT);
        String formatted = String.format("[%s] [%-12s] %s", ts, tag, msg);

        LOG_BUFFER.addLast(formatted);
        while (LOG_BUFFER.size() > MAX_BUFFER) {
            LOG_BUFFER.pollFirst();
        }

        MutoLog.info("[DEV:{}] {}", tag, msg);

        Path p = resolveLogPath();
        if (p == null) return;

        try (BufferedWriter bw = Files.newBufferedWriter(p, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            bw.write(formatted);
            bw.newLine();
        } catch (IOException ignored) {}
    }

    public static void onScreenChange(Screen from, Screen to, String callerContext) {
        if (!DEV_MODE.get()) return;

        String fromName = from != null ? from.getClass().getSimpleName() : "null";
        String toName = to != null ? to.getClass().getSimpleName() : "null";
        String caller = resolveCaller();

        ScreenTransition st = new ScreenTransition(System.currentTimeMillis(), fromName, toName, caller, callerContext);
        SCREEN_BUFFER.addLast(st);
        while (SCREEN_BUFFER.size() > MAX_BUFFER) {
            SCREEN_BUFFER.pollFirst();
        }

        log("SCREEN", String.format("%s -> %s via %s (caller: %s)", fromName, toName, callerContext, caller));

        long delta = System.currentTimeMillis() - lastReloadTime;
        if (delta < 12000 && (to == null || to instanceof TitleScreen)) {
            log("SCREEN-ALERT", String.format("player kicked to '%s' during/shortly after reload flow (delta=%dms)! Trigger: %s", toName, delta, caller));
        }
    }

    public static void onOverlayChange(Object overlay) {
        if (!DEV_MODE.get()) return;
        String name = overlay != null ? overlay.getClass().getSimpleName() : "null";
        log("OVERLAY", overlay != null ? "mounted: " + name : "unmounted (overlay cleared)");
    }

    public static void diagnoseNoOp(ModSnapshot baseline, ModSnapshot target, Path modsDir) {
        if (!DEV_MODE.get()) return;

        int baseCount = baseline != null ? baseline.mods().size() : 0;
        int tgtCount = target != null ? target.mods().size() : 0;

        log("NO-OP", String.format("reload had NO WORK. baseline mods=%d, target mods=%d", baseCount, tgtCount));

        // check locked files
        if (modsDir != null && Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path jar : ds) {
                    if (!canOpenWrite(jar)) {
                        log("LOCK-ALERT", String.format("jar locked by Windows OS/JVM: %s (cannot rename to .disabled)", jar.getFileName()));
                    }
                }
            } catch (Throwable t) {
                log("NO-OP", "failed checking jar locks: " + t.getMessage());
            }
        }

        // check disabled files on disk
        if (modsDir != null && Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.disabled")) {
                List<String> disNames = new ArrayList<>();
                for (Path dis : ds) disNames.add(dis.getFileName().toString());
                log("NO-OP", String.format("disk contains %d disabled file(s): %s", disNames.size(), disNames));
            } catch (Throwable ignored) {}
        }
    }

    public static boolean canOpenWrite(Path p) {
        if (!Files.isRegularFile(p)) return true;
        try (var ch = Files.newByteChannel(p, StandardOpenOption.WRITE)) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<String> getRecentLogs() {
        return new ArrayList<>(LOG_BUFFER);
    }

    public static List<ScreenTransition> getScreenHistory() {
        return new ArrayList<>(SCREEN_BUFFER);
    }

    public static void clearLogs() {
        LOG_BUFFER.clear();
        SCREEN_BUFFER.clear();
        Path p = resolveLogPath();
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (Throwable ignored) {}
        }
    }

    private static String resolveCaller() {
        StackTraceElement[] traces = Thread.currentThread().getStackTrace();
        for (int i = 3; i < traces.length; i++) {
            String cls = traces[i].getClassName();
            if (cls.contains("MutoDev") || cls.contains("ScreenTrackerMixin") || cls.startsWith("org.spongepowered")) {
                continue;
            }
            return traces[i].getClassName() + "." + traces[i].getMethodName() + ":" + traces[i].getLineNumber();
        }
        return "unknown";
    }

    public record ScreenTransition(long timestamp, String from, String to, String caller, String context) {
        @Override
        public String toString() {
            return String.format("[%s] %s -> %s (caller: %s)", context, from, to, caller);
        }
    }
}
