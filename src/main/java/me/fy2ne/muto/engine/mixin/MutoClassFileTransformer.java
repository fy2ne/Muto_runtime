package me.fy2ne.muto.engine.mixin;

import me.fy2ne.muto.MutoLog;
import me.fy2ne.muto.engine.knot.KnotInjector;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MutoClassFileTransformer implements ClassFileTransformer {
    public static final Map<String, byte[]> PRISTINE_CACHE = new ConcurrentHashMap<>();

    private final Set<String> targetClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> unweavingTargets = ConcurrentHashMap.newKeySet();
    private Method transformMethod;

    public void addTargets(Set<String> targets) {
        if (targets != null) {
            targetClasses.addAll(targets);
            for (String t : targets) {
                cachePristineIfAbsent(t, null);
            }
        }
    }

    public void removeTargets(Set<String> targets) {
        if (targets != null) {
            targetClasses.removeAll(targets);
        }
    }

    public void addUnweavingTargets(Set<String> targets) {
        if (targets != null) {
            unweavingTargets.addAll(targets);
            targetClasses.addAll(targets);
        }
    }

    public void removeUnweavingTargets(Set<String> targets) {
        if (targets != null) {
            unweavingTargets.removeAll(targets);
            targetClasses.removeAll(targets);
        }
    }

    public boolean isUnweaving(String dotted) {
        return unweavingTargets.contains(dotted);
    }

    public boolean hasTargets() {
        return !targetClasses.isEmpty();
    }

    public static byte[] cachePristineIfAbsent(String dotted, ClassLoader loader) {
        if (dotted == null || dotted.isEmpty()) return null;
        byte[] cached = PRISTINE_CACHE.get(dotted);
        if (cached != null) return cached;

        byte[] pre = resolvePreMixinBytes(dotted, loader);
        if (pre != null && pre.length > 0) {
            PRISTINE_CACHE.put(dotted, pre);
            return pre;
        }
        return null;
    }

    public static byte[] resolvePreMixinBytes(String dotted, ClassLoader loader) {
        byte[] bytes = null;
        try {
            Object delegate = KnotInjector.getDelegate();
            if (delegate != null) {
                try {
                    Method getPreBytes = delegate.getClass().getMethod("getPreMixinClassBytes", String.class);
                    bytes = (byte[]) getPreBytes.invoke(delegate, dotted);
                } catch (NoSuchMethodException e) {
                    try {
                        Method getPreBytes = delegate.getClass().getDeclaredMethod("getPreMixinClassByteArray", String.class, boolean.class);
                        getPreBytes.setAccessible(true);
                        bytes = (byte[]) getPreBytes.invoke(delegate, dotted, false);
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        if (bytes == null || bytes.length == 0) {
            String resName = dotted.replace('.', '/') + ".class";
            ClassLoader cl = (loader != null) ? loader : Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = MutoClassFileTransformer.class.getClassLoader();
            }
            if (cl != null) {
                try (java.io.InputStream is = cl.getResourceAsStream(resName)) {
                    if (is != null) {
                        bytes = is.readAllBytes();
                    }
                } catch (Throwable ignored) {}
            }
        }
        return (bytes != null && bytes.length > 0) ? bytes : null;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) {
        if (className == null || classfileBuffer == null) return null;

        String dotted = className.replace('/', '.');
        if (!targetClasses.contains(dotted)) {
            return null;
        }

        byte[] pristineBytes = PRISTINE_CACHE.get(dotted);
        if (pristineBytes == null) {
            pristineBytes = resolvePreMixinBytes(dotted, loader);
            if (pristineBytes != null && pristineBytes.length > 0) {
                PRISTINE_CACHE.put(dotted, pristineBytes);
            }
        }

        if (!DynamicMixinEngine.hasActiveConfigsFor(dotted)) {
            return (pristineBytes != null) ? pristineBytes : classfileBuffer;
        }

        Object mixinTransformer = KnotInjector.getMixinTransformer();
        if (mixinTransformer == null) {
            return (pristineBytes != null) ? pristineBytes : classfileBuffer;
        }

        byte[] inputBytes = (pristineBytes != null) ? pristineBytes : classfileBuffer;

        try {
            if (transformMethod == null) {
                try {
                    Class<?> imtClass = Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer");
                    Class<?> envClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
                    transformMethod = imtClass.getMethod("transformClass", envClass, String.class, byte[].class);
                    transformMethod.setAccessible(true);
                } catch (Throwable ignored) {
                    for (Method m : mixinTransformer.getClass().getMethods()) {
                        if (m.getName().equals("transformClass") && m.getParameterCount() == 3) {
                            transformMethod = m;
                            transformMethod.setAccessible(true);
                            break;
                        }
                    }
                }
            }

            if (transformMethod != null) {
                Class<?> envClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
                Object curEnv = null;
                try {
                    Method getCurEnv = envClass.getMethod("getCurrentEnvironment");
                    curEnv = getCurEnv.invoke(null);
                } catch (Throwable ignored) {}
                if (curEnv == null) {
                    try {
                        Method getDefEnv = envClass.getMethod("getDefaultEnvironment");
                        curEnv = getDefEnv.invoke(null);
                    } catch (Throwable ignored) {}
                }

                byte[] out = (byte[]) transformMethod.invoke(mixinTransformer, curEnv, dotted, inputBytes);
                if (out != null && out.length > 0) {
                    MutoLog.info("live mixin woven into {}", dotted);
                    return out;
                }
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            MutoLog.warn("error transforming class {}: {}", dotted, cause.getMessage());
            return (pristineBytes != null) ? pristineBytes : classfileBuffer;
        }

        return (pristineBytes != null) ? pristineBytes : classfileBuffer;
    }

}
