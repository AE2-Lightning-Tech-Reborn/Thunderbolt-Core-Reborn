package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import org.junit.jupiter.api.Test;

class MaterialDagBudgetTest {
    @Test
    void theSameDagCertificateWorksWithMinimalRecursiveSearch() {
        for (var graph : List.of(batchCycle().withAdditionalStock(Map.of("M0", 1L)),
                sharedCycle().withAdditionalStock(Map.of("M2", 5L)))) {
            var result = CraftPlannerV2.planDetailed(graph, "M6", 2, 1, 1);
            assertTrue(result.plan().feasible(), () -> result.diagnostics().toString());
            assertTrue(result.diagnostics().consumedSearchBudget() <= 1);
            assertBalance(graph, result.plan());
        }
    }

    @Test
    void refinementKeepsTheSmallerSupplementAndItStillReplansWithMinimalSearch() {
        var graph = sharedCycle();
        var result = CraftPlannerV2.planDetailed(graph, "M6", 2);
        assertTrue(result.plan().missing().keySet().stream().allMatch("M2"::equals));
        assertTrue(result.plan().missing().getOrDefault("M2", 0L) < 6,
                () -> result.plan().missing().toString());
        var supplied = graph.withAdditionalStock(result.plan().missing());
        var ready = CraftPlannerV2.planDetailed(supplied, "M6", 2, 1, 1);
        assertTrue(ready.plan().feasible());
        assertBalance(supplied, ready.plan());
    }

    @Test
    void anotherMinimalCycleLeafAlsoKeepsTheReplenishmentPromise() {
        var graph = batchCycle();
        var plan = CraftPlannerV2.plan(graph, "M6", 2);
        assertEquals(1, plan.missing().size());
        assertEquals(1L, plan.missing().values().iterator().next());
        var supplied = graph.withAdditionalStock(plan.missing());
        var ready = CraftPlannerV2.plan(supplied, "M6", 2, 1, 1);
        assertTrue(ready.feasible());
        assertBalance(supplied, ready);
    }

