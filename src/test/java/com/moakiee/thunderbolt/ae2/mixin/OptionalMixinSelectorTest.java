package com.moakiee.thunderbolt.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptionalMixinSelectorTest {
    @Test
    void neoEcoWorkerPersistenceFixRequiresNeoEco() {
        String mixin = "com.moakiee.thunderbolt.mixin.compat.neoeco.NeoEcoThreadRegistryAccessMixin";
        assertFalse(OptionalMixinSelector.shouldApply(mixin, ignored -> false));
        assertFalse(OptionalMixinSelector.shouldApply(mixin, "ae2"::equals));
        assertTrue(OptionalMixinSelector.shouldApply(mixin, "neoecoae"::equals));
    }

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
        assertTrue(OptionalMixinSelector.shouldApply("CraftConfirmMenuMixin", ignored -> false));
        assertTrue(OptionalMixinSelector.shouldApply("ExtendedCraftingCpuServiceMixin", ignored -> false));
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCalculationMixin", ignored -> false));
    }

    @Test
    void callerSideInsertBridgeRemainsEnabledWithAdvancedAe() {
        assertTrue(OptionalMixinSelector.shouldApply(
                "ExtendedCraftingCpuInsertMixin", "advanced_ae"::equals));
        assertTrue(OptionalMixinSelector.shouldApply(
                "ExtendedCraftingCpuInsertMixin", ignored -> false));
    }
}
