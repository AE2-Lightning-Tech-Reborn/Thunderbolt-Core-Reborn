package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SparseResourceFlowTest {
    @Test
    void batchProducedIntermediatesRetainTheSparseResourceProof() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            for (int depth : new int[]{32, 48, 64, 80}) {
                long[] quantities = depth <= 48 ? new long[]{8L, 8_000_000L, 8_000_000_000_000L}
                        : new long[]{8L, 8_000_000L};
                for (long quantity : quantities) {
                    var original = recurrence(depth, quantity, false);
                    var builder = CraftGraph.<String>builder();
                    for (int i = 3; i <= depth; i++) original.patternsFor("X" + i).forEach(builder::pattern);
                    for (int i = 0; i < 3; i++) {
                        assertEquals(0, original.stock("X" + i) % 4);
                        builder.pattern("X" + i, 4, List.of(CraftInput.of("R" + i, 1)))
                                .stock("R" + i, original.stock("X" + i) / 4);
                    }
                    var graph = builder.build();
                    var result = CraftPlannerV2.planDetailed(graph, "X" + depth, quantity);
                    assertTrue(result.plan().feasible(), () -> "depth=" + depth + " q=" + quantity);
                    assertFalse(result.plan().budgetExhausted());
                    assertTrue(result.diagnostics().lowWidthIntegerNodes() <= 2);
                    assertExecutable(graph, result.plan(), depth, quantity);
                }
            }
        });
    }

    @Test
    void tightTwoRouteRecurrencesRemainExecutableBeyondTheFormerDenseGate() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            for (int depth : new int[]{32, 48, 64, 80}) {
                for (long quantity : new long[]{1L, 1_000_000L, depth <= 48 ? 1_000_000_000_000L : 10_000_000L}) {
                    for (boolean reverse : new boolean[]{false, true}) {
                        var graph = recurrence(depth, quantity, reverse);
                        var result = CraftPlannerV2.planDetailed(graph, "X" + depth, quantity);
                        assertTrue(result.plan().feasible(), () -> "depth=" + depth + " q=" + quantity
                                + " missing=" + result.plan().missing());
                        assertFalse(result.plan().budgetExhausted());
                        assertTrue(result.diagnostics().lowWidthSolved() <= 1);
                        if (!reverse) assertEquals(1, result.diagnostics().lowWidthSolved());
                        assertEquals(0, result.diagnostics().consumedFallbackBudget());
                        assertTrue(result.diagnostics().lowWidthIntegerNodes() <= 2);
                        assertExecutable(graph, result.plan(), depth, quantity);
                    }
                }
            }
        });
    }

    @Test
    void insufficientRawStockStillProducesAnExecutableLeafReplenishment() {
        var graph = recurrence(48, 1_000_000L, false);
        var builder = CraftGraph.<String>builder();
        for (int i = 0; i <= 48; i++) {
            graph.patternsFor("X" + i).forEach(builder::pattern);
            builder.stock("X" + i, graph.stock("X" + i) / 2);
        }
        var poor = builder.build();
        var plan = CraftPlannerV2.plan(poor, "X48", 1_000_000L);
        assertFalse(plan.feasible());
        assertTrue(plan.missing().keySet().stream().allMatch(key ->
                key.equals("X0") || key.equals("X1") || key.equals("X2")));
        assertExecutable(poor, plan, 48, 1_000_000L);
        var filled = poor.withAdditionalStock(plan.missing());
        var retry = CraftPlannerV2.plan(filled, "X48", 1_000_000L);
        assertTrue(retry.feasible(), () -> "missing=" + retry.missing());
        assertExecutable(filled, retry, 48, 1_000_000L);
    }

    private static CraftGraph<String> recurrence(int depth, long quantity, boolean reverse) {
        var builder = CraftGraph.<String>builder();
        long[] need = new long[depth + 1]; need[depth] = quantity;
        // The skip route minimizes total raw cost above X3. X3's two equal-cost alternatives
        // must split the remaining demand to match the deliberately tight three raw stocks.
        for (int i = depth; i >= 4; i--) {
            need[i - 2] = Math.addExact(need[i - 2], need[i]);
            need[i - 3] = Math.addExact(need[i - 3], need[i]);
        }
        long left = need[3] / 2, right = need[3] - left;
        need[0] = Math.addExact(need[0], right);
        need[1] = Math.addExact(need[1], need[3]);
        need[2] = Math.addExact(need[2], left);
        for (int i = 3; i <= depth; i++) {
            for (int route = 0; route < 2; route++) {
                int skip = reverse ? 2 - route : 1 + route;
                builder.pattern("X" + i, 1, List.of(
                        CraftInput.of("X" + (i - skip), 1),
                        CraftInput.of("X" + (i - skip - 1), 1)));
            }
        }
        for (int i = 0; i < 3; i++) {
            assertTrue(need[i] < Sat.SAT);
            builder.stock("X" + i, need[i]);
        }
        return builder.build();
    }

    /** Independent DAG replay, using only the physical extraction and supplied leaf quantities. */
    private static void assertExecutable(CraftGraph<String> graph, CraftPlan<String> plan,
            int depth, long quantity) {
        var inventory = new HashMap<>(plan.usedStock());
        plan.usedStock().forEach((key, value) -> assertTrue(value <= graph.stock(key)));
        plan.missing().forEach((key, value) -> inventory.merge(key, value, Math::addExact));
        var recipes = new ArrayList<>(plan.firings().keySet());
        recipes.sort(Comparator.comparingInt(pattern -> Integer.parseInt(pattern.output().substring(1))));
        for (var pattern : recipes) {
            long times = plan.firings().get(pattern);
            for (var input : pattern.inputs()) {
                long left = Math.subtractExact(inventory.getOrDefault(input.key(), 0L),
                        Math.multiplyExact(times, input.amount()));
                assertTrue(left >= 0, () -> "overdraw " + input.key());
                inventory.put(input.key(), left);
            }
            inventory.merge(pattern.output(), Math.multiplyExact(times, pattern.outputAmount()), Math::addExact);
        }
        assertTrue(inventory.getOrDefault("X" + depth, 0L) >= quantity);
    }
}
