package com.moakiee.thunderbolt.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptionalMixinSelectorTest {
    @Test
    void craftingTreeTailAdaptationOnlyRunsWithTheAddon() {
        assertFalse(OptionalMixinSelector.shouldApply("Ae2CraftingTreeCompatibilityMixin", ignored -> false));
        assertTrue(OptionalMixinSelector.shouldApply("Ae2CraftingTreeCompatibilityMixin", "ae2ct"::equals));
    }

    @Test
    void skipsAdvancedAeTargetsWhenAddonIsMissing() {
        assertFalse(OptionalMixinSelector.shouldApply(
                "com.moakiee.thunderbolt.mixin.compat.advancedae.AdvCraftingCpuLogicBatchMixin",
                ignored -> false));
        assertFalse(OptionalMixinSelector.shouldApply("AaeTaskProgressAccessor", ignored -> false));
    }

    @Test
    void skipsAppliedETargetWhenAddonIsMissing() {
        assertFalse(OptionalMixinSelector.shouldApply(
                "AppliedETransmutationModuleBatchMixin",
                ignored -> false));
    }

    @Test
    void appliesOptionalTargetsWhenTheirModIsLoaded() {
        assertTrue(OptionalMixinSelector.shouldApply("AdvCraftingCpuAccessor", "advanced_ae"::equals));
        assertTrue(OptionalMixinSelector.shouldApply(
                "AppliedETransmutationModuleBatchMixin",
                "appliede"::equals));
    }

    @Test
    void neverGatesRequiredMixins() {
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCalculationMixin", ignored -> false));
    }

    @Test
    void callerSideInsertBridgeRemainsEnabledWithAdvancedAe() {
        assertTrue(OptionalMixinSelector.shouldApply(
                "ExtendedCraftingCpuInsertMixin", "advanced_ae"::equals));
        assertTrue(OptionalMixinSelector.shouldApply(
                "ExtendedCraftingCpuInsertMixin", ignored -> false));
    }

    @Test
    void nbtCopyWrappersStandDownForLithiumAndForgePortsBeforeInjection() {
        for (String owner : new String[] {"radium", "lithium", "canary"}) {
            assertFalse(OptionalMixinSelector.shouldApply(
                    "com.moakiee.thunderbolt.mixin.platform.objects.CompoundTagCopyMixin", owner::equals));
            assertFalse(OptionalMixinSelector.shouldApply("ListTagCopyMixin", owner::equals));
            assertTrue(OptionalMixinSelector.shouldApply("CraftingCalculationMixin", owner::equals));
            assertTrue(OptionalMixinSelector.shouldApply("CraftingCpuLogicBatchMixin", owner::equals));
        }
    }

    @Test
    void nbtCopyWrappersRemainAvailableWithoutAConflictingMod() {
        assertTrue(OptionalMixinSelector.shouldApply("CompoundTagCopyMixin", ignored -> false));
        assertTrue(OptionalMixinSelector.shouldApply("ListTagCopyMixin", "gtlcore"::equals));
        assertTrue(OptionalMixinSelector.shouldApply("CompoundTagCopyMixin", "modernfix"::equals));
    }
}
