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
    void primaryCertificatesScaleAndSurviveReversedRecipeRegistration() {
        for (long scale : new long[] {1L, 50L, 500_000_000_000L}) {
            for (boolean reverse : new boolean[] {false, true}) {
                var original = batchCycle();
                var builder = CraftGraph.<String>builder();
                for (int i = 0; i <= 6; i++) {
                    String key = "M" + i;
                    builder.stock(key, original.stock(key) * scale + (i == 3 ? scale : 0L));
                    var routes = new ArrayList<>(original.patternsFor(key));
                    if (reverse) java.util.Collections.reverse(routes);
                    routes.forEach(builder::pattern);
                }
                var graph = builder.build();
                var ready = CraftPlannerV2.planDetailed(graph, "M6", 2 * scale, 1, 1);
                assertTrue(ready.plan().feasible(), () -> "scale=" + scale + " reverse=" + reverse
                        + " result=" + ready);
                assertBalance(graph, ready.plan(), 2 * scale);
                assertTrue(ready.diagnostics().consumedSearchBudget() <= 1);
            }
        }
    }

    @Test
    void primaryCertificatesSurviveAnExhaustedOptionalPortfolioInAFreshSession() throws Exception {
        for (int round = 0; round < 3; round++) {
            for (var graph : List.of(batchCycle().withAdditionalStock(Map.of("M3", 1L)),
                    sharedCycle().withAdditionalStock(Map.of("M2", 2L)))) {
                var session = new CraftPlannerV2.PlanningSession<String>();
                session.optimizeFeasible = false;
                session.refineMissing = false;
                var time = session.getClass().getDeclaredField("materialDagOrderNanos");
                var attempts = session.getClass().getDeclaredField("materialDagOrderAttempts");
                time.setAccessible(true);
                attempts.setAccessible(true);
                time.setLong(session, 100_000_000L);
                attempts.setInt(session, 120);
                var result = CraftPlannerV2.planCore(graph, "M6", 2, 1, 1,
                        session.reachableWork(graph, "M6"), session);
                assertTrue(result.plan().feasible(), () -> result.toString());
                assertBalance(graph, result.plan());
                ByproductReplaySafetyTest.assertEveryOrderFinishes(result.plan(), "M6", 2);
                assertTrue(result.diagnostics().consumedSearchBudget() <= 1);
                assertEquals(100_000_000L, time.getLong(session));
                assertEquals(120, attempts.getInt(session));
                result.plan().firings().keySet().forEach(pattern ->
                        assertTrue(graph.patternsFor(pattern.output()).contains(pattern)));
            }
        }
    }

    @Test
    void coreCertificateEnumerationChargesSharedWorkAndHonorsTheParentDeadline() throws Exception {
        var graph = batchCycle().withAdditionalStock(Map.of("M3", 1L));
        var denied = MaterialDagOrders.compileCertificates(graph, "M6",
                BoundedIntegerLinearSolver.WorkBudget.bounded(1, 1, Long.MAX_VALUE),
                candidate -> false, List.of());
        assertFalse(denied.complete());
        assertTrue(denied.candidates().isEmpty());
        var field = PlanningCancellation.class.getDeclaredField("OPTIONAL_DEADLINE");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var deadline = (ThreadLocal<Long>) field.get(null);
        try (var parent = PlanningCancellation.limitOptionalWork(Long.MAX_VALUE / 4L)) {
            long expired = System.nanoTime() - 1L;
            deadline.set(expired);
            var stopped = MaterialDagOrders.compileCertificates(graph, "M6",
                    BoundedIntegerLinearSolver.WorkBudget.unlimited(), candidate -> false, List.of());
            assertFalse(stopped.complete());
            assertTrue(stopped.candidates().isEmpty());
            assertEquals(expired, deadline.get(), "core certification must not extend the parent's cutoff");
        }
        assertDoesNotThrow(PlanningCancellation::check);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> MaterialDagOrders.compileCertificates(
                    graph, "M6", BoundedIntegerLinearSolver.WorkBudget.unlimited(), candidate -> false, List.of()));
        } finally {
            Thread.interrupted();
        }
        var exit = new PlanningExitException("outer cancellation during core certification");
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() { throw exit; }
        };
        try (var bound = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class, () -> MaterialDagOrders.compileCertificates(
                    graph, "M6", BoundedIntegerLinearSolver.WorkBudget.unlimited(), candidate -> false, List.of())));
        }
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void cheapDagProposalsCanSplitRoutesWithoutSpendingAnotherBranchsStock() {
        var graph = CraftGraph.<String>builder().stock("A", 3).stock("B", 2)
                .pattern("P", 1, List.of(CraftInput.of("A", 1)))
                .pattern("P", 1, List.of(CraftInput.of("B", 1)))
                .pattern("T", 1, List.of(CraftInput.of("P", 4), CraftInput.of("A", 1))).build();
        var candidates = MaterialDagOrders.compile(graph, "T");
        var plan = candidates.get(0).tryFixedPlan("T", 1);
        assertNotNull(plan);
        assertEquals(Map.of("A", 3L, "B", 2L), plan.usedStock());
        assertEquals(2L, plan.firings().get(graph.patternsFor("P").get(0)));
        assertEquals(2L, plan.firings().get(graph.patternsFor("P").get(1)));
        var shortGraph = graph.withStockLimits(Map.of("A", 2L, "B", 2L));
        assertTrue(MaterialDagOrders.compile(shortGraph, "T").stream()
                .allMatch(candidate -> candidate.tryFixedPlan("T", 1) == null));
    }

    @Test
    void aPartialBatchUsesItsActualDrawBeforeLaunchingAWholePlannerProbe() {
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 3, List.of(CraftInput.of("raw", 3)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1))).build();
        var candidates = MaterialDagOrders.compile(graph, "T");
        assertFalse(candidates.isEmpty());
        var plan = candidates.get(0).tryFixedPlan("T", 1);
        assertNotNull(plan, "equal unit costs do not justify withdrawing an unaffordable whole batch");
        assertEquals(Map.of("raw", 1L), plan.usedStock());
        assertEquals(Map.of(graph.patternsFor("T").get(1), 1L), plan.firings());
        assertTrue(plan.feasible());
    }

    @Test
    void anExhaustedSharedBudgetCannotRecursivelyRecompileTheSamePrefix() throws Exception {
        var graph = batchCycle();
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        session.refineMissing = false;
        CraftPlannerV2.planDetailed(graph, "M6", 0, session);
        session.lowWidthWorkBudget = BoundedIntegerLinearSolver.WorkBudget.bounded(1, 1, Long.MAX_VALUE);
        assertFalse(session.lowWidthWorkBudget.tryConsume(2));
        var cached = session.getClass().getDeclaredField("materialDagOrders");
        cached.setAccessible(true);
        cached.set(session, List.of());
        int[] maximumDepth = {0};
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() {
                int depth = StackWalker.getInstance().walk(frames -> (int) frames.filter(frame ->
                        frame.getClassName().equals(CraftPlannerV2.class.getName())
                                && frame.getMethodName().equals("tryMaterialDagOrders")).count());
                maximumDepth[0] = Math.max(maximumDepth[0], depth);
                assertTrue(depth <= 2, "work denial cannot trigger repeated recursive compilation");
            }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            var result = CraftPlannerV2.planDetailed(graph, "M6", 2, session);
            assertTrue(result.plan().supported());
            assertFalse(result.plan().feasible());
        }
        assertEquals(2, maximumDepth[0], "a reused incomplete prefix receives one stock-local retry");
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void completedSupportsAreTriedBeforeTheRestOfThePortfolioIsCompiled() {
        var graph = CraftGraph.<String>builder().stock("raw", 4L)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).build();
        var fullPortfolio = MaterialDagOrders.compilePortfolio(graph, "T",
                BoundedIntegerLinearSolver.WorkBudget.unlimited(), candidate -> false, List.of());
        assertTrue(fullPortfolio.complete());
        var complete = fullPortfolio.candidates();
        var certified = new ArrayList<CraftPlan<String>>();
        var prefix = MaterialDagOrders.compile(graph, "T", BoundedIntegerLinearSolver.WorkBudget.unlimited(), order -> {
            if (!order.maySupply(1)) return false;
            var plan = CraftPlannerV2.plan(order.graph(), "T", 1, 1, 1);
            if (!plan.feasible()) return false;
            certified.add(order.restore(plan));
            return true;
        });
        assertEquals(1, certified.size());
        assertTrue(prefix.size() < complete.size(), "unused permutations must not delay an available proof");
        assertEquals(Map.of("raw", 2L), certified.get(0).usedStock());
    }

    @Test
    void freshRecipeIdentitiesKeepBothCycleSupplementsReproducible() {
        for (int round = 0; round < 6; round++) {
            for (var graph : List.of(batchCycle(), sharedCycle())) {
                var plan = CraftPlannerV2.plan(graph, "M6", 2);
                assertFalse(plan.missing().isEmpty());
                var supplied = graph.withAdditionalStock(plan.missing());
                var ready = CraftPlannerV2.planDetailed(supplied, "M6", 2, 1, 1);
                assertTrue(ready.plan().feasible(), () -> "missing=" + plan.missing()
                        + " diagnostics=" + ready.diagnostics());
                assertBalance(supplied, ready.plan());
            }
        }
    }

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
    @SuppressWarnings("unchecked")
    void replenishmentPortfolioUsesTheExistingRefinementDeadlineWithoutQuarteringItAgain() throws Exception {
        var field = PlanningCancellation.class.getDeclaredField("OPTIONAL_DEADLINE");
        field.setAccessible(true);
        var deadline = (ThreadLocal<Long>) field.get(null);
        try (var refinement = PlanningCancellation.limitOptionalWork(100_000_000L)) {
            long parentDeadline = deadline.get();
            try (var portfolio = MaterialDagOrders.limitWork(0L, true)) {
                assertNotNull(portfolio);
                assertEquals(parentDeadline, deadline.get(), "replenishment retains the exact parent cutoff");
            }
            assertEquals(parentDeadline, deadline.get());
            try (var ordinary = MaterialDagOrders.limitWork(0L, false)) {
                assertNotNull(ordinary);
                assertTrue(deadline.get() < parentDeadline, "ordinary planning reserves caller time");
            }
            assertEquals(parentDeadline, deadline.get());
            assertNull(MaterialDagOrders.limitWork(100_000_000L, true), "the cumulative cap still applies");
        }
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void replenishmentPortfolioStillPropagatesOuterCancellation() {
        var exit = new PlanningExitException("test cancellation during replenishment proof");
        var graph = sharedCycle().withAdditionalStock(Map.of("M2", 6L));
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() { checks.incrementAndGet(); throw exit; }
        };
        try (var refinement = PlanningCancellation.limitOptionalWork(100_000_000L);
             var portfolio = MaterialDagOrders.limitWork(0L, true);
             var ignored = PlanningCancellation.bind(context)) {
            assertNotNull(portfolio);
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> MaterialDagOrders.compile(graph, "M6")));
        }
        assertTrue(checks.get() > 0);
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
        MaterialDagOrders.Portfolio<String> partialPortfolio;
        try (var bound = PlanningCancellation.bind(context);
             var optional = PlanningCancellation.limitOptionalWork(Long.MAX_VALUE / 4L)) {
            partialPortfolio = MaterialDagOrders.compilePortfolio(graph, "T",
                    BoundedIntegerLinearSolver.WorkBudget.unlimited(), candidate -> false, List.of());
        }
        var partial = partialPortfolio.candidates();
        assertFalse(partialPortfolio.complete(), "a timed out prefix must remain eligible for stock-local discovery");
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
            if (observedPortfolio || expiredRefinement || optionalDeadline.get() == null) return;
            boolean refinement = false, portfolio = false;
            for (var frame : Thread.currentThread().getStackTrace()) {
                refinement |= frame.getClassName().equals(MissingRefinement.class.getName())
                        && frame.getMethodName().equals("refine");
                portfolio |= frame.getClassName().equals(MaterialDagOrders.class.getName());
            }
            if (!refinement) return;
            if (refinementDeadline == 0L) refinementDeadline = optionalDeadline.get();
            if (expire) {
                expiredRefinement = true;
                optionalDeadline.set(System.nanoTime() - 1L);
            } else if (portfolio) {
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
        assertBalance(graph, plan, 2L);
    }

    private static void assertBalance(CraftGraph<String> graph, CraftPlan<String> plan, long amount) {
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
        assertTrue(balance.getOrDefault("M6", BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
