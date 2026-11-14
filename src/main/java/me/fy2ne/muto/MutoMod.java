package me.fy2ne.muto;

import me.fy2ne.muto.engine.ReloadEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MutoMod implements ClientModInitializer, PreLaunchEntrypoint {
    private static boolean dcevmDetected;
    private static boolean jbrDetected;
    private static final AtomicBoolean bootReconciled = new AtomicBoolean(false);

    @Override
    public void onPreLaunch() {
        try {
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            me.fy2ne.muto.mods.ModStateManager.INSTANCE.reconcileFileRenames(modsDir);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onInitializeClient() {
        MutoLog.info("Muto Engine initializing (client runtime)");
        detectRuntimeCapabilities();
        try {
            me.fy2ne.muto.engine.agent.MutoAgentBootstrap.getInstrumentation();
        } catch (Throwable t) {
            MutoLog.warn("early agent attach notice: {}", t.getMessage());
        }
        Set<String> disabledIds = java.util.Collections.emptySet();
        try {
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            disabledIds = me.fy2ne.muto.mods.ModStateManager.INSTANCE.reconcileFileRenames(modsDir);
            ReloadEngine.INSTANCE.recordInitialSnapshot(modsDir);
        } catch (Throwable t) {
            MutoLog.warn("baseline snapshot deferred: {}", t.getMessage());
        }

        final Set<String> toPurgeAtBoot = disabledIds;
        try {
            final net.minecraft.client.gui.screens.Screen[] lastScreen = new net.minecraft.client.gui.screens.Screen[1];
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
                net.minecraft.client.gui.screens.Screen prev = lastScreen[0];
                lastScreen[0] = screen;
                me.fy2ne.muto.dev.MutoDev.onScreenChange(prev, screen, "ScreenEvents.BEFORE_INIT");

                if (bootReconciled.compareAndSet(false, true) && !toPurgeAtBoot.isEmpty()) {
                    boolean anyStillLoaded = false;
                    for (String id : toPurgeAtBoot) {
                        if (FabricLoader.getInstance().isModLoaded(id)) {
                            anyStillLoaded = true;
                            break;
                        }
                    }
                    if (anyStillLoaded) {
                        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
                        ReloadEngine.INSTANCE.reloadAsync(modsDir, msg -> {});
                    }
                }
            });
        } catch (Throwable t) {
            MutoLog.warn("screen tracker hook skipped: {}", t.getMessage());
        }
    }

    private void detectRuntimeCapabilities() {
        String vmName = System.getProperty("java.vm.name", "");
        String vmVendor = System.getProperty("java.vm.vendor", "");
        jbrDetected = vmName.toLowerCase().contains("jetbrains") || vmVendor.toLowerCase().contains("jetbrains");

        List<String> vmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        dcevmDetected = vmArgs.stream().anyMatch(arg -> arg.contains("AllowEnhancedClassRedefinition"))
                || vmName.toLowerCase().contains("dcevm");

        MutoLog.info("runtime check: jbr={}, dcevm={}", jbrDetected, dcevmDetected);
    }

    public static boolean hasDcevm() {
        return dcevmDetected;
    }

    public static boolean isJbr() {
        return jbrDetected;
    }
}
