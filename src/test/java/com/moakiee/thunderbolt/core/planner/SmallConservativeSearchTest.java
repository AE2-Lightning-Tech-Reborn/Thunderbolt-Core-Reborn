package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class SmallConservativeSearchTest {
    private static CraftGraph<String> conversionGraph() {
        return CraftGraph.<String>builder().stock("A", 3)
                .pattern("B", 3, List.of(CraftInput.of("A", 3)))
                .pattern("A", 2, List.of(CraftInput.of("B", 2)))
                .pattern("T", 1, List.of(CraftInput.of("A", 2), CraftInput.of("B", 1))).build();
    }

    @Test void preservesBothDirectionsOfAnExecutableConservativeCycle() {
        var plan = SmallConservativeSearch.tryPlan(conversionGraph(), "T", 1, 4096);
        assertNotNull(plan);
        assertTrue(plan.feasible());
        assertEquals(3, plan.firings().size());
        assertEquals(Map.of("A", 3L), plan.usedStock());
        ByproductReplaySafetyTest.assertEveryOrderFinishes(plan, "T", 1);
        assertTrue(CraftPlannerV2.plan(conversionGraph(), "T", 1).feasible());
    }

    @Test void rejectsBalancedButUnstartableCycles() {
        var graph = CraftGraph.<String>builder().stock("A", 1)
                .pattern("B", 2, List.of(CraftInput.of("A", 2)))
                .pattern("A", 2, List.of(CraftInput.of("B", 2)))
                .pattern("T", 1, List.of(CraftInput.of("B", 1))).build();
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 1, 4096));
    }

    @Test void usesIncidentalByproductsButRejectsByproductOnlyBatches() {
        var producer = new CraftPattern<>("A", 2, List.of(CraftInput.of("raw", 3)),
                List.of(CraftOutput.of("B", 1)), "producer");
        var good = CraftGraph.<String>builder().stock("raw", 3).pattern(producer)
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).build();
        assertNotNull(SmallConservativeSearch.tryPlan(good, "T", 1, 4096));
        var extra = CraftGraph.<String>builder().stock("raw", 6).pattern(producer)
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 2))).build();
        assertNull(SmallConservativeSearch.tryPlan(extra, "T", 1, 4096));
        var sideOnly = CraftGraph.<String>builder().stock("raw", 3).pattern(producer)
                .pattern("T", 1, List.of(CraftInput.of("B", 1))).build();
        assertNull(SmallConservativeSearch.tryPlan(sideOnly, "T", 1, 4096));
    }

    @Test void refusesASequentialWitnessThatCanDeadlockUnderAnotherLegalOrder() {
        var producer = new CraftPattern<>("A", 1,
                List.of(CraftInput.of("seed", 1), CraftInput.of("fuel", 1)),
                List.of(CraftOutput.of("seed", 1)), "producer");
        var graph = CraftGraph.<String>builder().stock("seed", 1).stock("fuel", 1)
                .pattern(producer).pattern("B", 1, List.of(CraftInput.of("seed", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).build();
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 1, 4096));
        var safe = SmallConservativeSearch.tryPlan(graph.withAdditionalStock(Map.of("seed", 1L)), "T", 1, 4096);
        assertNotNull(safe);
        ByproductReplaySafetyTest.assertEveryOrderFinishes(safe, "T", 1);
    }

    @Test void countsSideOutputsOutsidePrimaryAncestryInGrowthCertificate() {
        var producer = new CraftPattern<>("A", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("unused", 1)), "producer");
        var graph = CraftGraph.<String>builder().stock("raw", 1).pattern(producer)
                .pattern("T", 1, List.of(CraftInput.of("A", 1))).build();
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 1, 4096));
    }

    @Test void doesNotEnumerateHugeOrdersOrContinueAfterBudgetAndCancellation() {
        assertNull(SmallConservativeSearch.tryPlan(conversionGraph(), "T", Long.MAX_VALUE, 4096));
        assertNull(SmallConservativeSearch.tryPlan(conversionGraph(), "T", 1, 1));
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class,
                    () -> SmallConservativeSearch.tryPlan(conversionGraph(), "T", 1, 4096));
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> SmallConservativeSearch.tryPlan(conversionGraph(), "T", 1, 4096));
        } finally { Thread.interrupted(); }
    }
}
