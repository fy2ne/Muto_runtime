package me.fy2ne.muto.engine;

import me.fy2ne.muto.engine.knot.KnotInjector;
import me.fy2ne.muto.engine.mixin.DynamicMixinEngine;
import me.fy2ne.muto.engine.mixin.MutoClassFileTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChallengerM2StressTest {

    private Instrumentation originalInst;
    private Object originalDelegate;

    @BeforeEach
    void setUp() throws Exception {
        MutoClassFileTransformer.PRISTINE_CACHE.clear();
        clearKnownModTargets();
        clearModIdToConfigs();

        Field instField = MutoAgent.class.getDeclaredField("instrumentation");
        instField.setAccessible(true);
        originalInst = (Instrumentation) instField.get(null);

        Field delegateField = KnotInjector.class.getDeclaredField("cachedDelegate");
        delegateField.setAccessible(true);
        originalDelegate = delegateField.get(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        MutoClassFileTransformer.PRISTINE_CACHE.clear();
        clearKnownModTargets();
        clearModIdToConfigs();

        Field instField = MutoAgent.class.getDeclaredField("instrumentation");
        instField.setAccessible(true);
        instField.set(null, originalInst);

        Field delegateField = KnotInjector.class.getDeclaredField("cachedDelegate");
        delegateField.setAccessible(true);
        delegateField.set(null, originalDelegate);

        Field tfInstalledField = DynamicMixinEngine.class.getDeclaredField("transformerInstalled");
        tfInstalledField.setAccessible(true);
        tfInstalledField.setBoolean(null, false);
    }

    @SuppressWarnings("unchecked")
    private void clearKnownModTargets() throws Exception {
        Field kmf = DynamicMixinEngine.class.getDeclaredField("KNOWN_MOD_TARGETS");
        kmf.setAccessible(true);
        ((Map<String, Set<String>>) kmf.get(null)).clear();
    }

    @SuppressWarnings("unchecked")
    private void clearModIdToConfigs() throws Exception {
        Field mcf = DynamicMixinEngine.class.getDeclaredField("modIdToConfigs");
        mcf.setAccessible(true);
        ((Map<String, Set<String>>) mcf.get(null)).clear();
    }

    @SuppressWarnings("unchecked")
    private void addKnownModTarget(String modId, String targetClass) throws Exception {
        Field kmf = DynamicMixinEngine.class.getDeclaredField("KNOWN_MOD_TARGETS");
        kmf.setAccessible(true);
        ((Map<String, Set<String>>) kmf.get(null))
                .computeIfAbsent(modId, k -> ConcurrentHashMap.newKeySet())
                .add(targetClass);
    }

    // =========================================================================
    // 1. Empirical Stress: MutoClassFileTransformer Unweaving vs Active Configs
    // =========================================================================

    @Test
    void testTransformerUnweavingWhenAllConfigsRemovedReturnsPristineBytes() {
        MutoClassFileTransformer transformer = new MutoClassFileTransformer();
        String targetName = "com.muto.test.CleanUnweaveTarget";
        byte[] pristineBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 1, 2, 3, 4};
        byte[] dirtyBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 9, 9, 9, 9};

        // Cache pristine bytes
        MutoClassFileTransformer.PRISTINE_CACHE.put(targetName, pristineBytes);

        // Ensure hasActiveConfigsFor returns false
        Assertions.assertFalse(DynamicMixinEngine.hasActiveConfigsFor(targetName));

        // Mark as unweaving target
        transformer.addUnweavingTargets(Set.of(targetName));
        Assertions.assertTrue(transformer.isUnweaving(targetName));

        // Transform must immediately return pristine bytes, rejecting dirty buffer
        byte[] out = transformer.transform(null, targetName.replace('.', '/'), null, null, dirtyBytes);
        Assertions.assertNotNull(out, "transformer must return non-null for unweaving target");
        Assertions.assertArrayEquals(pristineBytes, out, "must return exact pristine bytes when all configs are removed");

        // Clean up
        transformer.removeUnweavingTargets(Set.of(targetName));
        Assertions.assertFalse(transformer.isUnweaving(targetName));
    }

    @Test
    void testTransformerUnweavingWhenTargetClassesOnlyAndNoActiveConfigsReturnsPristineBytes() {
        MutoClassFileTransformer transformer = new MutoClassFileTransformer();
        String targetName = "com.muto.test.TargetClassNoConfigs";
        byte[] pristineBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 5, 6, 7, 8};
        byte[] dirtyBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 88, 88};

        MutoClassFileTransformer.PRISTINE_CACHE.put(targetName, pristineBytes);
        Assertions.assertFalse(DynamicMixinEngine.hasActiveConfigsFor(targetName));

        // Add to targetClasses (e.g. from general retransformation after mod unweave)
        transformer.addTargets(Set.of(targetName));

        byte[] out = transformer.transform(null, targetName.replace('.', '/'), null, null, dirtyBytes);
        Assertions.assertNotNull(out);
        Assertions.assertArrayEquals(pristineBytes, out, "classes without active configs must restore pristine bytes");

        transformer.removeTargets(Set.of(targetName));
    }

    @Test
    void testTransformerUnweavingWhenActiveConfigsRemainFunnelsPristineToMixinTransformer() throws Exception {
        MutoClassFileTransformer transformer = new MutoClassFileTransformer();
        String targetName = "com.muto.test.SharedTargetClass";
        byte[] pristineBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 11, 22, 33};
        byte[] dirtyBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 99, 99, 99};
        byte[] wovenBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 44, 55, 66};

        MutoClassFileTransformer.PRISTINE_CACHE.put(targetName, pristineBytes);

        // Simulate another mod having active configs targeting this class
        addKnownModTarget("other_mod", targetName);
        Assertions.assertTrue(DynamicMixinEngine.hasActiveConfigsFor(targetName));

        // Setup mock IMixinTransformer via KnotInjector cachedDelegate
        AtomicBoolean mixinInvoked = new AtomicBoolean(false);
        AtomicBoolean receivedPristineInput = new AtomicBoolean(false);

        Object mockMixinTransformer = Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer")},
                (proxy, method, args) -> {
                    if ("transformClass".equals(method.getName()) && args.length == 3) {
                        mixinInvoked.set(true);
                        byte[] inputBytes = (byte[]) args[2];
                        if (Arrays.equals(pristineBytes, inputBytes)) {
                            receivedPristineInput.set(true);
                        }
                        return wovenBytes;
                    }
                    return null;
                }
        );

        // Mock delegate that provides mixinTransformer
        // Initialize FabricLauncherBase properties if null to avoid NPE during MixinEnvironment lookup
        try {
            Class<?> flb = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
            Field propField = flb.getDeclaredField("properties");
            propField.setAccessible(true);
            if (propField.get(null) == null) {
                propField.set(null, new HashMap<String, Object>());
            }
        } catch (Throwable ignored) {}

        // Initialize MixinBootstrap if not yet initialized
        try {
            Class<?> mb = Class.forName("org.spongepowered.asm.launch.MixinBootstrap");
            Method initMethod = mb.getMethod("init");
            initMethod.invoke(null);
        } catch (Throwable ignored) {}

        ConcreteMockKnotDelegate mockDelegate = new ConcreteMockKnotDelegate();
        mockDelegate.mixinTransformer = mockMixinTransformer;

        Field delegateField = KnotInjector.class.getDeclaredField("cachedDelegate");
        delegateField.setAccessible(true);
        delegateField.set(null, mockDelegate);

        // Reset getMixinTransformerMethod in KnotInjector to ensure it reflects on the new delegate
        Field mtmField = KnotInjector.class.getDeclaredField("getMixinTransformerMethod");
        mtmField.setAccessible(true);
        mtmField.set(null, null);

        // Add to unweaving targets (unweaving mod A, but mod B still targets it)
        transformer.addUnweavingTargets(Set.of(targetName));

        byte[] out = transformer.transform(null, targetName.replace('.', '/'), null, null, dirtyBytes);

        Assertions.assertTrue(mixinInvoked.get(), "MixinTransformer must be invoked when active configs remain");
        Assertions.assertTrue(receivedPristineInput.get(), "MixinTransformer must receive PRISTINE bytes as input, not dirty buffer");
        Assertions.assertArrayEquals(wovenBytes, out, "must return re-woven bytes from remaining active configs");
    }

    @Test
    void testTransformerFallbackToPristineWhenMixinTransformerThrows() throws Exception {
        MutoClassFileTransformer transformer = new MutoClassFileTransformer();
        String targetName = "com.muto.test.ExplodingMixinTarget";
        byte[] pristineBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 12, 34};
        byte[] dirtyBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 77, 77};

        MutoClassFileTransformer.PRISTINE_CACHE.put(targetName, pristineBytes);
        addKnownModTarget("faulty_mod", targetName);

        Object explodingMixinTransformer = Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Class.forName("org.spongepowered.asm.mixin.transformer.IMixinTransformer")},
                (proxy, method, args) -> {
                    if ("transformClass".equals(method.getName())) {
                        throw new RuntimeException("SpongePowered Mixin catastrophic transformation error");
                    }
                    return null;
                }
        );

        ConcreteMockKnotDelegate mockDelegate = new ConcreteMockKnotDelegate();
        mockDelegate.mixinTransformer = explodingMixinTransformer;

        Field delegateField = KnotInjector.class.getDeclaredField("cachedDelegate");
        delegateField.setAccessible(true);
        delegateField.set(null, mockDelegate);

        Field mtmField = KnotInjector.class.getDeclaredField("getMixinTransformerMethod");
        mtmField.setAccessible(true);
        mtmField.set(null, null);

        transformer.addTargets(Set.of(targetName));

        // When Mixin explodes, transformer must catch, log, and safely fall back to pristineBytes
        byte[] out = transformer.transform(null, targetName.replace('.', '/'), null, null, dirtyBytes);
        Assertions.assertNotNull(out);
        Assertions.assertArrayEquals(pristineBytes, out, "transformer must fall back to pristine bytes on Mixin exception");
    }

    @Test
    void testResolvePreMixinBytesFromKnotDelegate() throws Exception {
        String testClassName = "com.muto.test.KnotDelegateTarget";
        byte[] knotBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 100, 101, 102};

        Object mockDelegate = Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{MockKnotDelegate.class},
                (proxy, method, args) -> {
                    if ("getPreMixinClassBytes".equals(method.getName()) && args.length == 1) {
                        if (testClassName.equals(args[0])) {
                            return knotBytes;
                        }
                    }
                    return null;
                }
        );

        Field delegateField = KnotInjector.class.getDeclaredField("cachedDelegate");
        delegateField.setAccessible(true);
        delegateField.set(null, mockDelegate);

        byte[] resolved = MutoClassFileTransformer.resolvePreMixinBytes(testClassName, null);
        Assertions.assertNotNull(resolved);
        Assertions.assertArrayEquals(knotBytes, resolved, "must resolve pre-mixin bytes via KnotClassDelegate.getPreMixinClassBytes");
    }

    // =========================================================================
    // 2. Empirical Stress: DynamicMixinEngine Isolated Per-Class Retransformation
    // =========================================================================

    public static class TestClassA {}
    public static class TestClassB {}
    public static class TestClassC {}
    public static class TestClassD {}

    @Test
    void testIsolatedRetransformationSurvivesLinkageAndUnsupportedOperationExceptions() throws Exception {
        Class<?> classA = TestClassA.class;
        Class<?> classB = TestClassB.class;
        Class<?> classC = TestClassC.class;
        Class<?> classD = TestClassD.class;

        Set<String> targetNames = Set.of(
                classA.getName(),
                classB.getName(),
                classC.getName(),
                classD.getName()
        );

        List<String> retransformedClasses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger totalRetransformAttempts = new AtomicInteger(0);

        Instrumentation mockInst = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("isRetransformClassesSupported".equals(mName)) {
                        return true;
                    }
                    if ("addTransformer".equals(mName)) {
                        return null;
                    }
                    if ("getAllLoadedClasses".equals(mName)) {
                        return new Class<?>[]{classA, classB, classC, classD};
                    }
                    if ("isModifiableClass".equals(mName)) {
                        return true;
                    }
                    if ("retransformClasses".equals(mName)) {
                        totalRetransformAttempts.incrementAndGet();
                        Class<?> target = ((Class<?>[]) args[0])[0];

                        // Class B triggers HotSpot schema redefinition restriction
                        if (target == classB) {
                            throw new UnsupportedOperationException("class redefinition failed: attempted to delete a method");
                        }
                        // Class C triggers LinkageError
                        if (target == classC) {
                            throw new LinkageError("loader constraint violation: class TestClassC");
                        }

                        // Class A and Class D succeed
                        retransformedClasses.add(target.getName());
                        return null;
                    }
                    return null;
                }
        );

        // Inject mock Instrumentation into MutoAgent
        Field instField = MutoAgent.class.getDeclaredField("instrumentation");
        instField.setAccessible(true);
        instField.set(null, mockInst);

        // Execute isolated retransformation
        int successCount = DynamicMixinEngine.retransformTargets(targetNames, true);

        // VERIFICATIONS:
        // 1. All 4 classes must have been attempted individually
        Assertions.assertEquals(4, totalRetransformAttempts.get(), "all 4 loaded classes must be attempted in isolation");

        // 2. Class B (UnsupportedOperationException) and Class C (LinkageError) were isolated and caught
        Assertions.assertEquals(2, successCount, "exactly 2 classes must succeed despite 2 throwing exceptions");

        // 3. Class A and Class D were successfully retransformed
        Assertions.assertTrue(retransformedClasses.contains(classA.getName()));
        Assertions.assertTrue(retransformedClasses.contains(classD.getName()));
        Assertions.assertFalse(retransformedClasses.contains(classB.getName()));
        Assertions.assertFalse(retransformedClasses.contains(classC.getName()));
    }

    @Test
    void testRetransformationWhenRetransformUnsupportedReturnsZeroGracefully() throws Exception {
        Instrumentation unsupportedInst = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("isRetransformClassesSupported".equals(method.getName())) {
                        return false;
                    }
                    return null;
                }
        );

        Field instField = MutoAgent.class.getDeclaredField("instrumentation");
        instField.setAccessible(true);
        instField.set(null, unsupportedInst);

        int count = DynamicMixinEngine.retransformTargets(Set.of(TestClassA.class.getName()), false);
        Assertions.assertEquals(0, count, "unsupported retransformation must return 0 without throwing");
    }

    @Test
    void testRetransformationWhenInstrumentationNullReturnsZero() throws Exception {
        Field instField = MutoAgent.class.getDeclaredField("instrumentation");
        instField.setAccessible(true);
        instField.set(null, null);

        // Ensure attemptedAttach doesn't fail
        Field attachField = me.fy2ne.muto.engine.agent.MutoAgentBootstrap.class.getDeclaredField("attemptedAttach");
        attachField.setAccessible(true);
        attachField.setBoolean(null, true);

        int count = DynamicMixinEngine.retransformTargets(Set.of("com.muto.Unknown"), false);
        Assertions.assertEquals(0, count);
    }

    // Helper interface to mock KnotClassDelegate
    public interface MockKnotDelegate {
        Object getMixinTransformer();
        byte[] getPreMixinClassBytes(String className);
    }

    public static class ConcreteMockKnotDelegate implements MockKnotDelegate {
        public Object mixinTransformer;

        @Override
        public Object getMixinTransformer() {
            return mixinTransformer;
        }

        @Override
        public byte[] getPreMixinClassBytes(String className) {
            return null;
        }
    }
}
