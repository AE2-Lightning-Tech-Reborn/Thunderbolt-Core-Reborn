package com.moakiee.thunderbolt.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.moakiee.thunderbolt.compat.gtl.GtlCompat;

/** Verifies which mixins a GTL environment suppresses, and that nothing else is affected. */
class GtlStandDownSelectionTest {
    private static final String GTL = GtlCompat.GTL_MOD_ID;

    @AfterEach
    void clearModeOverride() {
        System.clearProperty(GtlCompat.MODE_PROPERTY);
    }

    @Test
    void gtlOwnedMixinsAreSuppressedWhileGtlcOreIsPresent() {
        assertFalse(OptionalMixinSelector.shouldApply(
                "com.moakiee.thunderbolt.mixin.ae2.crafting.CraftingCpuLogicBatchMixin", GTL::equals));
        assertFalse(OptionalMixinSelector.shouldApply(
                "AdvCraftingCpuLogicBatchMixin", GTL::equals));
        assertFalse(OptionalMixinSelector.shouldApply(
                "ExtendedAePlusSuperMatrixBatchMixin", GTL::equals));
        assertFalse(OptionalMixinSelector.shouldApply(
                "AppliedETransmutationModuleBatchMixin", GTL::equals));
    }

    @Test
    void plannerMixinStaysAppliedNextToGtlcOre() {
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCalculationMixin", GTL::equals));
        assertFalse(OptionalMixinSelector.isGtlOwned("CraftingCalculationMixin"));
    }

    @Test
    void gtlOwnedMixinsApplyWhenGtlcOreIsAbsent() {
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCalculationMixin", ignored -> false));
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCpuLogicBatchMixin", ignored -> false));
    }

    @Test
    void neverModeKeepsThemNextToGtlcOre() {
        System.setProperty(GtlCompat.MODE_PROPERTY, "never");
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCalculationMixin", GTL::equals));
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCpuLogicBatchMixin", GTL::equals));
    }

    @Test
    void alwaysModeSuppressesCpuDispatchWithoutGtlcOre() {
        System.setProperty(GtlCompat.MODE_PROPERTY, "always");
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCalculationMixin", ignored -> false));
        assertFalse(OptionalMixinSelector.shouldApply("CraftingCpuLogicBatchMixin", ignored -> false));
    }

    @Test
    void addonGatingStillAppliesToGtlOwnedAddonMixins() {
        // gtlcore present but the addon owning the mixin absent: suppressed either way, and the
        // stand-down must not be reported as the reason (see ThunderboltMixinConfigPlugin).
        System.setProperty(GtlCompat.MODE_PROPERTY, "never");
        assertFalse(OptionalMixinSelector.shouldApply(
                "AdvCraftingCpuLogicBatchMixin", GTL::equals));
    }

    @Test
    void mixinsOutsideTheGtlOwnedFamilyAreUnaffected() {
        assertTrue(OptionalMixinSelector.shouldApply("ExtendedCraftingCpuServiceMixin", GTL::equals));
        assertTrue(OptionalMixinSelector.shouldApply("CraftingCpuLogicAccessor", GTL::equals));
        assertTrue(OptionalMixinSelector.shouldApply("CraftConfirmMenuMixin", GTL::equals));
        assertTrue(OptionalMixinSelector.shouldApply("ExecutingCraftingJobAccessor", GTL::equals));
        assertFalse(OptionalMixinSelector.isGtlOwned("ExecutingCraftingJobAccessor"));
    }

    @Test
    void gtlOwnershipIsReportedForDiagnostics() {
        assertEquals(null, OptionalMixinSelector.gtlOwner("CraftingCalculationMixin"));
        assertEquals(GTL, OptionalMixinSelector.gtlOwner(
                "com.moakiee.thunderbolt.mixin.ae2.crafting.CraftingCpuLogicBatchMixin"));
        assertEquals(null, OptionalMixinSelector.gtlOwner("CraftConfirmMenuMixin"));
        assertEquals(null, OptionalMixinSelector.requiredMod("CraftingCalculationMixin"));
        assertEquals("advanced_ae",
                OptionalMixinSelector.requiredMod("AaeExecutingCraftingJobAccessor"));
    }
}