    @Test
    void compilationHasAWorkLimitAndCancellationStillPropagates() {
        var graph = batchCycle();
        assertTrue(MaterialDagOrders.compile(graph, "M6",
                BoundedIntegerLinearSolver.WorkBudget.bounded(1, 1, Long.MAX_VALUE)).isEmpty());
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> MaterialDagOrders.compile(graph, "M6"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void replenishmentPortfolioUsesTheExistingRefinementDeadlineWithoutQuarteringItAgain() {
        var graph = sharedCycle();
        var context = new RefinementDeadlineProbe(null, false);
        PlanningResult<String> result;
        try (var ignored = PlanningCancellation.bind(context)) {
            result = CraftPlannerV2.planDetailed(graph, "M6", 2);
        }
        assertTrue(context.observedPortfolio, "must inspect a portfolio inside the replenishment oracle");
        // Compare installed deadlines, not elapsed runtime: cold class loading or scheduling
        // cannot make a deliberate quarter-sized child slice satisfy this relationship.
        assertTrue(context.portfolioDeadline >= context.refinementDeadline,
                "the nested proof must not reserve the caller's adaptation time a second time");
        assertTrue(context.portfolioDeadline <= context.refinementDeadline + 1_000_000L,
                "the child scope must still respect the parent's 100 ms deadline");
        assertTrue(result.plan().missing().getOrDefault("M2", 0L) < 6,
                () -> result.diagnostics().toString());
        var supplied = graph.withAdditionalStock(result.plan().missing());
        var ready = CraftPlannerV2.plan(supplied, "M6", 2, 1, 1);
        assertTrue(ready.feasible());
        assertBalance(supplied, ready);
    }

    @Test
    void replenishmentPortfolioStillPropagatesOuterCancellation() {
        var exit = new PlanningExitException("test cancellation during replenishment proof");
        var context = new RefinementDeadlineProbe(exit, false);
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> CraftPlannerV2.plan(sharedCycle(), "M6", 2)));
        }
        assertTrue(context.observedPortfolio);
        assertDoesNotThrow(PlanningCancellation::check, "the caller scope must be restored after cancellation");
    }

    @Test
    void anExpiredRefinementDeadlineKeepsTheLastExecutableSupplement() {
        var graph = sharedCycle();
        var context = new RefinementDeadlineProbe(null, true);
        CraftPlan<String> result;
        try (var ignored = PlanningCancellation.bind(context)) {
            result = CraftPlannerV2.plan(graph, "M6", 2);
        }
        assertTrue(context.expiredRefinement);
        assertEquals(Map.of("M2", 6L), result.missing());
        var supplied = graph.withAdditionalStock(result.missing());
        var ready = CraftPlannerV2.plan(supplied, "M6", 2, 1, 1);
        assertTrue(ready.feasible());
        assertBalance(supplied, ready);
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void stockRefreshRetainsInitiallyUnproductiveSupportsWithoutAccumulatingProbeStock() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("raw", 2))).build();
        var templates = MaterialDagOrders.compile(graph, "T");
        assertFalse(templates.isEmpty(), "zero capacity does not mean unusable topology after replenishment");
        assertTrue(templates.stream().noneMatch(candidate -> candidate.maySupply(1)));
        var two = MaterialDagOrders.withAdditionalStock(templates, Map.of("raw", 2L), "T",
                BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertTrue(two.stream().allMatch(candidate -> candidate.maySupply(1)));
        for (var candidate : two) {
            var ready = CraftPlannerV2.plan(candidate.graph(), "T", 1, 1, 1);
            assertTrue(ready.feasible());
            assertEquals(Map.of("raw", 2L), ready.usedStock());
        }
        var one = MaterialDagOrders.withAdditionalStock(templates, Map.of("raw", 1L), "T",
                BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertTrue(one.stream().noneMatch(candidate -> candidate.maySupply(1)));
        assertEquals(0L, graph.stock("raw"));
        assertTrue(templates.stream().allMatch(candidate -> candidate.graph().stock("raw") == 0L));
        assertTrue(two.stream().allMatch(candidate -> candidate.graph().stock("raw") == 2L));
        assertTrue(one.stream().allMatch(candidate -> candidate.graph().stock("raw") == 1L));
    }

    @Test
    void stockRefreshChargesTheSharedBudgetAndPreservesOriginalSideOutputRecipes() {
        var graph = CraftGraph.<String>builder().stock("raw", 1L)
                .pattern("P", 1, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("side", 1)))
                .pattern("T", 1, List.of(CraftInput.of("P", 1))).build();
        var templates = MaterialDagOrders.compile(graph, "T");
        assertTrue(MaterialDagOrders.withAdditionalStock(templates,
                Map.of("raw", 1L), "T", BoundedIntegerLinearSolver.WorkBudget.bounded(1, 1, Long.MAX_VALUE)).isEmpty());
        var refreshed = MaterialDagOrders.withAdditionalStock(templates, Map.of("raw", 1L), "T",
                BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertTrue(refreshed.stream().anyMatch(candidate -> !candidate.originals().isEmpty()));
        for (var candidate : refreshed) {
            assertEquals(2L, candidate.graph().stock("raw"));
            if (!candidate.maySupply(1)) continue;
            var restored = candidate.restore(CraftPlannerV2.plan(candidate.graph(), "T", 1, 1, 1));
            assertTrue(restored.feasible());
            assertTrue(restored.firings().keySet().stream().allMatch(pattern ->
                    graph.patternsFor(pattern.output()).contains(pattern)));
            assertTrue(restored.firings().keySet().stream().anyMatch(pattern -> !pattern.byproducts().isEmpty()));
        }
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> MaterialDagOrders.withAdditionalStock(templates,
                    Map.of("raw", 1L), "T", BoundedIntegerLinearSolver.WorkBudget.unlimited()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void portfolioTimeoutRetainsCompletedSupportsForTheNextReplenishmentProbe() throws Exception {
        var graph = CraftGraph.<String>builder().stock("raw", 4L)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).build();
        var complete = MaterialDagOrders.compile(graph, "T");
        assertTrue(complete.size() > 1);
        var field = PlanningCancellation.class.getDeclaredField("OPTIONAL_DEADLINE");
        field.setAccessible(true);
        var deadline = (ThreadLocal<Long>) field.get(null);
        int[] boundChecks = {0};
        boolean[] expired = {false};
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() {
                if (expired[0]) return;
                for (var frame : Thread.currentThread().getStackTrace()) {
                    if (frame.getClassName().equals(MaterialDagOrders.class.getName())
                            && frame.getMethodName().equals("optimisticCapacity") && ++boundChecks[0] == 5) {
                        expired[0] = true;
                        deadline.set(System.nanoTime() - 1L);
                        return;
                    }
                }
            }
        };
        List<MaterialDagOrders.Candidate<String>> partial;
        try (var bound = PlanningCancellation.bind(context);
             var optional = PlanningCancellation.limitOptionalWork(Long.MAX_VALUE / 4L)) {
            partial = MaterialDagOrders.compile(graph, "T");
        }
        assertTrue(expired[0]);
        assertFalse(partial.isEmpty(), "a completed support must survive expiration during the next support");
        assertTrue(partial.size() < complete.size());
        var refreshed = MaterialDagOrders.withAdditionalStock(partial, Map.of("raw", 1L), "T",
                BoundedIntegerLinearSolver.WorkBudget.unlimited());
        for (var candidate : refreshed) {
            assertTrue(candidate.maySupply(1));
            assertTrue(CraftPlannerV2.plan(candidate.graph(), "T", 1, 1, 1).feasible());
        }
        assertDoesNotThrow(PlanningCancellation::check);
    }

    /** Inspect/expire existing scope deadlines without sleeps or a production-only test hook. */
    private static final class RefinementDeadlineProbe implements PlanningAttemptContext {
        private final ThreadLocal<Long> optionalDeadline;
        private final PlanningExitException exit;
        private final boolean expire;
        private final StackWalker callers = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        long refinementDeadline, portfolioDeadline;
        boolean observedPortfolio, expiredRefinement;

        @SuppressWarnings("unchecked")
        RefinementDeadlineProbe(PlanningExitException exit, boolean expire) {
            this.exit = exit;
            this.expire = expire;
            try {
                var field = PlanningCancellation.class.getDeclaredField("OPTIONAL_DEADLINE");
                field.setAccessible(true);
                optionalDeadline = (ThreadLocal<Long>) field.get(null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        @Override public long deadlineNanos() { return Long.MAX_VALUE; }
        @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
        @Override public void checkpoint() {
            if (observedPortfolio || expiredRefinement) return;
            Long installed = optionalDeadline.get();
            if (installed == null) return;
            // Inspect only the checkpoint's immediate caller. Building a full stack at every
            // hot-loop checkpoint can itself exhaust the refinement's unchanged 100 ms slice.
            Class<?> caller = callers.walk(frames -> frames.skip(2).findFirst()
                    .map(StackWalker.StackFrame::getDeclaringClass).orElse(null));
            if (caller == MissingRefinement.class && refinementDeadline == 0L)
                refinementDeadline = installed;
            if (refinementDeadline == 0L) return;
            if (expire && caller == MissingRefinement.class) {
                expiredRefinement = true;
                optionalDeadline.set(System.nanoTime() - 1L);
            } else if (caller == MaterialDagOrders.class) {
                observedPortfolio = true;
                portfolioDeadline = optionalDeadline.get();
                if (exit != null) throw exit;
            }
        }
    }

    private static CraftGraph<String> batchCycle() {
        var b = stock(0, 4, 1, 1, 2, 2, 0);
        recipe(b, 3, 3, new int[] {0,1,1,1,4,1});
        recipe(b, 3, 1, new int[] {0,1});
        recipe(b, 1, 3, new int[] {0,1,3,1,4,1});
        recipe(b, 2, 1, new int[] {1,1,3,1});
        recipe(b, 3, 1, new int[] {1,2}, 5,1);
        recipe(b, 0, 1, new int[] {1,1});
        recipe(b, 4, 3, new int[] {0,1,3,1,5,1});
        recipe(b, 5, 1, new int[] {0,1});
        recipe(b, 3, 1, new int[] {0,1,4,1});
        recipe(b, 3, 3, new int[] {2,1,5,2});
        recipe(b, 2, 1, new int[] {0,1,1,1}, 4,1);
        recipe(b, 4, 1, new int[] {0,1,2,1,3,1}, 3,2);
        recipe(b, 1, 1, new int[] {3,1});
        recipe(b, 6, 1, new int[] {2,2});
        return b.build();
    }

    private static CraftGraph<String> sharedCycle() {
        var b = stock(4, 5, 0, 1, 0, 0, 0);
        recipe(b, 5, 1, new int[] {1,1,2,2});
        recipe(b, 2, 1, new int[] {0,1});
        recipe(b, 4, 1, new int[] {0,1,5,1}, 3,1);
        recipe(b, 4, 1, new int[] {0,1});
        recipe(b, 1, 1, new int[] {2,1,4,1}, 2,1);
        recipe(b, 2, 1, new int[] {1,1});
        recipe(b, 1, 1, new int[] {0,1,2,1,3,1});
        recipe(b, 2, 1, new int[] {0,1});
        recipe(b, 4, 3, new int[] {2,1,3,2});
        recipe(b, 0, 1, new int[] {1,1,5,1});
        recipe(b, 0, 1, new int[] {4,1});
        recipe(b, 3, 1, new int[] {0,1,1,1,5,1});
        recipe(b, 5, 2, new int[] {3,2});
        recipe(b, 6, 1, new int[] {3,1,5,1});
        return b.build();
    }

    private static CraftGraph.Builder<String> stock(int... amounts) {
        var b = CraftGraph.<String>builder();
        for (int i = 0; i < amounts.length; i++) b.stock("M"+i, amounts[i]);
        return b;
    }

    private static void recipe(CraftGraph.Builder<String> b, int output, int amount, int[] consumed, int... side) {
        var inputs = new ArrayList<CraftInput<String>>();
        var outputs = new ArrayList<CraftOutput<String>>();
        for (int i = 0; i < consumed.length; i+=2) inputs.add(CraftInput.of("M"+consumed[i], consumed[i+1]));
        for (int i = 0; i < side.length; i+=2) outputs.add(CraftOutput.of("M"+side[i], side[i+1]));
        b.pattern("M"+output, amount, inputs, outputs);
    }

    private static void assertBalance(CraftGraph<String> graph, CraftPlan<String> plan) {
        var balance = new HashMap<String, BigInteger>();
        plan.usedStock().forEach((key, value) -> {
            assertTrue(value <= graph.stock(key));
            balance.put(key, BigInteger.valueOf(value));
        });
        plan.firings().forEach((p, count) -> {
            var n = BigInteger.valueOf(count);
            p.inputs().forEach(i -> balance.merge(i.key(), i.exactAmount().multiply(n).negate(), BigInteger::add));
            balance.merge(p.output(), p.exactOutputAmount().multiply(n), BigInteger::add);
            p.byproducts().forEach(o -> balance.merge(o.key(), o.exactAmount().multiply(n), BigInteger::add));
        });
        assertTrue(balance.values().stream().allMatch(n -> n.signum() >= 0));
        assertTrue(balance.getOrDefault("M6", BigInteger.ZERO).compareTo(BigInteger.TWO) >= 0);
    }
}
