package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialDagProjectionCertificateTest {
    @Test
    void restoredFeedbackOutputsKeepTheConservativeAllOrdersWitnessAndOriginalIdentities() {
        for (long amount : new long[] {2, 1_000_000_000_000L}) {
            var graph = MaterialDagReplayTest.mixedRoutesGraph(amount);
            // Both routes consume M0; the conservative witness reserves its full gross draw.
            var rawRoute = graph.patternsFor("M3").get(0);
            var intermediate = graph.patternsFor("M4").get(2);
            var feedbackRoute = graph.patternsFor("M3").get(2);
            var target = graph.patternsFor("M5").get(0);
            var conservative = feedbackRoute.projectMaterials(feedbackRoute.inputs(), List.of());
            var projection = graph.withPatterns(Map.of("M3", List.of(rawRoute, conservative),
                    "M4", List.of(intermediate), "M5", List.of(target)));
            var plan = MaterialDagReplay.tryPlan(projection,
                    Map.of(rawRoute, amount, intermediate, amount, conservative, amount, target, amount),
                    "M5", amount);
            assertNotNull(plan);
            var candidate = new MaterialDagOrders.Candidate<>(projection, amount,
                    Map.of(conservative, feedbackRoute), List.of());
            var restored = candidate.restore(plan, graph, "M5", amount);
            assertTrue(MaterialDagReplay.hasCertificate(graph, restored, "M5", amount));
            assertFalse(MaterialDagReplay.hasCertificate(graph, restored, "M5", amount + 1));
            assertFalse(MaterialDagReplay.hasCertificate(projection, restored, "M5", amount));
            assertEquals(amount, restored.firings().get(feedbackRoute));
            assertFalse(restored.firings().containsKey(conservative));
            assertTrue(restored.missing().isEmpty());
            assertSame(restored, UnorderedByproductSafety.protect(graph, restored, "M5", amount));
            // A plain count vector must receive the same proof before feedback reserve search.
            var plain = new CraftPlan<String>(true, true, Map.copyOf(restored.firings()),
                    restored.usedStock(), Map.of(), Map.of(),
                    restored.grossDemand(), restored.itemsProcessed(), false);
            var protectedPlan = UnorderedByproductSafety.protect(graph, plain, "M5", amount);
            assertTrue(protectedPlan.feasible());
            assertTrue(MaterialDagReplay.hasCertificate(graph, protectedPlan, "M5", amount));
            if (amount == 2) ByproductReplaySafetyTest.assertEveryOrderFinishes(restored, "M5", amount);
        }
    }

    @Test
    void certificateRestorationPreservesPhysicalBatchStockReservations() {
        var original = new CraftPattern<>("M", 10, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("side", 1)), "batch");
        var projected = original.projectMaterials(original.inputs(), List.of());
        var target = new CraftPattern<>("T", 1, List.of(CraftInput.of("M", 12)), "target");
        var graph = CraftGraph.<String>builder().pattern(original).pattern(target)
                .stock("M", 5).stock("raw", 1).build();
        var projection = graph.withPatterns(Map.of("M", List.of(projected), "T", List.of(target)));
        var net = MaterialDagReplay.tryPlan(projection, Map.of(projected, 1L, target, 1L), "T", 1);
        assertNotNull(net);
        assertEquals(2L, net.usedStock().get("M"));
        var reserved = new CraftPlan<String>(true, true, net.firings(), Map.of("M", 5L, "raw", 1L),
                Map.of(), Map.of(), net.grossDemand(), net.itemsProcessed(), false);
        var candidate = new MaterialDagOrders.Candidate<>(projection, 1, Map.of(projected, original), List.of());
        var restored = candidate.restore(reserved, graph, "T", 1);
        assertTrue(MaterialDagReplay.hasCertificate(graph, restored, "T", 1));
        assertEquals(reserved.usedStock(), restored.usedStock());
        ByproductReplaySafetyTest.assertEveryOrderFinishes(restored, "T", 1);

        var unavailable = graph.withStockLimits(Map.of("M", 2L, "raw", 1L));
        assertFalse(MaterialDagReplay.hasCertificate(unavailable,
                candidate.restore(reserved, unavailable, "T", 1), "T", 1));
    }

    @Test
    void projectionCannotAuthorizeChangedInputsUnregisteredRecipesOrStockFromAnotherSnapshot() {
        var projected = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), "projected");
        var projection = CraftGraph.<String>builder().pattern(projected).stock("raw", 1).build();
        var plan = MaterialDagReplay.tryPlan(projection, Map.of(projected, 1L), "T", 1);
        assertNotNull(plan);
        var changed = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 2)),
                List.of(CraftOutput.of("side", 1)), "changed");
        var graph = CraftGraph.<String>builder().pattern(changed).stock("raw", 2).build();
        var candidate = new MaterialDagOrders.Candidate<>(projection, 1L, Map.of(projected, changed), List.of());
        assertFalse(MaterialDagReplay.hasCertificate(graph, candidate.restore(plan, graph, "T", 1), "T", 1));

        var original = new CraftPattern<>("T", 1, projected.inputs(),
                List.of(CraftOutput.of("side", 1)), "original");
        var unknown = CraftGraph.<String>builder().stock("raw", 1).build();
        var unavailable = CraftGraph.<String>builder().pattern(original).build();
        candidate = new MaterialDagOrders.Candidate<>(projection, 1L, Map.of(projected, original), List.of());
        assertFalse(MaterialDagReplay.hasCertificate(unknown, candidate.restore(plan, unknown, "T", 1), "T", 1));
        assertFalse(MaterialDagReplay.hasCertificate(unavailable, candidate.restore(plan, unavailable, "T", 1), "T", 1));
    }
}
