package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatSparseDagTest {
    @BeforeAll static void initialize() { assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath()); }

    @Test void tenThousandRecipeChainDoesNotMaterializeSquareMatrices() {
        for (long n : new long[] {1, 1_000_000_000_000L}) {
            var graph = chain(10_000, n);
            var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> CpSatRankedFlowSolver.solve(graph, "P10000", n));
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertTrue(result.plan().feasible());
            assertEquals(10_000, result.plan().firings().size());
            assertTrue(result.plan().firings().values().stream().allMatch(count -> count == n));
            assertEquals(Map.of("P0", n), result.plan().usedStock());
        }
    }

    @Test void wideGraphDiagnosesOneMissingPhysicalUnitAndRefills() {
        int size = 10_000;
        long n = 1_000_000_000_000L;
        var builder = CraftGraph.<String>builder().stock("raw", size*n-1);
        var root = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < size; i++) {
            builder.pattern("P"+i, 1, List.of(CraftInput.of("raw", 1)));
            root.add(CraftInput.of("P"+i, 1));
        }
        var graph = builder.pattern("T", 1, root).build();
        var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> CpSatRankedFlowSolver.solve(graph,"T",n));
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("raw",1L), result.plan().missing());
        var refill = CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "T", n);
        assertTrue(refill.plan().feasible());
        assertEquals(size+1, refill.plan().firings().size());
    }

    @Test void unrelatedNetworkPatternsNeverEnterTheTargetModel() {
        var builder = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 1, List.of(CraftInput.of("raw",1)));
        for (int i = 0; i < 10_000; i++) {
            // These side outputs and self loops would disqualify the sparse DAG representation
            // if the compiler walked the entire catalog instead of the requested dependency cone.
            builder.pattern(new CraftPattern<>("unrelated"+i, 2,
                    List.of(CraftInput.of("unrelated"+i, 1)), List.of(CraftOutput.of("side"+i,1)), null));
        }
        var graph = builder.build();
        var forced = CpSatSparseDag.trySolve(graph, "T", 1, 0);
        assertNotNull(forced);
        assertTrue(forced.plan().feasible());
        assertEquals(1, forced.plan().firings().size());
        // Representation selection must also count only the reachable graph (two cells).
        assertNull(CpSatSparseDag.trySolve(graph, "T", 1, 100));
        var normal = CpSatRankedFlowSolver.solve(graph, "T", 1);
        assertTrue(normal.plan().feasible());
        assertEquals(Map.of("raw",1L), normal.plan().usedStock());
    }

    @Test void sparseAndRankedModelsAgreeOnOneHundredRandomDags() {
        var random = new Random(2026091820);
        for (int sample = 0; sample < 100; sample++) {
            var builder = CraftGraph.<String>builder();
            for (int i = 0; i < 7; i++) builder.stock("M"+i, random.nextInt(4));
            for (int i = 2; i < 7; i++) for (int a = 0; a < 2; a++) {
                var inputs = List.of(CraftInput.of("M"+random.nextInt(i), 1+random.nextInt(2)),
                        CraftInput.of("M"+random.nextInt(i),1));
                builder.pattern("M"+i, 1+random.nextInt(3), inputs);
            }
            var graph = builder.build(); long n = 1+random.nextInt(6);
            var sparse = CpSatSparseDag.trySolve(graph, "M6", n, 0);
            var dense = CpSatRankedFlowSolver.solve(graph, "M6", n);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, sparse.status(), "sparse " + sample);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, dense.status(), "ranked " + sample);
            assertEquals(dense.plan().feasible(), sparse.plan().feasible());
            assertEquals(total(dense.plan().missing()), total(sparse.plan().missing()));
            assertEquals(total(dense.plan().firings()), total(sparse.plan().firings()));
            assertEquals(total(dense.plan().usedStock()), total(sparse.plan().usedStock()));
            assertNotNull(MaterialDagReplay.tryLeafMissingPlan(graph, sparse.plan().firings(), "M6", n));
        }
    }

    @Test void cyclesStayOutButSimpleStatefulDagUsesItsCertificate() {
        var cyclic = CraftGraph.<String>builder().pattern("A",1,List.of(CraftInput.of("B",1)))
                .pattern("B",1,List.of(CraftInput.of("A",1))).stock("A",1).build();
        assertNull(CpSatSparseDag.trySolve(cyclic,"A",1,0));
        var stateful = CraftGraph.<String>builder().pattern("T",1,List.of(CraftInput.returned("tool",1)))
                .stock("tool",1).build();
        var catalyst = CpSatSparseDag.trySolve(stateful, "T", 1, 0);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, catalyst.status());
        assertTrue(catalyst.plan().feasible());
        assertEquals(Map.of("tool", 1L), catalyst.plan().usedStock());
    }

    @Test void unconsumedSideOutputsUseTheFixedDagCertificateAndRefillExactLeaves() {
        var recipe = new CraftPattern<>("T", 2, List.of(CraftInput.of("raw", 3)),
                List.of(CraftOutput.of("unused", 7)), null);
        var graph = CraftGraph.<String>builder().pattern(recipe).stock("raw", 5).stock("unused", 100).build();
        var result = CpSatSparseDag.trySolve(graph, "T", 4, 0);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertEquals(Map.of("raw", 1L), result.plan().missing());
        assertEquals(Map.of(recipe, 2L), result.plan().firings());
        assertEquals(Map.of("raw", 5L), result.plan().usedStock());
        assertEquals(0, result.branches());
        var ready = CpSatSparseDag.trySolve(graph.withAdditionalStock(result.plan().missing()), "T", 4, 0);
        assertTrue(ready.plan().feasible());
        assertFalse(ready.plan().budgetExhausted());
        assertEquals(Map.of("raw", 6L), ready.plan().usedStock());
        assertTrue(UnorderedByproductSafety.allOrdersFinishSmall(ready.plan(), "T", 4));
    }

    @Test void finiteToolsUseExactBatchDemandAndRefillWithoutEnteringTheLinearModel() {
        var recipe = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("raw", 1), CraftInput.finiteUse("tool", 2, 10)), null);
        for (long amount : new long[] {1, 10, 11, 100, 1_000_000_000_000L}) {
            long tools = 2 * Sat.ceilDiv(amount, 10);
            var graph = CraftGraph.<String>builder().pattern(recipe).stock("raw", amount)
                    .stock("tool", tools - 1).build();
            var result = CpSatSparseDag.trySolve(graph, "T", amount, 0);
            assertNotNull(result);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertEquals(Map.of(recipe, amount), result.plan().firings());
            assertEquals(Map.of("tool", 1L), result.plan().missing());
            assertEquals(tools - 1, result.plan().usedStock().getOrDefault("tool", 0L));
            var ready = CpSatSparseDag.trySolve(graph.withAdditionalStock(result.plan().missing()),
                    "T", amount, 0);
            assertTrue(ready.plan().feasible());
            assertEquals(Map.of("raw", amount, "tool", tools), ready.plan().usedStock());
            assertFalse(ready.plan().budgetExhausted());
            assertEquals(0, ready.branches());
        }
        var alternative = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 2)), null);
        var competing = CraftGraph.<String>builder().pattern(recipe).pattern(alternative)
                .stock("raw", 22).stock("tool", 4).build();
        assertNull(CpSatSparseDag.trySolve(competing, "T", 11, 0));
        var ranked = CpSatRankedFlowSolver.solve(competing, "T", 11);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, ranked.status());
        assertTrue(ranked.plan().feasible());
    }

    @Test void unusedContainerRemaindersKeepExactConsumptionAndTheOriginalPattern() {
        var recipe = new CraftPattern<>("T", 2, List.of(CraftInput.consumedReturning("raw", 3, "empty")), null);
        var graph = CraftGraph.<String>builder().pattern(recipe).stock("raw", 5).build();
        var result = CpSatSparseDag.trySolve(graph, "T", 4, 0);
        assertNotNull(result);
        assertEquals(Map.of(recipe, 2L), result.plan().firings());
        assertEquals(Map.of("raw", 1L), result.plan().missing());
        assertEquals(Map.of("raw", 5L), result.plan().usedStock());
        var ready = CpSatSparseDag.trySolve(graph.withAdditionalStock(result.plan().missing()), "T", 4, 0);
        assertTrue(ready.plan().feasible());
        assertFalse(ready.plan().budgetExhausted());
        assertEquals(Map.of("raw", 6L), ready.plan().usedStock());
        assertEquals(0, ready.branches());
        assertTrue(UnorderedByproductSafety.allOrdersFinishSmall(ready.plan(), "T", 4));

        var consumer = new CraftPattern<>("U", 1, List.of(CraftInput.of("T", 1), CraftInput.of("empty", 1)), null);
        assertNull(CpSatSparseDag.trySolve(CraftGraph.<String>builder().pattern(recipe).pattern(consumer)
                .stock("raw", 3).build(), "U", 1, 0));
    }

    @Test void aConsumedSideOutputOrSelfReturnStillRequiresTheRankedModel() {
        var producer = new CraftPattern<>("A", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("B", 1)), null);
        var graph = CraftGraph.<String>builder().pattern(producer).pattern("T", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).stock("raw", 1).build();
        assertNull(CpSatSparseDag.trySolve(graph, "T", 1, 0));
        var self = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("raw", 1)), null);
        assertNull(CpSatSparseDag.trySolve(CraftGraph.<String>builder().pattern(self).stock("raw", 1).build(),
                "T", 1, 0));
    }

    @Test void fixedDagUsesStockAndMergedInputsWithWeightedExecutionsAndExactRefill() {
        var batch = CraftPattern.weighted("part", 3,
                List.of(CraftInput.of("raw", 2), CraftInput.of("raw", 1)), List.of(), null, 7);
        var target = CraftPattern.weighted("T", 2, List.of(CraftInput.of("part", 4)), List.of(), null, 3);
        var graph = CraftGraph.<String>builder().stock("raw", 6).stock("part", 1)
                .stock("T", 1).pattern(batch).pattern(target).build();
        var result = CpSatSparseDag.trySolve(graph, "T", 5, 0);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("raw", 3L), result.plan().missing());
        assertEquals(Map.of(batch, 3L, target, 2L), result.plan().firings());
        assertEquals(java.math.BigInteger.valueOf(27), result.plan().executionCount());
        var supplied = graph.withAdditionalStock(result.plan().missing());
        var ready = CpSatSparseDag.trySolve(supplied, "T", 5, 0);
        assertTrue(ready.plan().feasible());
        // Three batches make nine parts, covering the eight consumed parts without extracting
        // the stocked part. The final certificate removes that unnecessary draw.
        assertEquals(Map.of("raw", 9L, "T", 1L), ready.plan().usedStock());
        assertEquals(result.plan().firings(), ready.plan().firings());
        assertEquals(0, ready.branches());
    }

    @Test void repeatedPatternIdentityIsNotASecondRecipeVariable() {
        var p = new CraftPattern<>("T",1,List.of(CraftInput.of("raw",1)),null);
        var graph = CraftGraph.<String>builder().pattern(p).pattern(p).stock("raw",2).build();
        var result = CpSatSparseDag.trySolve(graph,"T",2,0);
        assertTrue(result.plan().feasible());
        assertEquals(Map.of(p,2L),result.plan().firings());
    }

    @Test void cancellationIsNotReportedAsMissingOrInfeasible() {
        var context = new PlanningAttemptContext() {
            public long deadlineNanos() { return System.nanoTime()-1; }
            public void checkpoint() { throw new PlanningExitException("test cancellation"); }
            public void report(PlanningDiagnosticSnapshot ignored) {}
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertThrows(PlanningExitException.class, () -> CpSatSparseDag.trySolve(chain(3,1),"P3",1,0));
        }
    }

    private static long total(Map<?,Long> values) { return values.values().stream().mapToLong(Long::longValue).sum(); }
    private static CraftGraph<String> chain(int size, long stock) {
        var b = CraftGraph.<String>builder().stock("P0",stock);
        for (int i = 1; i <= size; i++) b.pattern("P"+i,1,List.of(CraftInput.of("P"+(i-1),1)));
        return b.build();
    }
}
