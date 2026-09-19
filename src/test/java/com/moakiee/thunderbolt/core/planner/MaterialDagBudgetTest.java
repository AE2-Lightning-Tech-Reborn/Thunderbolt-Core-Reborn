package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
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
