package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanningSessionProbeTest {
    @Test
    void probesShareWorkLimitsButNotOptionalState() {
        var graph = graph();
        var parent = new CraftPlannerV2.PlanningSession<String>();
        CraftPlannerV2.planDetailed(graph, "T", 1, parent);
        parent.consumptionOptimizationProbes = 7;
        parent.consumptionOptimizationNanos = 123;

        var probe = parent.newProbe();
        assertSame(parent.lowWidthWorkBudget, probe.lowWidthWorkBudget);
        assertSame(parent.searchWorkBudget, probe.searchWorkBudget);
        assertSame(parent.resolutionWorkBudget, probe.resolutionWorkBudget);
        assertSame(parent.fallbackWorkBudget, probe.fallbackWorkBudget);
        assertNotSame(parent.consumptionIndex, probe.consumptionIndex);
        assertFalse(probe.optimizeFeasible);
        assertFalse(probe.refineMissing);
        assertEquals(0, probe.consumptionOptimizationProbes);
        assertEquals(0, probe.consumptionOptimizationNanos);
    }

    @Test
    void probesRebuildStockBoundsWithoutRebindingTheParent() {
        var graph = graph();
        var parent = new CraftPlannerV2.PlanningSession<String>();
        assertEquals(Map.of("raw", 1L), CraftPlannerV2.planDetailed(graph, "T", 1, parent).plan().missing());

        var supplied = graph.withAdditionalStock(Map.of("raw", 3L));
        var probe = parent.newProbe();
        assertEquals(parent.reachableWork(graph, "T"), probe.reachableWork(supplied, "T"));
        var plan = CraftPlannerV2.planDetailed(supplied, "T", 1, probe).plan();
        assertTrue(plan.feasible());
        assertEquals(Map.of("raw", 1L), plan.usedStock());
        assertEquals(Map.of("raw", 1L), CraftPlannerV2.planDetailed(graph, "T", 1, parent).plan().missing());
    }

    private static CraftGraph<String> graph() {
        return CraftGraph.<String>builder().pattern("T", 1, List.of(CraftInput.of("raw", 1))).build();
    }
}
