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
        var initial = sharedReplenishment(graph);
        var work = BoundedIntegerLinearSolver.WorkBudget.bounded(65_536, 1_000_000, Long.MAX_VALUE);
        // Compile the bounded topology portfolio outside optional discovery. This tests real
        // refinement and certified recipes without requiring its 100 ms wrapper to finish.
        var templates = MaterialDagOrders.compile(graph, "M6", work);
        assertFalse(templates.isEmpty());
        var probes = new ArrayList<Map<String, Long>>();
        long[] improvements = {0};
        var refined = MissingRefinement.refine(graph, initial, List.of("M2"), plan -> false,
                supplied -> {
                    probes.add(supplied);
                    assertTrue(probes.size() <= CraftPlannerV2.MAX_MISSING_REFINEMENT_PROBES);
                    return certifiedSharedReplenishment(graph, initial, templates, supplied, work);
                }, count -> improvements[0] += count);
        assertEquals(Map.of("M2", 2L), refined.missing());
        assertTrue(improvements[0] > 0);
        ReplenishmentContractTest.assertExactBalance(graph, "M6", 2, refined);
        var supplied = graph.withAdditionalStock(refined.missing());
        var ready = CraftPlannerV2.planDetailed(supplied, "M6", 2, 1, 1);
        assertTrue(ready.plan().feasible());
        assertBalance(supplied, ready.plan());
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void boundedSharedCycleKeepsAnExecutableSupplement() {
        var graph = sharedCycle();
        var result = CraftPlannerV2.planDetailed(graph, "M6", 2);
        assertTrue(result.plan().missing().keySet().stream().allMatch("M2"::equals));
        // Optional quality may stop before its first improvement; validity remains mandatory.
        assertTrue(result.plan().missing().getOrDefault("M2", 0L) <= 6,
                () -> result.plan().missing().toString());
        assertTrue(result.diagnostics().missingRefinementProbes() <= CraftPlannerV2.MAX_MISSING_REFINEMENT_PROBES);
        ReplenishmentContractTest.assertExactBalance(graph, "M6", 2, result.plan());
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
    void replenishmentPortfolioUsesTheExistingRefinementDeadlineWithoutQuarteringItAgain() throws Exception {
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.refineMissing = false;
        // Check the actual session allowance used by tryMaterialDagOrders, independently of
        // cold class loading, scheduling and a StackWalker observer spending the 100 ms slice.
        assertEquals(100_000_000L, session.remainingMaterialDagNanos(100_000_000L));
        assertEquals(75_000_000L, session.remainingMaterialDagNanos(75_000_000L),
                "a replenishment probe must keep its parent's remaining time");
        assertEquals(100_000_000L, session.remainingMaterialDagNanos(Long.MAX_VALUE),
                "the portfolio must still respect its cumulative 100 ms cap");
        var spent = session.getClass().getDeclaredField("materialDagOrderNanos");
        spent.setAccessible(true);
        spent.setLong(session, 60_000_000L);
        assertEquals(40_000_000L, session.remainingMaterialDagNanos(75_000_000L));
        assertEquals(20_000_000L, session.remainingMaterialDagNanos(20_000_000L));
        session.refineMissing = true;
        assertEquals(18_750_000L, session.remainingMaterialDagNanos(75_000_000L),
                "ordinary discovery must continue reserving its caller's adaptation time");
        spent.setLong(session, 100_000_000L);
        assertEquals(0L, session.remainingMaterialDagNanos(75_000_000L));
    }

    @Test
    void replenishmentPortfolioStillPropagatesOuterCancellation() {
        var exit = new PlanningExitException("test cancellation during replenishment proof");
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("raw", 2))).build();
        var initial = CraftPlannerV2.plan(graph, "T", 1);
        boolean[] inPortfolio = {false};
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) { }
            @Override public void checkpoint() { if (inPortfolio[0]) throw exit; }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> MissingRefinement.refine(graph, initial, List.of("raw"), plan -> false,
                            supplied -> {
                                inPortfolio[0] = true;
                                MaterialDagOrders.compile(graph.withAdditionalStock(supplied), "T");
                                fail("the replenishment portfolio must propagate the caller's cancellation");
                                return null;
                            }, count -> { })));
        }
        assertTrue(inPortfolio[0]);
        assertDoesNotThrow(PlanningCancellation::check, "the caller scope must be restored after cancellation");
    }

    @Test
    void anExpiredRefinementDeadlineKeepsTheLastExecutableSupplement() {
        var graph = sharedCycle();
        var context = new RefinementExpiryProbe();
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
    void anExpiredRefinementAfterAnImprovementKeepsItsCertifiedSupplement() {
        var graph = sharedCycle();
        var initial = sharedReplenishment(graph);
        var work = BoundedIntegerLinearSolver.WorkBudget.bounded(65_536, 1_000_000, Long.MAX_VALUE);
        var templates = MaterialDagOrders.compile(graph, "M6", work);
        long[] improvements = {0};
        var result = MissingRefinement.refine(graph, initial, List.of("M2"), plan -> false,
                supplied -> certifiedSharedReplenishment(graph, initial, templates, supplied, work),
                count -> {
                    improvements[0] += count;
                    try (var ignored = PlanningCancellation.limitOptionalWork(0L)) {
                        PlanningCancellation.check();
                    }
                });
        assertEquals(1L, improvements[0]);
        assertEquals(Map.of("M2", 5L), result.missing());
        ReplenishmentContractTest.assertExactBalance(graph, "M6", 2, result);
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
    private static final class RefinementExpiryProbe implements PlanningAttemptContext {
        private final ThreadLocal<Long> optionalDeadline;
        private final StackWalker callers = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        private Long lastDeadline;
        boolean expiredRefinement;

        @SuppressWarnings("unchecked")
        RefinementExpiryProbe() {
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
            if (expiredRefinement) return;
            Long installed = optionalDeadline.get();
            if (java.util.Objects.equals(installed, lastDeadline)) return;
            lastDeadline = installed;
            if (installed == null) return;
            // Inspect only entry to a new optional scope, never every hot-loop checkpoint.
            Class<?> caller = callers.walk(frames -> frames.skip(2).findFirst()
                    .map(StackWalker.StackFrame::getDeclaringClass).orElse(null));
            if (caller == MissingRefinement.class) {
                expiredRefinement = true;
                optionalDeadline.set(System.nanoTime() - 1L);
            }
        }
    }

    /** The existing conservative M2=6 vector, certified against its exact supplement. */
    private static CraftPlan<String> sharedReplenishment(CraftGraph<String> graph) {
        var baseline = MaterialDagReplay.tryPlan(graph.withAdditionalStock(Map.of("M2", 6L)),
                Map.of(graph.patternsFor("M5").get(0), 3L,
                        graph.patternsFor("M3").get(0), 1L, graph.patternsFor("M6").get(0), 2L), "M6", 2);
        assertNotNull(baseline);
        var used = new HashMap<>(baseline.usedStock());
        assertEquals(6L, used.remove("M2"));
        var initial = new CraftPlan<>(true, false, baseline.firings(), Map.copyOf(used), Map.of(),
                Map.of("M2", 6L), baseline.grossDemand(), baseline.itemsProcessed(), false);
        ReplenishmentContractTest.assertExactBalance(graph, "M6", 2, initial);
        return initial;
    }

    /** Real stock refresh, minimal DAG planning and restoration to the original recipe identities. */
    private static CraftPlan<String> certifiedSharedReplenishment(CraftGraph<String> graph,
            CraftPlan<String> rejected, List<MaterialDagOrders.Candidate<String>> templates,
            Map<String, Long> supplied, BoundedIntegerLinearSolver.WorkBudget work) {
        var replenished = graph.withAdditionalStock(supplied);
        for (var order : MaterialDagOrders.withAdditionalStock(templates, supplied, "M6", work)) {
            if (!order.maySupply(2)) continue;
            var ready = CraftPlannerV2.plan(order.graph(), "M6", 2, 1, 1);
            if (!ready.feasible()) continue;
            ready = order.restore(ready, replenished, "M6", 2);
            if (!MaterialDagReplay.hasCertificate(replenished, ready, "M6", 2)) continue;
            assertBalance(replenished, ready);
            ByproductReplaySafetyTest.assertEveryOrderFinishes(ready, "M6", 2);
            return ready;
        }
        return rejected;
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
