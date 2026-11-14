package me.fy2ne.muto.engine.agent;

import me.fy2ne.muto.MutoLog;
import me.fy2ne.muto.engine.MutoAgent;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;

public final class MutoAgentBootstrap {
    private static volatile boolean attemptedAttach = false;

    private MutoAgentBootstrap() {}

    public static synchronized Instrumentation getInstrumentation() {
        if (MutoAgent.isAvailable()) {
            return MutoAgent.getInstrumentation();
        }

        if (attemptedAttach) return null;
        attemptedAttach = true;

        try {
            Path selfJar = FabricLoader.getInstance().getModContainer("muto")
                    .flatMap(c -> c.getOrigin().getPaths().stream().findFirst())
                    .orElse(null);

            if (selfJar == null || !java.nio.file.Files.isRegularFile(selfJar) || !selfJar.toString().endsWith(".jar")) {
                Path devLibs = FabricLoader.getInstance().getGameDir().resolve("../build/libs").normalize();
                if (java.nio.file.Files.isDirectory(devLibs)) {
                    try (var stream = java.nio.file.Files.list(devLibs)) {
                        selfJar = stream.filter(p -> p.toString().endsWith(".jar") && !p.toString().contains("-sources"))
                                .findFirst()
                                .orElse(null);
                    }
                }
                if (selfJar == null) {
                    Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
                    if (java.nio.file.Files.isDirectory(modsDir)) {
                        try (var stream = java.nio.file.Files.list(modsDir)) {
                            selfJar = stream.filter(p -> p.getFileName().toString().startsWith("muto-") && p.toString().endsWith(".jar"))
                                    .findFirst()
                                    .orElse(null);
                        }
                    }
                }
            }

            if (selfJar == null || !java.nio.file.Files.exists(selfJar)) {
                MutoLog.warn("cannot resolve muto mod jar path for dynamic agent attach");
                return null;
            }

            // Unlock HotSpot self-attach check via Unsafe before calling VirtualMachine.attach
            try {
                Class<?> hsvm = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
                java.lang.reflect.Field uField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                uField.setAccessible(true);
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uField.get(null);

                java.lang.reflect.Field f = hsvm.getDeclaredField("ALLOW_ATTACH_SELF");
                Object base = unsafe.staticFieldBase(f);
                long offset = unsafe.staticFieldOffset(f);
                unsafe.putBoolean(base, offset, true);
            } catch (Throwable ignored) {}

            String pid = String.valueOf(ProcessHandle.current().pid());
            MutoLog.info("attempting dynamic agent attach to pid {}", pid);

            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            var attachMethod = vmClass.getMethod("attach", String.class);
            Object vm = attachMethod.invoke(null, pid);

            var loadAgentMethod = vmClass.getMethod("loadAgent", String.class);
            loadAgentMethod.invoke(vm, selfJar.toAbsolutePath().toString());

            var detachMethod = vmClass.getMethod("detach");
            detachMethod.invoke(vm);

            MutoLog.info("dynamic agent attach call completed successfully");
        } catch (Throwable t) {
            Throwable cause = t;
            if (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null) {
                cause = ite.getCause();
            }
            MutoLog.warn("dynamic self-attach failed: {}", cause.toString());
            me.fy2ne.muto.dev.MutoDev.log("AGENT-FAIL", "dynamic self-attach failed: " + cause.toString());
        }

        return MutoAgent.getInstrumentation();
    }
}
