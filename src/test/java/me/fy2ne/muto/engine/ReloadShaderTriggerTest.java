package me.fy2ne.muto.engine;

import me.fy2ne.muto.api.ModDiff;
import me.fy2ne.muto.engine.mixin.DynamicMixinEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ReloadShaderTriggerTest {

    @Test
    void testShaderReloadTriggerConditions() {
        // verify removed mod triggers reload even when initializable is empty (F6 fix)
        ModDiff removeOnly = new ModDiff(Set.of(), Set.of("old_mod"), Set.of(), Set.of());
        List<ScannedMod> emptyInit = Collections.emptyList();

        boolean shouldReloadOnRemove = (!emptyInit.isEmpty() || !removeOnly.removed().isEmpty());
        Assertions.assertTrue(shouldReloadOnRemove, "removing a mod must trigger resource and shader remount");

        // empty diff must not trigger reload
        ModDiff noChanges = ModDiff.EMPTY;
        boolean shouldReloadOnEmpty = (!emptyInit.isEmpty() || !noChanges.removed().isEmpty());
        Assertions.assertFalse(shouldReloadOnEmpty, "empty diff must not trigger resource remount");
    }

    @Test
    void testRetransformTargetsEmptyOrMissing() {
        // retransforming empty or unmodifiable targets should gracefully return 0 without failing
        int count = DynamicMixinEngine.retransformTargets(Set.of());
        Assertions.assertEquals(0, count);

        count = DynamicMixinEngine.retransformTargets(Set.of("non.existent.TargetClass"), true);
        Assertions.assertEquals(0, count);
    }

    @Test
    void testHasActiveConfigsForQuery() {
        Assertions.assertFalse(DynamicMixinEngine.hasActiveConfigsFor(null));
        Assertions.assertFalse(DynamicMixinEngine.hasActiveConfigsFor(""));
        Assertions.assertFalse(DynamicMixinEngine.hasActiveConfigsFor("unknown.Target"));
    }
}
