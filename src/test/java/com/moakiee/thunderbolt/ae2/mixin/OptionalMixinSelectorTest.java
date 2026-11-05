package com.moakiee.thunderbolt.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptionalMixinSelectorTest {
    @Test
    void craftingTreeSummaryBridgeRequiresCraftingTree() {
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
    void exactSummaryBridgeRequiresLightningTech() {
        assertFalse(OptionalMixinSelector.shouldApply("LtBigCraftingPlanSummaryMixin", ignored -> false));
        assertTrue(OptionalMixinSelector.shouldApply("LtBigCraftingPlanSummaryMixin", "ae2lt"::equals));
    }

    @Test
    void ioPortSnapshotRequiresLightningTech() {
        assertFalse(OptionalMixinSelector.shouldApply("LtIOPortInventoryLoadMixin", ignored -> false));
        assertTrue(OptionalMixinSelector.shouldApply("LtIOPortInventoryLoadMixin", "ae2lt"::equals));
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
    void nbtCopyWrappersYieldToKnownOwnersWithoutDisablingOtherHooks() {
        for (String mod : new String[] {"leanobject", "radium", "lithium", "canary"}) {
            for (String mixin : new String[] {"CompoundTagCopyMixin", "ListTagCopyMixin"}) {
                assertFalse(OptionalMixinSelector.shouldApply(mixin, mod::equals), mod);
                assertFalse(OptionalMixinSelector.shouldApply("example." + mixin, mod::equals), mod);
                org.junit.jupiter.api.Assertions.assertEquals(mod,
                        OptionalMixinSelector.nbtCopyOwner(mixin, mod::equals));
            }
            assertTrue(OptionalMixinSelector.shouldApply("GtoCraftingPlannerMixin", mod::equals));
        }
    }

    @Test
    void nativeNbtWrappersStayEnabledWhenNoOtherCopyOwnerIsInstalled() {
        assertTrue(OptionalMixinSelector.shouldApply("CompoundTagCopyMixin", ignored -> false));
        assertTrue(OptionalMixinSelector.shouldApply("ListTagCopyMixin", "other_mod"::equals));
        org.junit.jupiter.api.Assertions.assertNull(
                OptionalMixinSelector.nbtCopyOwner("GtoCraftingPlannerMixin", ignored -> true));
    }
}
