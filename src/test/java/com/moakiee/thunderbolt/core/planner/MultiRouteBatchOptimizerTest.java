package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MultiRouteBatchOptimizerTest {
    @Test void threeDistinctRoutesAreNeededToUseBothScarceResources() {
        for (long scale : new long[] {1, 1_000_000_000_000L}) {
            var graph = CraftGraph.<String>builder().stock("iron", 100 * scale).stock("diamond", 100 * scale)
                    .pattern("T", 2 * scale, List.of(CraftInput.of("iron", 2 * scale), CraftInput.of("diamond", 2 * scale)))
                    .pattern("T", 4 * scale, List.of(CraftInput.of("iron", 7 * scale)))
                    .pattern("T", 4 * scale, List.of(CraftInput.of("diamond", 7 * scale))).build();
            var initial = initial(graph, new long[] {5, 0, 0}, 10 * scale);
            var result = MultiRouteBatchOptimizer.solve(graph, "T", 10 * scale, initial, n -> true);
            assertEquals(BatchOptimizationResult.Status.COMPLETE, result.status());
            assertEquals(3, executions(result.plan()));
            assertEquals(3, result.plan().firings().size());
            assertEquals(Map.of("iron", 9 * scale, "diamond", 9 * scale), result.plan().usedStock());
            balance(graph, result.plan(), 10 * scale);
        }
    }

    @Test void randomThreeAndFourRoutesMatchIndependentExhaustiveStockLimitedOracle() {
        var random = new Random(20261005);
        for (int sample = 0; sample < 400; sample++) {
            int size = sample % 2 + 3, amount = 1 + random.nextInt(8);
            var builder = CraftGraph.<String>builder().stock("iron", 10000).stock("diamond", 10000);
            int[] outputs = new int[size], iron = new int[size], diamond = new int[size];
            for (int r = 0; r < size; r++) {
                outputs[r] = 1 + random.nextInt(6); iron[r] = 1 + random.nextInt(5); diamond[r] = 1 + random.nextInt(5);
                builder.pattern("T", outputs[r], List.of(CraftInput.of("iron", iron[r]), CraftInput.of("diamond", diamond[r])));
            }
            var graph = builder.build();
            long[] counts = new long[size]; counts[0] = (amount + outputs[0] - 1) / outputs[0];
            var initial = initial(graph, counts, amount);
            long[] optimum = {executions(initial), initial.usedStock().values().stream().mapToLong(n -> n).sum()};
            oracle(0, amount, 0, 0, 0, 0, outputs, iron, diamond,
                    initial.usedStock().get("iron"), initial.usedStock().get("diamond"), optimum);
            var result = MultiRouteBatchOptimizer.solve(graph, "T", amount, initial, n -> true);
            assertEquals(BatchOptimizationResult.Status.COMPLETE, result.status(), "sample=" + sample);
            assertEquals(optimum[0], executions(result.plan()), "sample=" + sample);
            assertEquals(optimum[1], result.plan().usedStock().values().stream().mapToLong(n -> n).sum(), "sample=" + sample);
            balance(graph, result.plan(), amount);
        }
    }

    @Test void budgetExhaustionIsNotConfusedWithOptimalityAndRetainsIncumbent() {
        var graph = CraftGraph.<String>builder().stock("raw", 1000)
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 3, List.of(CraftInput.of("raw", 4)))
                .pattern("T", 4, List.of(CraftInput.of("raw", 6))).build();
        var initial = initial(graph, new long[] {20, 0, 0}, 20);
        var result = MultiRouteBatchOptimizer.solve(graph, "T", 20, initial, n -> false);
        assertEquals(BatchOptimizationResult.Status.EXHAUSTED, result.status());
        assertSame(initial, result.plan());
        var work = new AtomicInteger();
        result = MultiRouteBatchOptimizer.solve(graph, "T", 20, initial, n -> {
            work.addAndGet(n); return true;
        });
        assertTrue(work.get() <= MultiRouteBatchOptimizer.MAX_WORK);
        balance(graph, result.plan(), 20);
    }

    @Test void completedThreeRouteSearchIsIntegratedWithoutBuildingTheGeneralIndex() {
        var graph = CraftGraph.<String>builder().stock("iron", 10).stock("diamond", 10)
                .pattern("T", 2, List.of(CraftInput.of("iron", 2), CraftInput.of("diamond", 2)))
                .pattern("T", 4, List.of(CraftInput.of("iron", 7)))
                .pattern("T", 4, List.of(CraftInput.of("diamond", 7))).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        var result = CraftPlannerV2.planDetailed(graph, "T", 10, session);
        assertEquals(3, executions(result.plan()));
        assertEquals(3, result.plan().firings().size());
        assertFalse(session.consumptionIndex.isCompiled());
        balance(graph, result.plan(), 10);
    }

    @Test void fiveAndSixRouteSearchesPreserveOriginalPatternReferences() {
        for (int size : new int[] {5, 6}) {
            var builder = CraftGraph.<String>builder().stock("raw", 1000);
            for (int r = 1; r <= size; r++) builder.pattern("T", r, List.of(CraftInput.of("raw", 2 * r)));
            var graph = builder.build();
            long[] counts = new long[size]; counts[0] = 2 * size;
            var result = MultiRouteBatchOptimizer.solve(graph, "T", 2 * size,
                    initial(graph, counts, 2 * size), n -> true);
            assertEquals(BatchOptimizationResult.Status.COMPLETE, result.status());
            assertEquals(2, executions(result.plan()));
            assertEquals(4L * size, result.plan().usedStock().get("raw"));
            assertSame(graph.patternsFor("T").get(size - 1), result.plan().firings().keySet().iterator().next());
            balance(graph, result.plan(), 2 * size);
        }
    }

    @Test void exhaustedSearchRetainsACertifiedCandidateWithoutClaimingCompletion() {
        var graph = CraftGraph.<String>builder().stock("raw", 100)
                .pattern("T", 2, List.of(CraftInput.of("raw", 4)))
                .pattern("T", 4, List.of(CraftInput.of("raw", 7)))
                .pattern("T", 5, List.of(CraftInput.of("raw", 10))).build();
        var initial = initial(graph, new long[] {5, 0, 0}, 10);
        var remaining = new AtomicInteger(10);
        var result = MultiRouteBatchOptimizer.solve(graph, "T", 10, initial,
                n -> remaining.addAndGet(-n) >= 0);
        assertEquals(BatchOptimizationResult.Status.EXHAUSTED, result.status());
        assertNotSame(initial, result.plan());
        assertEquals(2, executions(result.plan()));
        assertEquals(20L, result.plan().usedStock().get("raw"));
        balance(graph, result.plan(), 10);
    }

    @Test void targetStockDoesNotAllowAFalseGlobalCompletenessShortcut() {
        var graph = CraftGraph.<String>builder().stock("raw", 100).stock("T", 3)
                .pattern("T", 8, List.of(CraftInput.of("raw", 12)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 3))).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        var result = CraftPlannerV2.planDetailed(graph, "T", 22, session);
        assertTrue(result.plan().feasible());
        assertTrue(session.consumptionIndex.isCompiled(), "the general optimizer may reduce fixed target-stock draws");
    }

    @Test void largeExecutionCountAndStatefulRoutesDeclineWithoutClaimingCompletion() {
        var graph = CraftGraph.<String>builder().stock("raw", 1000)
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 3, List.of(CraftInput.of("raw", 3)))
                .pattern("T", 4, List.of(CraftInput.of("raw", 4))).build();
        assertEquals(BatchOptimizationResult.Status.UNSUPPORTED,
                MultiRouteBatchOptimizer.solve(graph, "T", 100, initial(graph, new long[] {100, 0, 0}, 100), n -> true).status());
        var stateful = CraftGraph.<String>builder().stock("raw", 100).stock("tool", 1)
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 3, List.of(CraftInput.of("raw", 3)))
                .pattern("T", 4, List.of(CraftInput.finiteUse("tool", 1, 5))).build();
        assertEquals(BatchOptimizationResult.Status.UNSUPPORTED,
                MultiRouteBatchOptimizer.solve(stateful, "T", 5, initial(stateful, new long[] {5, 0, 0}, 5), n -> true).status());
    }

    @Test void completeTwoRouteResultSkipsTheGeneralOptimizerButTimeoutDoesNotClaimComplete() {
        var graph = CraftGraph.<String>builder().stock("raw", 30)
                .pattern("T", 8, List.of(CraftInput.of("raw", 12)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 3))).build();
        var initial = initial(graph, new long[] {0, 10}, 19);
        assertEquals(BatchOptimizationResult.Status.COMPLETE, TwoRouteBatchOptimizer.solve(graph, "T", 19, initial, n -> true).status());
        assertEquals(BatchOptimizationResult.Status.EXHAUSTED, TwoRouteBatchOptimizer.solve(graph, "T", 19, initial, n -> false).status());
        var session = new CraftPlannerV2.PlanningSession<String>();
        var plan = CraftPlannerV2.planDetailed(graph, "T", 19, session);
        var calls = new AtomicInteger();
        assertFalse(session.consumptionIndex.isCompiled(), "an exact result must skip general index construction");
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class,
                    () -> TwoRouteBatchOptimizer.solve(graph, "T", 19, initial, n -> { calls.incrementAndGet(); return true; }));
        }
        assertEquals(0, calls.get());
        assertEquals(1, plan.diagnostics().consumptionOptimizationProbes());
    }

    @Test void incumbentLimitedCompletenessDoesNotHideAdditionalAvailableStock() {
        var graph = CraftGraph.<String>builder().stock("raw", 36)
                .pattern("T", 8, List.of(CraftInput.of("raw", 12)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 3))).build();
        var initial = initial(graph, new long[] {0, 10}, 19);
        var local = TwoRouteBatchOptimizer.solve(graph, "T", 19, initial, n -> true);
        assertEquals(BatchOptimizationResult.Status.COMPLETE, local.status());
        assertEquals(4, executions(local.plan()));
        assertEquals(Map.of("raw", 30L), local.plan().usedStock());
        assertFalse(OptionalPlanningStages.canSkipGeneralOptimization(graph, "T", initial, local),
                "optimality inside 30 raw must not suppress a better plan using 36 available raw");
        assertTrue(OptionalPlanningStages.canSkipGeneralOptimization(
                graph.withStockLimits(initial.usedStock()), "T", initial, local));
        var optimizedSession = new CraftPlannerV2.PlanningSession<String>();
        var optimized = CraftPlannerV2.planDetailed(graph, "T", 19, optimizedSession).plan();
        assertEquals(3, executions(optimized));
        assertEquals(Map.of("raw", 36L), optimized.usedStock());
        balance(graph, optimized, 19);
    }

    private static void oracle(int route, int amount, int produced, int ironUse, int diamondUse, int count,
            int[] outputs, int[] iron, int[] diamond, long ironCap, long diamondCap, long[] best) {
        if (ironUse > ironCap || diamondUse > diamondCap || count > best[0]) return;
        if (route == outputs.length) {
            if (produced >= amount && (count < best[0] || count == best[0] && ironUse + diamondUse < best[1])) {
                best[0] = count; best[1] = ironUse + diamondUse;
            }
            return;
        }
        for (int n = 0; n <= amount; n++) oracle(route + 1, amount, produced + outputs[route] * n,
                ironUse + iron[route] * n, diamondUse + diamond[route] * n, count + n, outputs, iron, diamond, ironCap, diamondCap, best);
    }

    private static CraftPlan<String> initial(CraftGraph<String> graph, long[] counts, long amount) {
        var fired = new HashMap<CraftPattern<String>, Long>(); var used = new HashMap<String, Long>();
        for (int p = 0; p < counts.length; p++) if (counts[p] > 0) {
            var pattern = graph.patternsFor("T").get(p); fired.put(pattern, counts[p]);
            for (var input : pattern.inputs()) used.merge(input.key(), input.amount() * counts[p], Long::sum);
        }
        return new CraftPlan<>(true, true, Map.copyOf(fired), Map.copyOf(used), Map.of(), Map.of(), Map.of("T", amount), used.size() + 1, false);
    }

    private static long executions(CraftPlan<?> plan) { return plan.firings().values().stream().mapToLong(n -> n).sum(); }

    private static void balance(CraftGraph<String> graph, CraftPlan<String> plan, long amount) {
        var consumed = new HashMap<String, BigInteger>(); BigInteger produced = BigInteger.ZERO;
        for (var entry : plan.firings().entrySet()) {
            assertTrue(graph.patternsFor("T").contains(entry.getKey()));
            BigInteger n = BigInteger.valueOf(entry.getValue()); produced = produced.add(entry.getKey().exactOutputAmount().multiply(n));
            for (var input : entry.getKey().inputs()) consumed.merge(input.key(), input.exactAmount().multiply(n), BigInteger::add);
        }
        assertTrue(produced.compareTo(BigInteger.valueOf(amount)) >= 0);
        consumed.forEach((k, n) -> assertEquals(n, BigInteger.valueOf(plan.usedStock().getOrDefault(k, 0L))));
        plan.usedStock().forEach((k, n) -> assertTrue(n <= graph.stock(k)));
    }
}
