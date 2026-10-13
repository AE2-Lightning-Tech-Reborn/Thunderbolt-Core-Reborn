package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatWeightedExecutionCostTest {
    @BeforeAll static void initialize() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
    }

    @Test void rankedAndSparseUseTheWeightedObjectiveAndFixItBeforeTheStockTieBreak() {
        var expensive = CraftPattern.weighted("T", 8, List.of(CraftInput.of("raw", 1)),
                List.of(), null, 64);
        var cheap = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), null);
        var graph = CraftGraph.<String>builder().stock("raw", 8).pattern(expensive).pattern(cheap).build();
        for (var result : List.of(CpSatRankedFlowSolver.solve(graph, "T", 8),
                CpSatSparseDag.trySolve(graph, "T", 8, 0))) {
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertTrue(result.plan().feasible());
            assertTrue(result.plan().budgetExhausted(), "objective caps retain a partial witness, not a global proof");
            assertEquals(BigInteger.valueOf(8), result.plan().executionCount());
            assertEquals(Map.of(cheap, 8L), result.plan().firings());
            assertEquals(Map.of("raw", 8L), result.plan().usedStock());
            assertNotNull(MaterialDagReplay.tryLeafMissingPlan(graph, result.plan().firings(), "T", 8));
        }
    }

    @Test void rankedAndSparseCombinePositiveWeightsWithFreeTagFlow() {
        var tag = CraftPattern.tagConversion("raw", "tag", null);
        var expensive = CraftPattern.weighted("T", 8, List.of(CraftInput.of("raw", 1)),
                List.of(), null, 64);
        var cheap = CraftPattern.weighted("T", 2, List.of(CraftInput.of("tag", 1)), List.of(), null, 4);
        var graph = CraftGraph.<String>builder().stock("raw", 4)
                .pattern(expensive).pattern(cheap).pattern(tag).build();
        for (var result : List.of(CpSatRankedFlowSolver.solve(graph, "T", 8),
                CpSatSparseDag.trySolve(graph, "T", 8, 0))) {
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertTrue(result.plan().feasible());
            assertEquals(BigInteger.valueOf(16), result.plan().executionCount());
            assertEquals(Map.of(cheap, 4L, tag, 4L), result.plan().firings());
            assertNotNull(MaterialDagReplay.tryLeafMissingPlan(graph, result.plan().firings(), "T", 8));
        }
    }

    @Test void objectiveDomainOverflowIsUnknownRatherThanAFalseInfeasibilityProof() {
        int weight = Integer.MAX_VALUE;
        long amount = Sat.SAT / weight + 1;
        var weighted = CraftPattern.weighted("T", 1, List.of(CraftInput.of("raw", 1)),
                List.of(), null, weight);
        var graph = CraftGraph.<String>builder().stock("raw", amount).pattern(weighted).build();
        assertEquals(CpSatRankedFlowSolver.Status.UNKNOWN, CpSatRankedFlowSolver.solve(graph, "T", amount).status());
        assertEquals(CpSatRankedFlowSolver.Status.UNKNOWN, CpSatSparseDag.trySolve(graph, "T", amount, 0).status());
        // Direct bridge callers cannot bypass the weighted-domain check with a larger upper.
        long[] raw = CpSatRuntime.solveSparseDag(new int[][] {{0}, {0}}, new long[][] {{1}, {-1}},
                new int[][] {{0}, {}}, new long[] {1}, new long[] {Sat.SAT}, new long[] {0, 1},
                new int[] {0, 1}, 1, new int[] {64}, 1.0);
        assertEquals(3L, raw[0]);
    }
}
