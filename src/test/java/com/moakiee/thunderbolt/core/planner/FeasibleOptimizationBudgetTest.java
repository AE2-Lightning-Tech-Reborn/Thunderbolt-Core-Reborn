package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeasibleOptimizationBudgetTest {
    @Test
    void indexAndPropagationInheritStallDeadlineAndExpiryKeepsTheOriginal() {
        for (String stage : new String[] {"Index.build", "Search.propagate"}) {
            var graph = graph();
            var initial = initial(graph, 1);
            var cache = new FeasibleConsumptionOptimizer.IndexCache<String>();
            var probe = new StageExpiry(stage, null);
            var calls = new AtomicInteger();
            try (var parent = PlanningCancellation.limitOptionalWork(5_000_000_000L)) {
                long parentDeadline = deadlines().get();
                try (var bound = PlanningCancellation.bind(probe)) {
                    var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32,
                            candidate -> { calls.incrementAndGet(); return null; }, cache);
                    assertSame(initial, result.plan());
                    assertEquals(0, result.probes());
                }
                assertEquals(parentDeadline, deadlines().get());
            }
            assertTrue(probe.observed, stage);
            assertEquals(0, calls.get());
            if (stage.equals("Index.build")) assertFalse(cache.isCompiled());
            assertNull(deadlines().get());
            assertDoesNotThrow(PlanningCancellation::check);
        }
    }

    @Test
    void preprocessingStillPropagatesOuterExit() {
        var graph = graph();
        var exit = new PlanningExitException("cancel during optimization indexing");
        var probe = new StageExpiry("Index.build", exit);
        try (var bound = PlanningCancellation.bind(probe)) {
            assertSame(exit, assertThrows(PlanningExitException.class, () ->
                    FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial(graph, 1), 32,
                            candidate -> { throw new AssertionError("oracle ran"); })));
        }
        assertTrue(probe.observed);
        assertNull(deadlines().get());
    }

    @Test
    void frontLoadedBatchStagesShareTheStallDeadlineAndKeepTheCoreWitness() {
        for (int routes : new int[] {2, 3}) {
            var b = CraftGraph.<String>builder().stock("raw", 128);
            b.pattern("T", 3, List.of(CraftInput.of("raw", 3)));
            b.pattern("T", 2, List.of(CraftInput.of("raw", 1)));
            if (routes == 3) b.pattern("T", 4, List.of(CraftInput.of("raw", 7)));
            var graph = b.build();
            var baseline = new CraftPlannerV2.PlanningSession<String>();
            baseline.optimizeFeasible = false;
            var original = CraftPlannerV2.planDetailed(graph, "T", 8, baseline).plan();
            var probe = new StageExpiry(routes == 2 ? "TwoRouteBatchOptimizer.solve"
                    : "MultiRouteBatchOptimizer.solve", null);
            try (var bound = PlanningCancellation.bind(probe)) {
                var result = CraftPlannerV2.planDetailed(graph, "T", 8);
                assertEquals(original.firings(), result.plan().firings());
                assertEquals(original.usedStock(), result.plan().usedStock());
                assertTrue(result.plan().feasible());
            }
            assertTrue(probe.observed, "routes=" + routes);
            assertNull(deadlines().get());
        }
    }

    @Test
    void anOracleReturningAfterExpiryCannotReplaceTheIncumbent() {
        var graph = graph();
        var initial = initial(graph, 1);
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32, candidate -> {
            deadlines().set(System.nanoTime() - 1L);
            return new CraftPlan<>(true, true, Map.of(graph.patternsFor("T").get(1), 1L),
                    Map.of("raw", 2L), Map.of(), Map.of(), Map.of("T", 1L), 2, false);
        });
        assertSame(initial, result.plan());
        assertEquals(1, result.probes());
        assertEquals(0, result.improvements());
        assertNull(deadlines().get());
    }

    @Test
    void realExecutionProgressRenewsStallButCannotExtendTotalOrParent() {
        var graph = graph();
        try (var parent = PlanningCancellation.limitOptionalWork(2_000_000_000L)) {
            long parentDeadline = deadlines().get();
            try (var budget = new FeasibleOptimizationBudget(3_000_000_000L, 1_000_000_000L)) {
                deadlines().set(System.nanoTime() + 250_000_000L);
                long previous = deadlines().get();
                budget.improved(initial(graph, 100), initial(graph, 98));
                assertTrue(deadlines().get() > previous, "real progress renews the active cutoff");
                assertTrue(deadlines().get() <= parentDeadline);
                try (var probe = PlanningCancellation.limitOptionalWork(5_000_000_000L)) {
                    assertTrue(deadlines().get() <= parentDeadline);
                }
                assertDoesNotThrow(PlanningCancellation::check);
            }
            assertEquals(parentDeadline, deadlines().get());
        }
        try (var budget = new FeasibleOptimizationBudget(100_000_000L, 1_000_000_000L)) {
            long totalDeadline = deadlines().get();
            deadlines().set(System.nanoTime() + 10_000_000L);
            budget.improved(initial(graph, 100), initial(graph, 98));
            assertTrue(deadlines().get() <= totalDeadline, "renewal must charge elapsed total time");
        }
        assertNull(deadlines().get());
    }

    @Test
    void stockOnlyAndSubPercentGainsCannotRenewStallAndZeroDisablesIt() {
        var graph = graph();
        try (var budget = new FeasibleOptimizationBudget(3_000_000_000L, 1_000_000_000L)) {
            long deadline = deadlines().get();
            budget.improved(initial(graph, 1000), initial(graph, 999));
            assertEquals(deadline, deadlines().get());
            var stockOnly = new CraftPlan<>(true, true, initial(graph, 1000).firings(),
                    Map.of("raw", 999L), Map.of(), Map.of(), Map.of(), 2, false);
            budget.improved(initial(graph, 1000), stockOnly);
            assertEquals(deadline, deadlines().get());
        }
        long started = System.nanoTime();
        try (var budget = new FeasibleOptimizationBudget(2_000_000_000L, 0L)) {
            assertFalse(budget.stalled());
            assertTrue(deadlines().get() > started + 1_000_000_000L);
            long deadline = deadlines().get();
            budget.improved(initial(graph, 100), initial(graph, 98));
            assertEquals(deadline, deadlines().get());
        }
        assertNull(deadlines().get());
    }

    @Test
    void expiryAndThreadInterruptionRestoreTheOriginalParentScope() {
        var graph = graph();
        try (var parent = PlanningCancellation.limitOptionalWork(2_000_000_000L)) {
            long parentDeadline = deadlines().get();
            try (var budget = new FeasibleOptimizationBudget(1_000_000_000L)) {
                deadlines().set(System.nanoTime() - 1L);
                assertThrows(PlanningCancellation.OptionalWorkLimit.class,
                        () -> budget.improved(initial(graph, 100), initial(graph, 98)));
            }
            assertEquals(parentDeadline, deadlines().get());
            try (var budget = new FeasibleOptimizationBudget(1_000_000_000L)) {
                Thread.currentThread().interrupt();
                try {
                    assertThrows(CancellationException.class,
                            () -> budget.improved(initial(graph, 100), initial(graph, 98)));
                } finally { Thread.interrupted(); }
            }
            assertEquals(parentDeadline, deadlines().get());
        }
        assertNull(deadlines().get());
        assertDoesNotThrow(PlanningCancellation::check);
    }

    private static CraftGraph<String> graph() {
        return CraftGraph.<String>builder().stock("raw", 20_000)
                .pattern("T", 10, List.of(CraftInput.of("raw", 10)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 2))).build();
    }

    private static CraftPlan<String> initial(CraftGraph<String> graph, long count) {
        return new CraftPlan<>(true, true, Map.of(graph.patternsFor("T").get(0), count),
                Map.of("raw", 10 * count), Map.of(), Map.of(), Map.of("T", 10 * count), 2, false);
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Long> deadlines() {
        try {
            var field = PlanningCancellation.class.getDeclaredField("OPTIONAL_DEADLINE");
            field.setAccessible(true);
            return (ThreadLocal<Long>) field.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** Expire at an actual stage checkpoint; no sleep or dependence on how long work takes. */
    private static final class StageExpiry implements PlanningAttemptContext {
        private final String stage;
        private final PlanningExitException exit;
        boolean observed;

        StageExpiry(String stage, PlanningExitException exit) { this.stage = stage; this.exit = exit; }
        public long deadlineNanos() { return Long.MAX_VALUE; }
        public void report(PlanningDiagnosticSnapshot snapshot) {}
        public void checkpoint() {
            Long deadline = deadlines().get();
            if (observed || deadline == null) return;
            for (var frame : Thread.currentThread().getStackTrace()) {
                if (!(frame.getClassName().endsWith("$" + stage.split("\\.")[0])
                        || frame.getClassName().endsWith("." + stage.split("\\.")[0]))
                        || !frame.getMethodName().equals(stage.split("\\.")[1])) continue;
                observed = true;
                if (FeasibleConsumptionOptimizer.STALL_NANOS != 0)
                    assertTrue(deadline - System.nanoTime() <= FeasibleConsumptionOptimizer.STALL_NANOS,
                            stage + " did not inherit the stall cutoff");
                if (exit != null) throw exit;
                deadlines().set(System.nanoTime() - 1L);
                return;
            }
        }
    }
}
