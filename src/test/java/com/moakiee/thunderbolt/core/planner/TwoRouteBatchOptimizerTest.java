package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TwoRouteBatchOptimizerTest {
    @Test
    void moreAvailableStockMayReplaceTheEqualStockMixWithThreeExecutions() {
        var graph = graph(8, 12, 2, 3).withStockLimits(Map.of("raw", 36L));
        var result = CraftPlannerV2.planDetailed(graph, "T", 19).plan();
        assertEquals(3, executions(result));
        assertEquals(Map.of("raw", 36L), result.usedStock());
        assertEquals(Map.of(graph.patternsFor("T").get(0), 3L), result.firings());
        balance(graph, result, 19);
    }

    @Test
    void mixedBatchesReplaceTenExecutionsWithFourAtEqualStock() {
        for (long scale : new long[] {1, 1_000_000_000_000L}) {
            var graph = graph(8 * scale, 12 * scale, 2 * scale, 3 * scale)
                    .withStockLimits(Map.of("raw", 30 * scale));
            var result = CraftPlannerV2.planDetailed(graph, "T", 19 * scale);
            assertEquals(4, executions(result.plan()));
            assertEquals(30 * scale, result.plan().usedStock().get("raw"));
            assertEquals(2, result.plan().firings().get(graph.patternsFor("T").get(0)));
            assertEquals(2, result.plan().firings().get(graph.patternsFor("T").get(1)));
            balance(graph, result.plan(), 19 * scale);
        }
    }

    @Test
    void independentEnumerationAgreesWithExecutionFirstStockLimitedMinimum() {
        var random = new Random(20261004);
        for (int sample = 0; sample < 3000; sample++) {
            int amount = 1 + random.nextInt(20);
            int ao = 1 + random.nextInt(20), bo = 1 + random.nextInt(20);
            int ac = 1 + random.nextInt(20), bc = 1 + random.nextInt(20);
            var graph = graph(ao, ac, bo, bc);
            var initial = baseline(graph, amount);
            long cap = initial.usedStock().get("raw"), bestCount = Long.MAX_VALUE, bestCost = Long.MAX_VALUE;
            for (int a = 0; a <= amount; a++) for (int b = 0; b <= amount; b++) {
                long count = a + b, cost = a * ac + b * bc;
                if (a * ao + b * bo < amount || cost > cap) continue;
                if (count < bestCount || count == bestCount && cost < bestCost) {
                    bestCount = count;
                    bestCost = cost;
                }
            }
            var solved = TwoRouteBatchOptimizer.solve(graph, "T", amount, initial, n -> true);
            assertEquals(BatchOptimizationResult.Status.COMPLETE, solved.status());
            var plan = solved.plan();
            assertEquals(bestCount, executions(plan), "sample=" + sample);
            assertEquals(bestCost, plan.usedStock().get("raw"), "sample=" + sample);
            balance(graph, plan, amount);
        }
    }

    @Test
    void targetInventoryIsNotSpentTwiceAndInputSlotsAreMerged() {
        var graph = CraftGraph.<String>builder().stock("raw", 1000).stock("T", 3)
                .pattern("T", 8, List.of(CraftInput.of("raw", 5), CraftInput.of("raw", 7)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 3))).build();
        var initial = new CraftPlan<>(true, true, Map.of(graph.patternsFor("T").get(1), 10L),
                Map.of("T", 3L, "raw", 30L), Map.of(), Map.of(), Map.of("T", 22L, "raw", 30L), 2, false);
        var plan = TwoRouteBatchOptimizer.tryImprove(graph, "T", 22, initial, work -> true);
        assertNotNull(plan);
        assertEquals(3, plan.usedStock().get("T"));
        assertEquals(4, executions(plan));
        assertEquals(30, plan.usedStock().get("raw"));
        balance(graph, plan, 22);
    }

    @Test
    void hugeUnscaledCountsHaveConstantWorkAndExactCertificates() {
        var graph = graph(8, 12, 2, 3);
        long amount = 1_000_000_000_019L;
        long oldCount = (amount + 1) / 2;
        var initial = new CraftPlan<>(true, true, Map.of(graph.patternsFor("T").get(1), oldCount),
                Map.of("raw", 3 * oldCount), Map.of(), Map.of(), Map.of("T", amount), 2, false);
        var work = new AtomicInteger();
        var plan = TwoRouteBatchOptimizer.tryImprove(graph, "T", amount, initial, n -> {
            work.addAndGet(n);
            return true;
        });
        assertTrue(work.get() <= TwoRouteBatchOptimizer.MAX_WORK);
        assertNotNull(plan);
        assertEquals(125_000_000_004L, executions(plan));
        assertEquals(initial.usedStock(), plan.usedStock());
        balance(graph, plan, amount);
    }

    @Test
    void exhaustedWorkAndOptionalTimeoutKeepTheCertifiedIncumbent() {
        var graph = graph(8, 12, 2, 3);
        var initial = baseline(graph, 19);
        assertNull(TwoRouteBatchOptimizer.tryImprove(graph, "T", 19, initial, work -> false));
        var calls = new AtomicInteger();
        var partial = TwoRouteBatchOptimizer.tryImprove(graph, "T", 19, initial,
                work -> calls.incrementAndGet() <= 3);
        if (partial != null) balance(graph, partial, 19);
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class,
                    () -> TwoRouteBatchOptimizer.tryImprove(graph, "T", 19, initial, work -> true));
        }
        assertThrows(CancellationException.class,
                () -> TwoRouteBatchOptimizer.tryImprove(graph, "T", 19, initial,
                        work -> { throw new CancellationException("user"); }));
    }

    @Test
    void aLargeIntegralityGapDeclinesWithinTheFixedWorkBudget() {
        var graph = graph(1_000_000_000_000L, 10001, 1, 1);
        var initial = new CraftPlan<>(true, true, Map.of(graph.patternsFor("T").get(1), 10000L),
                Map.of("raw", 10000L), Map.of(), Map.of(), Map.of("T", 10000L), 2, false);
        var work = new AtomicInteger();
        assertNull(TwoRouteBatchOptimizer.tryImprove(graph, "T", 10000, initial, n -> {
            work.addAndGet(n);
            return true;
        }));
        assertTrue(work.get() <= TwoRouteBatchOptimizer.MAX_WORK);
        assertTrue(work.get() > 1000, "the case must exercise bounded integer-count search");
    }

    @Test
    void multipleRawResourcesAndEqualOutputCountsMatchIndependentOracle() {
        var random = new Random(721);
        for (int sample = 0; sample < 1000; sample++) {
            int amount = 1 + random.nextInt(25);
            int ao = 1 + random.nextInt(15), bo = 1 + random.nextInt(15);
            int ai = 1 + random.nextInt(15), bi = 1 + random.nextInt(15);
            int ad = random.nextInt(15), bd = random.nextInt(15);
            var aInputs = new java.util.ArrayList<>(List.of(CraftInput.of("raw", ai)));
            var bInputs = new java.util.ArrayList<>(List.of(CraftInput.of("raw", bi)));
            if (ad > 0) aInputs.add(CraftInput.of("diamond", ad));
            if (bd > 0) bInputs.add(CraftInput.of("diamond", bd));
            var graph = CraftGraph.<String>builder().stock("raw", 10000).stock("diamond", 10000)
                    .pattern("T", ao, aInputs).pattern("T", bo, bInputs).build();
            var initial = baseline(graph, amount);
            long rawCap = initial.usedStock().getOrDefault("raw", 0L);
            long diamondCap = initial.usedStock().getOrDefault("diamond", 0L);
            long bestCount = Long.MAX_VALUE, bestStock = Long.MAX_VALUE;
            for (int a = 0; a <= amount; a++) for (int b = 0; b <= amount; b++) {
                long raw = a * ai + b * bi, diamond = a * ad + b * bd;
                if (a * ao + b * bo < amount || raw > rawCap || diamond > diamondCap) continue;
                if (a + b < bestCount || a + b == bestCount && raw + diamond < bestStock) {
                    bestCount = a + b;
                    bestStock = raw + diamond;
                }
            }
            var candidate = TwoRouteBatchOptimizer.tryImprove(graph, "T", amount, initial, n -> true);
            var result = candidate == null ? initial : candidate;
            assertEquals(bestCount, executions(result), "sample=" + sample);
            assertEquals(bestStock, result.usedStock().values().stream().mapToLong(n -> n).sum(), "sample=" + sample);
            assertTrue(result.usedStock().getOrDefault("raw", 0L) <= rawCap);
            assertTrue(result.usedStock().getOrDefault("diamond", 0L) <= diamondCap);
        }
    }

    @Test
    void zeroInputRouteAndAlreadyOptimalPlanDoNotCreateFalseImprovements() {
        var graph = CraftGraph.<String>builder().stock("raw", 100)
                .pattern("T", 8, List.of())
                .pattern("T", 2, List.of(CraftInput.of("raw", 3))).build();
        var initial = baseline(graph, 19);
        assertEquals(3, executions(initial));
        assertNull(TwoRouteBatchOptimizer.tryImprove(graph, "T", 19, initial, n -> true));
        var saturated = new CraftPlan<>(true, true, initial.firings(), Map.of("raw", Sat.SAT),
                Map.of(), Map.of(), Map.of("T", 19L), 2, false);
        assertNull(TwoRouteBatchOptimizer.tryImprove(graph, "T", 19, saturated, n -> true));
    }

    @Test
    void byproductsStatefulInputsAndIntermediateRecipesDecline() {
        var byproducts = CraftGraph.<String>builder().stock("raw", 100)
                .pattern("T", 8, List.of(CraftInput.of("raw", 12)), List.of(CraftOutput.of("raw", 1)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 3))).build();
        assertNull(TwoRouteBatchOptimizer.tryImprove(byproducts, "T", 19,
                baseline(byproducts, 19), work -> true));
        var stateful = CraftGraph.<String>builder().stock("raw", 100).stock("tool", 1)
                .pattern("T", 8, List.of(CraftInput.of("raw", 12), CraftInput.finiteUse("tool", 1, 5)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 3))).build();
        assertNull(TwoRouteBatchOptimizer.tryImprove(stateful, "T", 19,
                baseline(stateful, 19), work -> true));
        var intermediate = CraftGraph.<String>builder().stock("source", 100)
                .pattern("raw", 1, List.of(CraftInput.of("source", 1)))
                .pattern("T", 8, List.of(CraftInput.of("raw", 12)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 3))).build();
        assertNull(TwoRouteBatchOptimizer.tryImprove(intermediate, "T", 19,
                baseline(intermediate, 19), work -> true));
    }

    private static CraftGraph<String> graph(long ao, long ac, long bo, long bc) {
        return CraftGraph.<String>builder().stock("raw", Sat.SAT - 1)
                .pattern("T", ao, List.of(CraftInput.of("raw", ac)))
                .pattern("T", bo, List.of(CraftInput.of("raw", bc))).build();
    }

    private static CraftPlan<String> baseline(CraftGraph<String> graph, long amount) {
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        return CraftPlannerV2.planDetailed(graph, "T", amount, session).plan();
    }

    private static long executions(CraftPlan<?> plan) {
        return plan.firings().values().stream().mapToLong(n -> n).sum();
    }

    private static void balance(CraftGraph<String> graph, CraftPlan<String> plan, long amount) {
        assertTrue(plan.feasible());
        BigInteger produced = BigInteger.valueOf(plan.usedStock().getOrDefault("T", 0L));
        BigInteger consumed = BigInteger.ZERO;
        for (var entry : plan.firings().entrySet()) {
            assertTrue(graph.patternsFor("T").contains(entry.getKey()));
            var n = BigInteger.valueOf(entry.getValue());
            produced = produced.add(entry.getKey().exactOutputAmount().multiply(n));
            for (var input : entry.getKey().inputs()) consumed = consumed.add(input.exactAmount().multiply(n));
        }
        assertTrue(produced.compareTo(BigInteger.valueOf(amount)) >= 0);
        assertEquals(consumed, BigInteger.valueOf(plan.usedStock().getOrDefault("raw", 0L)));
        plan.usedStock().forEach((key, value) -> assertTrue(value >= 0 && value <= graph.stock(key)));
    }
}
