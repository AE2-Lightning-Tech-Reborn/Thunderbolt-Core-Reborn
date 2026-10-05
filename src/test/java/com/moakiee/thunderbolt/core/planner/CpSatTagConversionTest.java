package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatTagConversionTest {
    @BeforeAll static void initialize() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
    }

    @Test void rankedAndSparsePreferFewerRealExecutionsThroughMoreTagEdges() {
        var first = CraftPattern.tagConversion("raw", "tag1", null);
        var second = CraftPattern.tagConversion("tag1", "tag2", null);
        var third = CraftPattern.tagConversion("tag2", "tag3", null);
        var tagged = new CraftPattern<>("T", 1, List.of(CraftInput.of("tag3", 1)), null);
        var intermediate = new CraftPattern<>("mid", 1, List.of(CraftInput.of("raw", 1)), null);
        var direct = new CraftPattern<>("T", 1, List.of(CraftInput.of("mid", 1)), null);
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern(direct).pattern(intermediate).pattern(tagged)
                .pattern(first).pattern(second).pattern(third).build();

        for (var result : List.of(CpSatRankedFlowSolver.solve(graph, "T", 1),
                CpSatSparseDag.trySolve(graph, "T", 1, 0))) {
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            var plan = result.plan();
            assertTrue(plan.feasible());
            assertEquals(BigInteger.ONE, plan.executionCount());
            assertEquals(Map.of(first, 1L, second, 1L, third, 1L, tagged, 1L), plan.firings());
            assertEquals(Map.of("raw", 1L), plan.usedStock());
            assertNotNull(MaterialDagReplay.tryLeafMissingPlan(graph, plan.firings(), "T", 1));
        }
    }

    @Test void allTagObjectiveStillReportsAndReplaysMissingPhysicalStock() {
        var first = CraftPattern.tagConversion("raw", "tag1", null);
        var second = CraftPattern.tagConversion("tag1", "tag2", null);
        var graph = CraftGraph.<String>builder().stock("raw", 2).pattern(first).pattern(second).build();
        for (var result : List.of(CpSatRankedFlowSolver.solve(graph, "tag2", 3),
                CpSatSparseDag.trySolve(graph, "tag2", 3, 0))) {
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            var plan = result.plan();
            assertFalse(plan.feasible());
            assertEquals(BigInteger.ZERO, plan.executionCount());
            assertEquals(Map.of(first, 3L, second, 3L), plan.firings());
            assertEquals(Map.of("raw", 2L), plan.usedStock());
            assertEquals(Map.of("raw", 1L), plan.missing());
            assertNotNull(MaterialDagReplay.tryLeafMissingPlan(graph, plan.firings(), "tag2", 3));
        }
    }

    @Test void zeroCostCycleKeepsItsStockSeedAndConcreteFirings() {
        var forward = CraftPattern.tagConversion("A", "B", null);
        var backward = CraftPattern.tagConversion("B", "A", null);
        var graph = CraftGraph.<String>builder().stock("A", 1).pattern(forward).pattern(backward).build();
        var result = CpSatRankedFlowSolver.solve(graph, "B", 1);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible());
        assertEquals(BigInteger.ZERO, result.plan().executionCount());
        assertEquals(Map.of(forward, 1L), result.plan().firings());
        assertEquals(Map.of("A", 1L), result.plan().usedStock());
    }

    @Test void sideOutputProjectionRetainsOriginalTagMetadata() {
        var tag = CraftPattern.tagConversion("raw", "tag", null);
        var target = new CraftPattern<>("T", 1, List.of(CraftInput.of("tag", 1)),
                List.of(CraftOutput.of("side", 1)), null);
        var graph = CraftGraph.<String>builder().stock("raw", 1).pattern(tag).pattern(target).build();
        var projections = MaterialDagOrders.compile(graph, "T");
        assertFalse(projections.isEmpty());
        for (var projection : projections) {
            assertSame(tag, projection.graph().patternsFor("tag").get(0));
            assertEquals(0, projection.graph().patternsFor("tag").get(0).executionCost());
        }
    }
}
