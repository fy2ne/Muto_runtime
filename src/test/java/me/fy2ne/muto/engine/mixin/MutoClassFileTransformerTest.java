package me.fy2ne.muto.engine.mixin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

public class MutoClassFileTransformerTest {
    private MutoClassFileTransformer transformer;

    @BeforeEach
    void setup() {
        transformer = new MutoClassFileTransformer();
        MutoClassFileTransformer.PRISTINE_CACHE.clear();
    }

    @AfterEach
    void tearDown() {
        MutoClassFileTransformer.PRISTINE_CACHE.clear();
    }

    @Test
    void testPreMixinResolutionFromClassLoader() {
        String selfClass = MutoClassFileTransformer.class.getName();
        byte[] pre = MutoClassFileTransformer.resolvePreMixinBytes(selfClass, getClass().getClassLoader());
        Assertions.assertNotNull(pre, "pre-mixin bytes should be resolvable from classloader");
        Assertions.assertTrue(pre.length > 4);
        // check java class magic: 0xCAFEBABE
        Assertions.assertEquals((byte) 0xCA, pre[0]);
        Assertions.assertEquals((byte) 0xFE, pre[1]);
        Assertions.assertEquals((byte) 0xBA, pre[2]);
        Assertions.assertEquals((byte) 0xBE, pre[3]);
    }

    @Test
    void testPristineCacheDirectStorageAndRetrieval() {
        String testName = "com.test.SampleTarget";
        byte[] mockBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 1, 2, 3};

        MutoClassFileTransformer.PRISTINE_CACHE.put(testName, mockBytes);
        byte[] cached = MutoClassFileTransformer.cachePristineIfAbsent(testName, null);

        Assertions.assertArrayEquals(mockBytes, cached);
        Assertions.assertTrue(MutoClassFileTransformer.PRISTINE_CACHE.containsKey(testName));
    }

    @Test
    void testAddTargetsPopulatesPristineCache() {
        String selfClass = MutoClassFileTransformer.class.getName();
        Assertions.assertFalse(MutoClassFileTransformer.PRISTINE_CACHE.containsKey(selfClass));

        transformer.addTargets(Set.of(selfClass));

        Assertions.assertTrue(transformer.hasTargets());
        Assertions.assertTrue(MutoClassFileTransformer.PRISTINE_CACHE.containsKey(selfClass));
        Assertions.assertNotNull(MutoClassFileTransformer.PRISTINE_CACHE.get(selfClass));
    }

    @Test
    void testTransformNonTargetReturnsNull() {
        byte[] dirtyBuffer = new byte[]{1, 2, 3};
        byte[] out = transformer.transform(null, "unrelated/Class", null, null, dirtyBuffer);
        Assertions.assertNull(out, "untracked class transform must return null to indicate no change to jvm");
    }

    @Test
    void testUnweavingRestoresPristineBytes() {
        String targetName = "com.test.UnweaveTarget";
        byte[] pristine = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 10, 20};
        byte[] dirty = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 99, 99};

        MutoClassFileTransformer.PRISTINE_CACHE.put(targetName, pristine);
        transformer.addUnweavingTargets(Set.of(targetName));

        Assertions.assertTrue(transformer.isUnweaving(targetName));

        byte[] out = transformer.transform(null, targetName.replace('.', '/'), null, null, dirty);
        Assertions.assertNotNull(out);
        Assertions.assertArrayEquals(pristine, out, "unweaving target must restore pristine bytes, not dirty buffer");

        transformer.removeUnweavingTargets(Set.of(targetName));
        Assertions.assertFalse(transformer.isUnweaving(targetName));
    }
}
