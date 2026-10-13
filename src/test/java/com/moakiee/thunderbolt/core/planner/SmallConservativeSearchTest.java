package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class SmallConservativeSearchTest {
    @Test void repeatedPatternRegistrationsDoNotDisablePublicTerminalRecovery() {
        long[] sample = {1,3,3, 2,3,2, 9,1,5, 12,5,4, 4,1,2, 3,4,4, 10,6,6, 8,2,5};
        for (int repetitions : new int[] {1, 3, 4}) {
            var builder = CraftGraph.<String>builder().stock("A", 5).stock("B", 14);
            var distinct = new java.util.HashSet<CraftPattern<String>>();
            for (int i = 0; i < sample.length; i += 3) {
                var pattern = new CraftPattern<>("T", sample[i], List.of(
                        CraftInput.of("A", sample[i + 1]), CraftInput.of("B", sample[i + 2])), null);
                distinct.add(pattern);
                for (int copy = 0; copy < repetitions; copy++) builder.pattern(pattern);
            }
            var graph = builder.build();
            assertEquals(8 * repetitions, graph.patternsFor("T").size());
            assertTrue(CraftPlannerV2.reachableWorkEstimate(graph, "T") <= SmallConservativeSearch.MAX_WORK);
            var direct = SmallConservativeSearch.tryPlan(graph, "T", 23, 4096);
            assertNotNull(direct, "repetitions=" + repetitions);
            var integrated = CraftPlannerV2.plan(graph, "T", 23);
            for (var plan : List.of(direct, integrated)) {
                assertTrue(plan.feasible(), "repetitions=" + repetitions);
                assertEquals(4, plan.firings().values().stream().mapToLong(n -> n).sum());
                assertTrue(distinct.containsAll(plan.firings().keySet()));
                ByproductReplaySafetyTest.assertEveryOrderFinishes(plan, "T", 23);
            }
        }
    }

    @Test void distinctRecoveryPatternsSharingOneSourceRemainSeparateRoutes() {
        var source = new Object();
        var first = new CraftPattern<>("T", 2, List.of(CraftInput.of("raw", 1)), source);
        var second = new CraftPattern<>("T", 4, List.of(CraftInput.of("raw", 3)), source);
        var graph = CraftGraph.<String>builder().stock("raw", 4).pattern(first).pattern(second).build();
        var plan = SmallConservativeSearch.tryPlan(graph, "T", 5, 4096);
        assertNotNull(plan);
        assertEquals(Map.of(first, 1L, second, 1L), plan.firings());
        ByproductReplaySafetyTest.assertEveryOrderFinishes(plan, "T", 5);
    }

    @Test void recoversInventoryBackedBatchMixturesWithMoreOutputItemsThanInputs() {
        // demand, stocks A/B, minimum firings, followed by output/A/B amounts.
        for (long[] sample : new long[][] {
                {23, 5, 14, 4, 1,3,3, 2,3,2, 9,1,5, 12,5,4, 4,1,2, 3,4,4, 10,6,6, 8,2,5},
                {14, 7, 5, 2, 6,6,0, 1,6,5, 7,5,1, 10,2,3, 12,6,3, 13,6,1, 6,6,0, 2,1,6},
                {17, 7, 9, 2, 9,5,3, 8,3,0, 7,3,0, 1,5,0, 13,4,1, 5,2,1, 12,5,2}}) {
            var builder = CraftGraph.<String>builder().stock("A", sample[1]).stock("B", sample[2]);
            for (int i = 4; i < sample.length; i += 3) {
                var inputs = new java.util.ArrayList<CraftInput<String>>();
                if (sample[i + 1] > 0) inputs.add(CraftInput.of("A", sample[i + 1]));
                if (sample[i + 2] > 0) inputs.add(CraftInput.of("B", sample[i + 2]));
                builder.pattern("T", sample[i], inputs);
            }
            var graph = builder.build();
            var direct = SmallConservativeSearch.tryPlan(graph, "T", sample[0], 4096);
            assertNotNull(direct);
            assertTrue(direct.feasible());
            assertEquals(sample[3], direct.firings().values().stream().mapToLong(n -> n).sum());
            ByproductReplaySafetyTest.assertEveryOrderFinishes(direct, "T", sample[0]);
            var result = CraftPlannerV2.plan(graph, "T", sample[0]);
            assertTrue(result.feasible());
            assertEquals(sample[3], result.firings().values().stream().mapToLong(n -> n).sum());
            assertTrue(result.usedStock().getOrDefault("A", 0L) <= sample[1]);
            assertTrue(result.usedStock().getOrDefault("B", 0L) <= sample[2]);
            ByproductReplaySafetyTest.assertEveryOrderFinishes(result, "T", sample[0]);
        }
    }

    @Test void growingTerminalRecoveryRetainsWorkStateDepthAndCancellationLimits() {
        var graph = CraftGraph.<String>builder().stock("raw", 64)
                .pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
        assertNotNull(SmallConservativeSearch.tryPlan(graph, "T", 64, 4096));
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 65, 4096));
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 2, 1));
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 2, 4096, () -> false));
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class,
                    () -> SmallConservativeSearch.tryPlan(graph, "T", 2, 4096));
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> SmallConservativeSearch.tryPlan(graph, "T", 2, 4096));
        } finally { Thread.interrupted(); }
    }

    @Test void terminalExceptionDoesNotAdmitGrowingCyclesSideOutputsOrSpecialInputs() {
        var growing = CraftGraph.<String>builder().stock("T", 1)
                .pattern("T", 2, List.of(CraftInput.of("T", 1))).build();
        assertNull(SmallConservativeSearch.tryPlan(growing, "T", 2, 4096));
        var side = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 2, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("side", 1))).build();
        assertNull(SmallConservativeSearch.tryPlan(side, "T", 2, 4096));
        var returned = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 2, List.of(CraftInput.returned("raw", 1))).build();
        assertNull(SmallConservativeSearch.tryPlan(returned, "T", 2, 4096));
        var twoLayer = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("A", 2, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 2, List.of(CraftInput.of("A", 1))).build();
        assertNull(SmallConservativeSearch.tryPlan(twoLayer, "T", 2, 4096));
    }

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
