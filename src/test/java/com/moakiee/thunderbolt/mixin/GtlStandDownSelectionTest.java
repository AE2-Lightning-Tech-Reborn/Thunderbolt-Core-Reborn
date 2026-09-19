package com.moakiee.thunderbolt.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    void loopPlansNeverReachVanillaOrGtlCpus() throws Exception {
        // Vanilla and GTLCore CPUs accept any ICraftingPlan (AE2 keys patternTimes by
        // IPatternDetails), so a LoopCraftingPlan that slips past the extended-CPU selection
        // would be mis-executed there: reusable seed inputs charged every cycle, time-wheel
        // CPU restrictions ignored, or a stalled job holding the CPU. Only unknown
        // third-party plan types may fall through to GTLCore.
        var source = Files.readString(Path.of(
                "src", "main", "java", "com", "moakiee", "thunderbolt",
                "mixin", "ae2", "crafting", "ExtendedCraftingCpuServiceMixin.java"));
        int guards = source.split("job instanceof LoopCraftingPlan", -1).length - 1;
        assertTrue(guards >= 2,
                () -> "both the explicit-target and auto-selection submitJob paths must reject "
                        + "loop plans, found " + guards + " guard(s)");
        assertFalse(source.contains(
                "leave the ICraftingPlan to GTLCore / vanilla instead of CPU_OFFLINE"),
                "the unguarded handover fall-through must stay removed");
    }
}
