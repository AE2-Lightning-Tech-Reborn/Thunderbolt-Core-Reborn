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

    @Test void cyclesAndStatefulInputsKeepTheExistingImplementation() {
        var cyclic = CraftGraph.<String>builder().pattern("A",1,List.of(CraftInput.of("B",1)))
                .pattern("B",1,List.of(CraftInput.of("A",1))).stock("A",1).build();
        assertNull(CpSatSparseDag.trySolve(cyclic,"A",1,0));
        var stateful = CraftGraph.<String>builder().pattern("T",1,List.of(CraftInput.returned("tool",1)))
                .stock("tool",1).build();
        assertNull(CpSatSparseDag.trySolve(stateful,"T",1,0));
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
