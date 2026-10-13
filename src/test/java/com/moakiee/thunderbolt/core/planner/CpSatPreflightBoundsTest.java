package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CpSatPreflightBoundsTest {
    @Test void costBoundRetainsUnusedCheaperRecipesAndDoesNotFreezeTheWitnessSupport() {
        var a = CraftPattern.weighted("T", 1, List.of(CraftInput.of("A", 1)), List.of(), null, 10);
        var b = CraftPattern.weighted("T", 1, List.of(CraftInput.of("B", 1)), List.of(), null, 2);
        var witness = plan(Map.of(a, 1L), Map.of());
        long[] original = {100, 100};
        assertArrayEquals(new long[] {1, 5},
                CpSatRankedFlowSolver.zeroMissingExecutionBounds(List.of(a, b), original, witness));
        assertArrayEquals(new long[] {100, 100}, original);
    }

    @Test void zeroCostConversionsAndMissingWitnessesCannotBoundExecutionCost() {
        var ordinary = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), null);
        var conversion = CraftPattern.tagConversion("raw", "T", null);
        assertNull(CpSatRankedFlowSolver.zeroMissingExecutionBounds(List.of(ordinary, conversion),
                new long[] {100, 100}, plan(Map.of(ordinary, 1L), Map.of())));
        assertNull(CpSatRankedFlowSolver.zeroMissingExecutionBounds(List.of(ordinary),
                new long[] {100}, plan(Map.of(ordinary, 1L), Map.of("raw", 1L))));
        assertNull(CpSatRankedFlowSolver.zeroMissingExecutionBounds(List.of(ordinary),
                new long[] {0}, plan(Map.of(ordinary, 1L), Map.of())));
    }

    private static CraftPlan<String> plan(Map<CraftPattern<String>, Long> firings, Map<String, Long> missing) {
        return new CraftPlan<>(true, missing.isEmpty(), firings, Map.of(), Map.of(), missing, Map.of(), 2, false);
    }
}
