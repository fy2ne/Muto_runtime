package me.fy2ne.muto.api;

import me.fy2ne.muto.boundary.ReloadBoundaryManager;
import me.fy2ne.muto.engine.ReloadEngine;
import me.fy2ne.muto.engine.ReloadHistoryEntry;
import me.fy2ne.muto.engine.ReloadPlan;
import me.fy2ne.muto.engine.ReloadResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class MutoApi {
    private MutoApi() {}

    public static boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("muto");
    }

    public static boolean isReloading() {
        return ReloadEngine.INSTANCE.isReloading();
    }

    public static boolean isSafeToReload() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || isReloading()) return false;
            return mc.level == null && !mc.hasSingleplayerServer();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static ReloadPlan plan() {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        return ReloadEngine.INSTANCE.plan(modsDir);
    }

    public static CompletableFuture<ReloadResult> reloadAsync() {
        return reloadAsync(stage -> {});
    }

    public static CompletableFuture<ReloadResult> reloadAsync(Consumer<String> stageNotifier) {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        return ReloadEngine.INSTANCE.reloadAsync(modsDir, stageNotifier);
    }

    /**
     * Dynamically loads a mod JAR at runtime into the running JVM.
     * Injects the JAR into KnotClassLoader, registers mixins, synchronizes Fabric Loader metadata,
     * unfreezes registries, and executes main/client/server entrypoints.
     *
     * @param jarPath path to the mod jar file
     * @return CompletableFuture with the ReloadResult
     */
    public static CompletableFuture<ReloadResult> loadMod(Path jarPath) {
        return loadMod(jarPath, stage -> {});
    }

    public static CompletableFuture<ReloadResult> loadMod(Path jarPath, Consumer<String> stageNotifier) {
        return ReloadEngine.INSTANCE.loadModAsync(jarPath, stageNotifier);
    }

    /**
     * Dynamically unloads a mod at runtime.
     * Dispatches safe teardown, prunes registries, unregisters networking packets, cleans mod options/key mappings,
     * and synchronizes Fabric Loader.
     *
     * @param modId the mod id to unload
     * @return CompletableFuture with the ReloadResult
     */
    public static CompletableFuture<ReloadResult> unloadMod(String modId) {
        return unloadMod(modId, stage -> {});
    }

    public static CompletableFuture<ReloadResult> unloadMod(String modId, Consumer<String> stageNotifier) {
        return ReloadEngine.INSTANCE.unloadModAsync(modId, stageNotifier);
    }

    /**
     * Triggers a safe boundary reload. If the player is inside a singleplayer world
     * or connected to a multiplayer server, cleanly disconnects to a progress screen,
     * runs the reload pipeline, and automatically rejoins the world/server on completion.
     */
    public static void reloadWithBoundary(Screen returnScreen) {
        ReloadBoundaryManager.triggerReload(returnScreen);
    }

    public static List<ReloadHistoryEntry> getHistory() {
        return ReloadEngine.INSTANCE.history();
    }

    public static boolean isAutoReloadResources() {
        return ReloadEngine.INSTANCE.isAutoReloadResources();
    }

    public static void setAutoReloadResources(boolean enabled) {
        ReloadEngine.INSTANCE.setAutoReloadResources(enabled);
    }

    public static Set<String> getReloadableModIds() {
        try {
            Set<String> ids = new java.util.LinkedHashSet<>();
            for (var mod : ReloadEngine.INSTANCE.currentSnapshot().reloadableMods()) {
                ids.add(mod.id());
            }
            return Collections.unmodifiableSet(ids);
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    public static Set<String> getStubbornModIds() {
        try {
            Set<String> ids = new java.util.LinkedHashSet<>();
            for (var mod : ReloadEngine.INSTANCE.currentSnapshot().stubbornMods()) {
                ids.add(mod.id());
            }
            return Collections.unmodifiableSet(ids);
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    public static boolean isDevMode() {
        return me.fy2ne.muto.dev.MutoDev.isDevMode();
    }

    public static void setDevMode(boolean active) {
        me.fy2ne.muto.dev.MutoDev.setDevMode(active);
    }

    public static List<String> getDevLogs() {
        return me.fy2ne.muto.dev.MutoDev.getRecentLogs();
    }

    public static List<String> getScreenHistory() {
        List<String> list = new java.util.ArrayList<>();
        for (var st : me.fy2ne.muto.dev.MutoDev.getScreenHistory()) {
            list.add(st.toString());
        }
        return list;
    }

    public static void clearDevLogs() {
        me.fy2ne.muto.dev.MutoDev.clearLogs();
    }

    public static String runReloadSimulation() {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        return me.fy2ne.muto.dev.MutoDevSimulation.runReloadSimulation(modsDir);
    }

    public static List<String> prunePhantomDisabledMods() {
        return Collections.emptyList();
    }
}
