package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Behavioral policy regressions; neither test inspects the private Policy result. */
class FeasiblePolicyReuseTest {
    @Test
    void cyclicUnitCostChoicesFallBackToAnExecutableEarlierRankedPolicy() {
        var direct = pattern("T", 1, "A", 1);
        var oldTarget = pattern("T", 1, "X", 1);
        var rawA = pattern("A", 1, "raw", 1);
        var cyclicA = pattern("A", 100, "B", 1);
        var cyclicB = pattern("B", 100, "A", 1);
        var oldX = pattern("X", 1, "Y", 1);
        var oldY = pattern("Y", 1, "raw", 1);
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern(direct).pattern(oldTarget)
                .pattern(rawA).pattern(cyclicA).pattern(cyclicB)
                .pattern(oldX).pattern(oldY).build();
        var initial = certified(graph, Map.of(oldTarget, 1L, oldX, 1L, oldY, 1L), 1);
        var expectedCounts = Map.of(direct, 1L, rawA, 1L);
        var expected = certified(graph, expectedCounts, 1);
        var oracleCalls = new AtomicInteger();

        // Per-unit relaxation favors both productive A/B conversions together.
        // Their selected graph is cyclic; the rank fallback must retain raw -> A -> T.
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 1, candidate -> {
            assertEquals(1, oracleCalls.incrementAndGet());
            assertEquals(List.of(direct), candidate.patternsFor("T"));
            assertEquals(List.of(rawA), candidate.patternsFor("A"));
            assertTrue(candidate.patternsFor("B").isEmpty(), "the cyclic argmin must not reach the oracle");
            assertEquals(1, candidate.stock("raw"));
            return certified(candidate, expectedCounts, 1);
        }, work -> { throw new AssertionError("the policy must finish within its one oracle probe"); });

        assertEquals(1, oracleCalls.get());
        assertEquals(1, result.probes());
        assertEquals(1, result.improvements());
        assertEquals(expected, result.plan());
        assertEquals(2, executions(result.plan()));
        assertEquals(Map.of("raw", 1L), result.plan().usedStock());
        assertEquals(3, executions(initial));
    }

    @Test
    void unchangedOverdrawingPoliciesKeepRaisingPriceUntilTheFirstFullInventoryProbeFits() {
        var oldTarget = pattern("T", 1, "X", 1);
        var oversized = pattern("T", 100, "R", 2);
        var freeTarget = new CraftPattern<String>("T", 1, List.of(), null);
        var oldX = pattern("X", 1, "Y", 1);
        var oldY = new CraftPattern<String>("Y", 1, List.of(), null);
        var graph = CraftGraph.<String>builder().stock("R", 1)
                .pattern(oldTarget).pattern(oversized).pattern(freeTarget)
                .pattern(oldX).pattern(oldY).build();
        var initial = certified(graph, Map.of(oldTarget, 10L, oldX, 10L, oldY, 10L), 10);
        var expectedCounts = Map.of(freeTarget, 10L);
        var expected = certified(graph, expectedCounts, 10);
        var oracleCalls = new AtomicInteger();

        // The oversized route cannot fit R=1, but remains cheaper per unit at
        // prices 0, 1 and 8. Equality must raise again; price 64 selects freeTarget.
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 10, initial, 1, candidate -> {
            assertEquals(1, oracleCalls.incrementAndGet(), "overdrawn policies must not consume oracle probes");
            assertEquals(List.of(freeTarget), candidate.patternsFor("T"));
            assertTrue(candidate.patternsFor("X").isEmpty());
            assertEquals(1, candidate.stock("R"),
                    "the first full-inventory policy must succeed before the later restricted-stock phase");
            return certified(candidate, expectedCounts, 10);
        }, work -> { throw new AssertionError("price adjustment must finish before mixed-batch work"); });

        assertEquals(1, oracleCalls.get());
        assertEquals(1, result.probes());
        assertEquals(1, result.improvements());
        assertEquals(expected, result.plan());
        assertEquals(10, executions(result.plan()));
        assertTrue(result.plan().usedStock().isEmpty());
        assertEquals(30, executions(initial));
    }

    private static CraftPattern<String> pattern(String output, long amount, String input, long used) {
        return new CraftPattern<>(output, amount, List.of(CraftInput.of(input, used)), null);
    }

    private static CraftPlan<String> certified(CraftGraph<String> graph,
            Map<CraftPattern<String>, Long> counts, long amount) {
        var plan = MaterialDagReplay.tryPlan(graph, counts, "T", amount);
        assertNotNull(plan, "every starting and proposed witness must independently replay");
        assertTrue(plan.feasible());
        assertTrue(plan.missing().isEmpty());
        return plan;
    }

    private static long executions(CraftPlan<?> plan) {
        return plan.firings().values().stream().mapToLong(Long::longValue).sum();
    }
}
