package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeasibleConsumptionOptimizerTest {
    @Test
    void indexCacheIsCalculationScopedAndCancellationDoesNotPublishPartialState() {
        var graph = batchGraph(1);
        var cache = new FeasibleConsumptionOptimizer.IndexCache<String>();
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> cache.get(graph, "T"));
        }
        var index = cache.get(graph, "T");
        assertNotNull(index);
        assertSame(index, cache.get(graph, "T"));
        assertNotSame(index, cache.get(graph.withStockLimits(Map.of("raw", 2L)), "T"));
        assertNotSame(index, cache.get(graph, "raw"));
        assertNotSame(index, cache.get(graph, "T"));
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> cache.get(graph, "T"));
        }
    }

    @Test
    void cachedIndexRecomputesQuantityAndStockPropagation() {
        var graph = batchGraph(1);
        var cache = new FeasibleConsumptionOptimizer.IndexCache<String>();
        for (long amount : new long[] {1, 2, 1}) {
            var initial = baseline(graph, "T", amount);
            var uncached = FeasibleConsumptionOptimizer.optimize(graph, "T", amount, initial,
                    32, candidate -> baseline(candidate, "T", amount));
            var cached = FeasibleConsumptionOptimizer.optimize(graph, "T", amount, initial,
                    32, candidate -> baseline(candidate, "T", amount), cache);
            assertEquals(uncached.plan().usedStock(), cached.plan().usedStock());
            assertEquals(executions(uncached.plan()), executions(cached.plan()));
            assertBalance(graph, cached.plan(), "T", amount);
        }
    }

    @Test
    void improvesAnAlreadyFeasibleBatchChoiceAtUnitAndTrillionScale() {
        for (long scale : new long[] {1, 1_000_000, 1_000_000_000_000L}) {
            var graph = batchGraph(scale);
            var initial = baseline(graph, "T", scale);
            assertEquals(10 * scale, initial.usedStock().get("raw"));
            var result = CraftPlannerV2.planDetailed(graph, "T", scale);
            assertTrue(result.plan().feasible());
            assertEquals(2 * scale, result.plan().usedStock().get("raw"));
            assertTrue(result.diagnostics().consumptionOptimizationImprovements() > 0);
            assertTrue(result.diagnostics().consumptionOptimizationProbes() <= 5);
            assertBalance(graph, result.plan(), "T", scale);
        }
    }

    @Test
    void equalMaterialsPreferFewerExecutions() {
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1))).build();
        var initial = baseline(graph, "T", 1);
        var result = CraftPlannerV2.planDetailed(graph, "T", 1);
        assertEquals(2, executions(initial));
        assertEquals(1, executions(result.plan()));
        assertEquals(initial.usedStock(), result.plan().usedStock());
        assertBalance(graph, result.plan(), "T", 1);
    }

    @Test
    void doesNotExchangeIronForDiamonds() {
        var graph = CraftGraph.<String>builder().stock("iron", 100).stock("diamond", 1)
                .pattern("T", 1, List.of(CraftInput.of("iron", 8)))
                .pattern("T", 1, List.of(CraftInput.of("diamond", 1))).build();
        var initial = baseline(graph, "T", 1);
        assertEquals(Map.of("iron", 8L), initial.usedStock());
        var result = CraftPlannerV2.plan(graph, "T", 1);
        assertEquals(initial.usedStock(), result.usedStock());
        assertBalance(graph, result, "T", 1);
    }

    @Test
    void reducingDrawsMustNotIncreaseNetConsumptionByDiscardingReturns() {
        var returning = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 3)),
                List.of(CraftOutput.of("A", 2)), null);
        var consuming = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 2)), null);
        assertFalse(FeasibleConsumptionOptimizer.improves(plan(returning, Map.of("A", 3L)),
                plan(consuming, Map.of("A", 2L))));
    }

    @Test
    void ordinaryBranchesCanImproveWithoutChangingDurabilityUse() {
        var graph = CraftGraph.<String>builder().stock("raw", 100).stock("tool", 1)
                .pattern("T", 1, List.of(CraftInput.of("P", 1), CraftInput.finiteUse("tool", 1, 5)))
                .pattern("P", 10, List.of(CraftInput.of("raw", 10)))
                .pattern("P", 1, List.of(CraftInput.of("raw", 2))).build();
        var initial = baseline(graph, "T", 1);
        var result = CraftPlannerV2.plan(graph, "T", 1);
        assertTrue(result.feasible());
        assertEquals(2, result.usedStock().get("raw"));
        assertEquals(initial.usedStock().get("tool"), result.usedStock().get("tool"));
        assertEquals(initial.firings().get(graph.patternsFor("T").get(0)),
                result.firings().get(graph.patternsFor("T").get(0)));
    }

    @Test
    void optionalTimeoutRetainsTheLatestCompletedWitness() {
        var graph = batchGraph(1);
        var initial = baseline(graph, "T", 1);
        var better = plan(graph.patternsFor("T").get(1), Map.of("raw", 2L));
        var calls = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32, candidate -> {
            if (calls.incrementAndGet() == 1) return better;
            try (var ignored = PlanningCancellation.limitOptionalWork(0)) { PlanningCancellation.check(); }
            throw new AssertionError();
        });
        assertSame(better, result.plan());
        assertEquals(1, result.improvements());
        assertEquals(2, result.probes());
    }

    @Test
    void optionalTimeoutBeforeTheFirstCandidateRetainsOriginalAndExternalCancelPropagates() {
        var graph = batchGraph(1);
        var initial = baseline(graph, "T", 1);
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertSame(initial, FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32,
                    candidate -> { throw new AssertionError(); }).plan());
        }
        assertThrows(CancellationException.class, () -> FeasibleConsumptionOptimizer.optimize(
                graph, "T", 1, initial, 32, candidate -> { throw new CancellationException("user"); }));
        assertThrows(com.moakiee.thunderbolt.api.crafting.PlanningExitException.class,
                () -> FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32,
                        candidate -> { throw new com.moakiee.thunderbolt.api.crafting.PlanningExitException("router"); }));
    }

    @Test
    void fixedChainsAndMissingPlansDoNotEnterFeasibleOptimization() {
        var chain = CraftGraph.<String>builder().stock("raw", 100)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1))).build();
        assertEquals(0, CraftPlannerV2.planDetailed(chain, "T", 1).diagnostics().consumptionOptimizationProbes());
        var missing = batchGraph(1).withStockLimits(Map.of());
        var result = CraftPlannerV2.planDetailed(missing, "T", 1);
        assertFalse(result.plan().feasible());
        assertEquals(0, result.diagnostics().consumptionOptimizationProbes());
    }

    @Test
    void quantityProbesShareTheOptionalProbeBudget() {
        var graph = batchGraph(1);
        var session = new CraftPlannerV2.PlanningSession<String>();
        int probes = 0;
        for (int i = 0; i < 20; i++) {
            var result = CraftPlannerV2.planDetailed(graph, "T", 1, session);
            assertTrue(result.plan().feasible());
            probes += result.diagnostics().consumptionOptimizationProbes();
        }
        assertTrue(probes > 0 && probes <= FeasibleConsumptionOptimizer.MAX_PROBES, () -> "probes exceeded");
    }

    @Test
    void wideGraphsStillOptimizeWhenOnlyTheLocalProbeFitsTheSearchBudget() {
        var builder = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)));
        // Unfunded alternatives enlarge the reachable graph, but cannot fire inside the
        // incumbent's stock limits. A useful probe only needs the direct raw -> T route.
        for (int i = 0; i < 6_000; i++) {
            builder.pattern("T", 1, List.of(CraftInput.of("unavailable" + i, 1)));
        }
        var graph = builder.build();
        assertTrue(CraftPlannerV2.reachableWorkEstimate(graph, "T") > 16_384);
        var initial = baseline(graph, "T", 1);
        assertEquals(2, executions(initial));
        var result = CraftPlannerV2.planDetailed(graph, "T", 1, CraftPlannerV2.DEFAULT_VISIT_CAP, 256);
        assertTrue(result.plan().feasible(), result::toString);
        assertEquals(1, executions(result.plan()));
        assertEquals(initial.usedStock(), result.plan().usedStock());
        assertTrue(result.diagnostics().consumptionOptimizationProbes() > 0);
        assertBalance(graph, result.plan(), "T", 1);
    }

    @Test
    void insufficientSearchBudgetRetainsTheCertifiedIncumbent() {
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1))).build();
        var result = CraftPlannerV2.planDetailed(graph, "T", 1, CraftPlannerV2.DEFAULT_VISIT_CAP, 1);
        assertTrue(result.plan().feasible());
        assertEquals(2, executions(result.plan()));
        assertEquals(0, result.diagnostics().consumptionOptimizationImprovements());
        assertBalance(graph, result.plan(), "T", 1);
    }

    @Test
    void nearOuterDeadlineSkipsOptimizationAndReturnsInitial() {
        var graph = batchGraph(1);
        var context = new com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext() {
            public long deadlineNanos() { return System.nanoTime() + 1_000_000L; }
            public void checkpoint() {}
            public void report(com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot ignored) {}
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            var result = CraftPlannerV2.planDetailed(graph, "T", 1);
            assertTrue(result.plan().feasible());
            assertEquals(0, result.diagnostics().consumptionOptimizationProbes());
        }
    }

    @Test
    void saturatedDisplayAmountsAreNotTreatedAsExecutableOptimizationBounds() {
        var graph = batchGraph(1);
        var initial = plan(graph.patternsFor("T").get(0), Map.of("raw", Sat.SAT));
        assertSame(initial, FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32,
                candidate -> { throw new AssertionError("display-only amount was probed"); }).plan());
    }

    @Test
    void randomBatchAlternativesAgreeWithIndependentMinimumAndNeverLoseFeasibility() {
        var random = new Random(180926);
        int improved = 0, fewestFound = 0, optimal = 0;
        for (int sample = 0; sample < 256; sample++) {
            int n = 1 + random.nextInt(8);
            int aOut = 1 + random.nextInt(8), bOut = 1 + random.nextInt(8);
            int aCost = 1 + random.nextInt(8), bCost = 1 + random.nextInt(8);
            var graph = CraftGraph.<String>builder().stock("raw", 128)
                    .pattern("T", aOut, List.of(CraftInput.of("raw", aCost)))
                    .pattern("T", bOut, List.of(CraftInput.of("raw", bCost))).build();
            var initial = baseline(graph, "T", n);
            var optimized = CraftPlannerV2.plan(graph, "T", n);
            assertTrue(optimized.feasible());
            long limit = initial.usedStock().get("raw"), raw = optimized.usedStock().get("raw");
            long executions = executions(optimized), before = executions(initial);
            // Fewer executions first, then less raw. Probes never draw more than the incumbent.
            assertTrue(raw <= limit, "sample="+sample);
            assertTrue(executions < before || executions == before && raw <= limit, "sample="+sample);
            long fewest = Long.MAX_VALUE, optimum = Long.MAX_VALUE;
            for (int a = 0; a <= n; a++) for (int b = 0; b <= n; b++) {
                long cost = a*aCost+b*bCost;
                if (a*aOut+b*bOut < n || cost > limit) continue;
                if (a+b < fewest || a+b == fewest && cost < optimum) { fewest = a+b; optimum = cost; }
            }
            if (executions == fewest) fewestFound++;
            if (executions == fewest && raw == optimum) optimal++;
            if (executions < before || raw < limit) improved++;
            assertBalance(graph, optimized, "T", n);
        }
        assertTrue(improved > 20, "the corpus must exercise real optimization");
        // Probes withdraw one route at a time or pin one pattern per output, so a few mixed
        // allocations remain out of reach. Stock has no probes of its own, only the tie-break.
        assertTrue(fewestFound >= 253, "fewestFound=" + fewestFound);
        assertTrue(optimal >= 249, "optimal=" + optimal);
    }

    private static CraftGraph<String> batchGraph(long scale) {
        return CraftGraph.<String>builder().stock("raw", 100 * scale)
                .pattern("T", 10 * scale, List.of(CraftInput.of("raw", 10 * scale)))
                .pattern("T", scale, List.of(CraftInput.of("raw", 2 * scale))).build();
    }

    private static CraftPlan<String> baseline(CraftGraph<String> graph, String target, long amount) {
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        return CraftPlannerV2.planDetailed(graph, target, amount, session).plan();
    }

    private static CraftPlan<String> plan(CraftPattern<String> p, Map<String, Long> stock) {
        return new CraftPlan<>(true, true, Map.of(p, 1L), stock, Map.of(), Map.of(), Map.of(), 0, false);
    }

    private static long executions(CraftPlan<?> plan) { return plan.firings().values().stream().mapToLong(n -> n).sum(); }

    private static void assertBalance(CraftGraph<String> graph, CraftPlan<String> plan, String target, long amount) {
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
        assertTrue(balance.getOrDefault(target, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
