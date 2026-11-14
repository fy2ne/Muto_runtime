package me.fy2ne.muto.engine.di;

import me.fy2ne.muto.MutoLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DiContainerGuard {
    private static final List<DiGuardHook> HOOKS = new CopyOnWriteArrayList<>();

    static {
        HOOKS.add(new DistantHorizonsGuard());
        HOOKS.add(new EssentialGuard());
        HOOKS.add(new GenericReflectionGuard());
    }

    private DiContainerGuard() {}

    public static void registerHook(DiGuardHook hook) {
        if (hook != null && !HOOKS.contains(hook)) {
            HOOKS.add(hook);
        }
    }

    public static void teardown(String modId) {
        if (modId == null) return;
        for (DiGuardHook hook : HOOKS) {
            try {
                if (hook.matches(modId)) {
                    hook.teardown(modId);
                }
            } catch (Throwable t) {
                MutoLog.warn("di guard teardown error for {} in {}: {}", modId, hook.getClass().getSimpleName(), t.getMessage());
            }
        }
    }

    public static void prepare(String modId) {
        if (modId == null) return;
        for (DiGuardHook hook : HOOKS) {
            try {
                if (hook.matches(modId)) {
                    hook.prepare(modId);
                }
            } catch (Throwable t) {
                MutoLog.warn("di guard prepare error for {} in {}: {}", modId, hook.getClass().getSimpleName(), t.getMessage());
            }
        }
    }

    public interface DiGuardHook {
        boolean matches(String modId);
        void teardown(String modId);
        default void prepare(String modId) {}
    }

    private static final class DistantHorizonsGuard implements DiGuardHook {
        private static final Set<String> IDS = Set.of("distanthorizons", "distant_horizons");
        private static final String INJECTOR_CLASS = "com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector";

        @Override
        public boolean matches(String modId) {
            return IDS.contains(modId.toLowerCase(Locale.ROOT));
        }

        @Override
        public void prepare(String modId) {
            enableDuplicateBindings();
        }

        @Override
        public void teardown(String modId) {
            enableDuplicateBindings();
            clearBindings();
        }

        private void enableDuplicateBindings() {
            Object injector = getSingletonInjector();
            if (injector == null) return;

            Field allowDup = findField(injector.getClass(), "allowDuplicateBindings");
            if (allowDup != null) {
                setBooleanField(allowDup, injector, true);
                MutoLog.info("enabled duplicate bindings on distant horizons di container");
            }
        }

        private void clearBindings() {
            Object injector = getSingletonInjector();
            if (injector == null) return;

            try {
                Method clear = findMethod(injector.getClass(), "clear");
                if (clear != null) {
                    clear.setAccessible(true);
                    clear.invoke(injector);
                    MutoLog.info("cleared distant horizons di bindings");
                }
            } catch (Throwable t) {
                MutoLog.warn("failed calling clear on distant horizons injector: {}", t.getMessage());
            }

            try {
                Field deps = findField(injector.getClass(), "dependencies");
                if (deps != null) {
                    deps.setAccessible(true);
                    Object map = deps.get(injector);
                    if (map instanceof Map<?, ?> m) {
                        m.clear();
                    }
                }
            } catch (Throwable ignored) {}
        }

        private Object getSingletonInjector() {
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                Class<?> cls = Class.forName(INJECTOR_CLASS, false, cl);
                Field inst = cls.getDeclaredField("INSTANCE");
                inst.setAccessible(true);
                return inst.get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static final class EssentialGuard implements DiGuardHook {
        private static final Set<String> IDS = Set.of("essential");

        @Override
        public boolean matches(String modId) {
            return IDS.contains(modId.toLowerCase(Locale.ROOT));
        }

        @Override
        public void prepare(String modId) {
            resetFlags();
        }

        @Override
        public void teardown(String modId) {
            resetFlags();
        }

        private void resetFlags() {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            resetField(cl, "gg.essential.Essential", "initialized", false);
            resetField(cl, "gg.essential.Essential", "getInstanceIsLocked", false);
            resetField(cl, "gg.essential.DI", "hasStarted", false);
            resetField(cl, "gg.essential.api.utils.DiKt", "initialised", false);
            MutoLog.info("reset essential static di flags");
        }

        private void resetField(ClassLoader cl, String className, String fieldName, boolean value) {
            try {
                Class<?> cls = Class.forName(className, false, cl);
                Field f = findField(cls, fieldName);
                if (f != null && Modifier.isStatic(f.getModifiers())) {
                    setBooleanField(f, null, value);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static final class GenericReflectionGuard implements DiGuardHook {
        @Override
        public boolean matches(String modId) {
            return modId != null && !modId.isEmpty();
        }

        @Override
        public void teardown(String modId) {
            // Safe cleanup for registered custom singletons or container beans if needed
        }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try {
                Method m = cur.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static void setBooleanField(Field field, Object instance, boolean val) {
        try {
            field.setAccessible(true);
            field.setBoolean(instance, val);
            return;
        } catch (Throwable ignored) {}

        sun.misc.Unsafe u = getUnsafe();
        if (u != null) {
            try {
                if (Modifier.isStatic(field.getModifiers())) {
                    Object base = u.staticFieldBase(field);
                    long offset = u.staticFieldOffset(field);
                    u.putBoolean(base, offset, val);
                } else if (instance != null) {
                    long offset = u.objectFieldOffset(field);
                    u.putBoolean(instance, offset, val);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static sun.misc.Unsafe getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
