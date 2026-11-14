package me.fy2ne.muto.engine.di;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

class DiContainerGuardTest {

    @Test
    void testUnknownModLifecycleDoesNotThrow() {
        Assertions.assertDoesNotThrow(() -> {
            DiContainerGuard.prepare("some_random_mod");
            DiContainerGuard.teardown("some_random_mod");
            DiContainerGuard.teardown(null);
            DiContainerGuard.prepare(null);
        });
    }

    @Test
    void testKnownModsSafeWhenClassesNotPresent() {
        Assertions.assertDoesNotThrow(() -> {
            DiContainerGuard.prepare("distanthorizons");
            DiContainerGuard.teardown("distanthorizons");
            DiContainerGuard.prepare("distant_horizons");
            DiContainerGuard.teardown("distant_horizons");
            DiContainerGuard.prepare("essential");
            DiContainerGuard.teardown("essential");
        });
    }

    @Test
    void testCustomHookRegistrationAndExecution() {
        AtomicBoolean prepared = new AtomicBoolean(false);
        AtomicBoolean tornDown = new AtomicBoolean(false);

        DiContainerGuard.DiGuardHook hook = new DiContainerGuard.DiGuardHook() {
            @Override
            public boolean matches(String modId) {
                return "custom_mod".equals(modId);
            }

            @Override
            public void prepare(String modId) {
                prepared.set(true);
            }

            @Override
            public void teardown(String modId) {
                tornDown.set(true);
            }
        };

        DiContainerGuard.registerHook(hook);
        DiContainerGuard.prepare("custom_mod");
        DiContainerGuard.teardown("custom_mod");

        Assertions.assertTrue(prepared.get());
        Assertions.assertTrue(tornDown.get());
    }

    // Simulates com.seibel.distanthorizons.coreapi.DependencyInjection.DependencyInjector
    static class DummyDependencyInjector {
        protected final boolean allowDuplicateBindings;
        protected final HashMap<Class<?>, ArrayList<Object>> dependencies = new HashMap<>();

        DummyDependencyInjector(boolean allowDuplicateBindings) {
            this.allowDuplicateBindings = allowDuplicateBindings;
        }

        public void bind(Class<?> clazz, Object obj) {
            if (dependencies.containsKey(clazz) && !allowDuplicateBindings) {
                throw new IllegalStateException("Already bound");
            }
            dependencies.computeIfAbsent(clazz, k -> new ArrayList<>()).add(obj);
        }

        public void clear() {
            dependencies.clear();
        }
    }

    @Test
    void testDistantHorizonsBindingSimulation() throws Exception {
        DummyDependencyInjector injector = new DummyDependencyInjector(false);
        injector.bind(String.class, "first");

        // Without guard, second bind throws
        Assertions.assertThrows(IllegalStateException.class, () -> injector.bind(String.class, "second"));

        // Guard simulates setting allowDuplicateBindings = true and clearing
        Field allowDup = DummyDependencyInjector.class.getDeclaredField("allowDuplicateBindings");
        allowDup.setAccessible(true);
        try {
            allowDup.setBoolean(injector, true);
        } catch (Throwable ignored) {
            Field uField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uField.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) uField.get(null);
            u.putBoolean(injector, u.objectFieldOffset(allowDup), true);
        }

        injector.clear();
        Assertions.assertTrue(injector.dependencies.isEmpty());
        Assertions.assertDoesNotThrow(() -> injector.bind(String.class, "new_binding"));
    }

    // Simulates gg.essential.Essential static flags
    static class DummyEssential {
        static boolean initialized = true;
        static boolean getInstanceIsLocked = true;
    }

    @Test
    void testEssentialFlagResetSimulation() throws Exception {
        Field initField = DummyEssential.class.getDeclaredField("initialized");
        initField.setAccessible(true);
        initField.setBoolean(null, false);

        Field lockField = DummyEssential.class.getDeclaredField("getInstanceIsLocked");
        lockField.setAccessible(true);
        lockField.setBoolean(null, false);

        Assertions.assertFalse(DummyEssential.initialized);
        Assertions.assertFalse(DummyEssential.getInstanceIsLocked);
    }
}
