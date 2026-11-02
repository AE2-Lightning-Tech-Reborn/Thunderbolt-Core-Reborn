package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CpSatUnitTailTest {
    @Test void deepUnitConversionWithASeedDoesNotUseTheJavaCallStack() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        var b = CraftGraph.<String>builder().stock("A", 11)
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .pattern("P0", 1, List.of(CraftInput.of("B", 1)));
        for (int i = 1; i < 4096; i++) b.pattern("P" + i, 1, List.of(CraftInput.of("P" + (i - 1), 1)));
        var graph = b.build();
        var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> CpSatRankedFlowSolver.solve(graph, "P4095", 11));
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible());
        assertFalse(result.plan().budgetExhausted());
        assertEquals(4097, result.plan().firings().size());
        assertEquals(java.util.Map.of("A", 11L), result.plan().usedStock());
    }

    private static CraftGraph<String> feedback(long seed, long targetStock, long middleStock) {
        var b = CraftGraph.<String>builder().stock("raw", 11).stock("A", seed)
                .stock("P999", targetStock).stock("P550", middleStock)
                .pattern("B", 1, List.of(CraftInput.of("A", 1), CraftInput.of("raw", 1)))
                .pattern("P0", 1, List.of(CraftInput.of("B", 1)), List.of(CraftOutput.of("A", 1)));
        for (int i = 1; i < 1000; i++) b.pattern("P" + i, 1, List.of(CraftInput.of("P" + (i - 1), 1)));
        return b.build();
    }

    @Test void feedbackCoreAndTailStockKeepExactCountsAcrossSharedAmountProbes() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        var graph = feedback(1, 2, 3);
        var session = new CpSatRankedFlowSolver.PlanningSession();
        for (long amount : new long[] {8, 11}) {
            var result = assertTimeout(Duration.ofSeconds(3),
                    () -> CpSatRankedFlowSolver.solve(graph, "P999", amount), "amount=" + amount);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            var plan = result.plan();
            assertTrue(plan.feasible());
            assertFalse(plan.budgetExhausted());
            assertEquals(amount - 5, plan.firings().get(graph.patternsFor("P0").get(0)));
            assertEquals(amount - 2, plan.firings().get(graph.patternsFor("P999").get(0)));
            assertEquals(amount - 5, plan.usedStock().get("raw"));
            assertEquals(1L, plan.usedStock().get("A"));
            assertEquals(2L, plan.usedStock().get("P999"));
            assertEquals(3L, plan.usedStock().get("P550"));
            assertEquals(amount, plan.grossDemand().get("P999"));
        }
    }

    @Test void missingSeedIsRetainedAndTheReportedRefillIsExecutable() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        var graph = feedback(0, 0, 0);
        var result = CpSatRankedFlowSolver.solve(graph, "P999", 11);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertFalse(result.plan().feasible());
        assertFalse(result.plan().missing().isEmpty());
        var refill = CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "P999", 11);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, refill.status());
        assertTrue(refill.plan().feasible());
    }

    @Test void stockedTailDoesNotStartOrBorrowFromTheCore() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        var result = CpSatRankedFlowSolver.solve(feedback(0, 0, 11), "P999", 11);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible());
        assertEquals(java.util.Map.of("P550", 11L), result.plan().usedStock());
        assertEquals(449, result.plan().firings().size());
    }

    @Test void sideCreditsStockCompetitionAndCyclesPreventElimination() {
        var sideCredit = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("P", 1)))
                .pattern("P", 1, List.of(CraftInput.of("X", 1)))
                .pattern("X", 1, List.of(CraftInput.of("raw", 2)), List.of(CraftOutput.of("P", 1))).build();
        assertNull(CpSatUnitTail.prepare(sideCredit, "T", 1, 2));
        var competition = CraftGraph.<String>builder().stock("P", 1)
                .pattern("T", 1, List.of(CraftInput.of("P", 1)))
                .pattern("P", 1, List.of(CraftInput.of("X", 1)))
                .pattern("X", 1, List.of(CraftInput.of("P", 1), CraftInput.of("raw", 1))).build();
        assertNull(CpSatUnitTail.prepare(competition, "T", 2, 2));
        var cycle = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("P", 1)))
                .pattern("P", 1, List.of(CraftInput.of("T", 1))).build();
        assertNull(CpSatUnitTail.prepare(cycle, "T", 1, 2));
        try (var ignored = PlanningCancellation.limitOptionalWork(0L)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class,
                    () -> CpSatUnitTail.prepare(sideCredit, "T", 1, 2));
        }
    }
}
