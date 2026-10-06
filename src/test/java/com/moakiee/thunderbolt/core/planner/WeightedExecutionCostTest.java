package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightedExecutionCostTest {
    @Test void executionTotalsMatchExactArithmeticAtMultiplyAndSumBoundaries() {
        var unit = new CraftPattern<>("T", 1, List.of(), "unit");
        var weighted = CraftPattern.weighted("T", 1, List.of(), List.of(), "weighted", 17);
        var tag = CraftPattern.tagConversion("raw", "tag", "tag");
        for (long count : new long[] {0, 1, Long.MAX_VALUE / 17, Long.MAX_VALUE / 17 + 1,
                Long.MAX_VALUE - 1, Long.MAX_VALUE, -1, Long.MIN_VALUE}) {
            for (long other : new long[] {0, 1, 17, Long.MAX_VALUE}) {
                var firings = new java.util.LinkedHashMap<CraftPattern<String>, Long>();
                firings.put(unit, other);
                firings.put(weighted, count);
                firings.put(tag, Long.MAX_VALUE);
                var plan = new CraftPlan<>(true, true, firings, Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
                var exact = BigInteger.valueOf(other).add(BigInteger.valueOf(count).multiply(BigInteger.valueOf(17)));
                assertEquals(exact, plan.executionCount());
                var reversed = new java.util.LinkedHashMap<CraftPattern<String>, Long>();
                reversed.put(tag, Long.MAX_VALUE);
                reversed.put(weighted, count);
                reversed.put(unit, other);
                assertEquals(exact, new CraftPlan<>(true, true, reversed, Map.of(), Map.of(), Map.of(), Map.of(), 0, false)
                        .executionCount());
            }
        }
        var overflowSum = new CraftPlan<>(true, true, Map.of(unit, Long.MAX_VALUE, weighted, 1L),
                Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(17)), overflowSum.executionCount());
    }

    @Test void ordinaryWeightsArePositiveAndTagConversionIsTheOnlyFreeFactory() {
        var ordinary = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), null);
        var weighted = CraftPattern.weighted("T", 2, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("side", 3)), "joint", 64);
        assertEquals(1, ordinary.executionCost());
        assertEquals(64, weighted.executionCost());
        assertEquals(3, weighted.byproducts().get(0).amount());
        assertEquals(0, CraftPattern.tagConversion("raw", "tag", null).executionCost());
        for (int cost : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> CraftPattern.weighted("T", 1,
                    List.of(CraftInput.of("raw", 1)), List.of(), null, cost));
            assertThrows(IllegalArgumentException.class, () -> CraftPattern.weighted("T", BigInteger.ONE,
                    List.of(CraftInput.of("raw", 1)), List.of(), null, cost));
        }
    }

    @Test void totalCostIsExactBeyondLongAndTagTransfersRemainInFirings() {
        var paid = CraftPattern.weighted("T", 1, List.of(CraftInput.of("tag", 1)),
                List.of(), null, Integer.MAX_VALUE);
        var tag = CraftPattern.tagConversion("raw", "tag", null);
        var ordinary = new CraftPattern<>("other", 1, List.of(), null);
        var firings = Map.of(paid, Long.MAX_VALUE, tag, Long.MAX_VALUE, ordinary, 7L);
        var plan = new CraftPlan<>(true, true, firings, Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(Integer.MAX_VALUE))
                .add(BigInteger.valueOf(7)), plan.executionCount());
        assertEquals(Long.MAX_VALUE, plan.firings().get(tag));
    }

    @Test void v2DoesNotTreatOneExpensiveBatchAsBetterThanEightCheapFirings() {
        var expensive = CraftPattern.weighted("T", 8, List.of(CraftInput.of("raw", 1)),
                List.of(), "expensive-batch", 64);
        var cheap = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), "cheap-unit");
        var graph = CraftGraph.<String>builder().stock("raw", 8).pattern(expensive).pattern(cheap).build();
        var initial = new CraftPlan<>(true, true, Map.of(expensive, 1L), Map.of("raw", 1L),
                Map.of(), Map.of(), Map.of("T", 8L, "raw", 1L), 2, false);
        assertFalse(FeasibleConsumptionOptimizer.targetOnlyBound(graph, "T", 8, initial));
        assertEquals(BatchOptimizationResult.Status.UNSUPPORTED,
                TwoRouteBatchOptimizer.solve(graph, "T", 8, initial, work -> true).status());

        var plan = CraftPlannerV2.plan(graph, "T", 8);
        assertTrue(plan.feasible());
        assertEquals(BigInteger.valueOf(8), plan.executionCount());
        assertEquals(Map.of(cheap, 8L), plan.firings());
        assertEquals(Map.of("raw", 8L), plan.usedStock());
        assertNotNull(MaterialDagReplay.tryLeafMissingPlan(graph, plan.firings(), "T", 8));
    }

    @Test void v2PricesWeightedRecipesAndFreeTagsTogether() {
        var tag = CraftPattern.tagConversion("raw", "tag", null);
        var expensive = CraftPattern.weighted("T", 8, List.of(CraftInput.of("raw", 1)),
                List.of(), null, 64);
        var cheap = CraftPattern.weighted("T", 2, List.of(CraftInput.of("tag", 1)),
                List.of(), null, 4);
        var graph = CraftGraph.<String>builder().stock("raw", 4)
                .pattern(expensive).pattern(cheap).pattern(tag).build();
        var plan = CraftPlannerV2.plan(graph, "T", 8);
        assertTrue(plan.feasible());
        assertEquals(BigInteger.valueOf(16), plan.executionCount());
        assertEquals(Map.of(cheap, 4L, tag, 4L), plan.firings());
        assertNotNull(MaterialDagReplay.tryLeafMissingPlan(graph, plan.firings(), "T", 8));
    }

    @Test void materialProjectionsAndRestorationPreserveWeightedMultiOutputAndTagIdentity() {
        Object source = new Object();
        var tag = CraftPattern.tagConversion("raw", "tag", null);
        var joint = CraftPattern.weighted("T", 2, List.of(CraftInput.of("tag", 1)),
                List.of(CraftOutput.of("side", 1)), source, 64);
        var graph = CraftGraph.<String>builder().stock("raw", 1).pattern(joint).pattern(tag).build();
        var projections = MaterialDagOrders.compile(graph, "T");
        assertFalse(projections.isEmpty());
        boolean checkedProjection = false;
        for (var projection : projections) {
            var projected = projection.graph().patternsFor("T").get(0);
            assertEquals(64, projected.executionCost());
            assertSame(source, projected.source());
            assertSame(tag, projection.graph().patternsFor("tag").get(0));
            var plan = new CraftPlan<>(true, true, Map.of(projected, 1L, tag, 1L),
                    Map.of("raw", 1L), Map.of(), Map.of(), Map.of("T", 2L), 3, false);
            assertEquals(BigInteger.valueOf(64), plan.executionCount());
            var restored = projection.restore(plan);
            assertEquals(Map.of(joint, 1L, tag, 1L), restored.firings());
            assertEquals(BigInteger.valueOf(64), restored.executionCount());
            checkedProjection |= projected != joint;
        }
        assertTrue(checkedProjection, "at least one side-output projection was inspected");
    }
}
