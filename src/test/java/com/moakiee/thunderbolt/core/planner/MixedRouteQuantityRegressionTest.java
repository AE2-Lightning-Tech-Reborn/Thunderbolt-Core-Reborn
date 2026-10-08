package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class MixedRouteQuantityRegressionTest {
    @Test
    void anIndependentBatchWitnessNeedsNoExtraStartupMaterial() {
        for (long q : new long[] {1, 2, 1_000_000, 1_000_000_000_000L}) {
            var graph = MaterialDagReplayTest.mixedRoutesGraph(q);
            var route = List.of(graph.patternsFor("M3").get(3), graph.patternsFor("M4").get(2),
                    graph.patternsFor("M3").get(2), graph.patternsFor("M5").get(0));
            var inventory = new HashMap<String, BigInteger>();
            for (String key : List.of("M0", "M1", "M2", "M4"))
                inventory.put(key, BigInteger.valueOf(graph.stock(key)));
            for (var pattern : route) {
                for (var input : pattern.inputs()) {
                    var needed = input.exactAmount().multiply(BigInteger.valueOf(q));
                    assertTrue(inventory.getOrDefault(input.key(), BigInteger.ZERO).compareTo(needed) >= 0);
                    inventory.merge(input.key(), needed.negate(), BigInteger::add);
                }
                inventory.merge(pattern.output(), pattern.exactOutputAmount().multiply(BigInteger.valueOf(q)), BigInteger::add);
                for (var output : pattern.byproducts())
                    inventory.merge(output.key(), output.exactAmount().multiply(BigInteger.valueOf(q)), BigInteger::add);
            }
            assertEquals(BigInteger.valueOf(q), inventory.get("M5"));
        }
    }

    @RepeatedTest(20)
    void freshRecipeIdentitiesKeepTrillionScaleFeasibility() {
        var graph = MaterialDagReplayTest.mixedRoutesGraph(1_000_000_000_000L);
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        var result = CraftPlannerV2.planDetailed(graph, "M5", 1_000_000_000_000L, session);
        assertTrue(result.plan().feasible(), result::toString);
        assertEquals(Map.of(), result.plan().missing());
    }
}
