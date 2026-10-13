package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import org.junit.jupiter.api.Test;

class MaterialGraphCertificateTest {
    @Test void duplicateArcsRemainAcyclicAndTraversalIsRepeatable() {
        var graph = new PlannerTopology.MaterialGraph<String>();
        var recipe = graph.node();
        graph.edge(graph.item("raw"), recipe);
        graph.edge(graph.item("raw"), recipe);
        graph.edge(recipe, graph.item("T"));
        assertTrue(graph.isAcyclic());
        assertTrue(graph.isAcyclic());
        assertNotEquals(graph.components().get(recipe), graph.components().get(graph.item("raw")));
    }

    @Test void itemKeysCannotAliasPatternVertices() {
        var graph = new PlannerTopology.MaterialGraph<Object>();
        var recipe = graph.node();
        var item = graph.item(recipe);
        assertNotSame(recipe, item);
        graph.edge(item, recipe);
        assertTrue(graph.isAcyclic());
        graph.edge(recipe, item);
        assertFalse(graph.isAcyclic());
        assertEquals(graph.components().get(recipe), graph.components().get(item));
    }

    @Test void smallDagDrawsNetStockWithoutUnnecessaryAllOrderEnumeration() {
        var producer = new CraftPattern<>("A", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("B", 1)), null);
        var target = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("B", 1), CraftInput.of("B", 1)), null);
        var graph = CraftGraph.<String>builder().pattern(producer).pattern(target)
                .stock("raw", 1).stock("B", 5).build();
        var plan = MaterialDagReplay.trySmallPlan(graph, Map.of(producer, 1L, target, 1L), "T", 1);
        assertNotNull(plan);
        assertTrue(plan.feasible());
        assertEquals(Map.of("raw", 1L, "B", 1L), plan.usedStock());
        assertTrue(UnorderedByproductSafety.allOrdersFinishSmall(plan, "T", 1));
        assertSame(plan, UnorderedByproductSafety.protect(graph, plan, "T", 1));
        var insufficient = CraftGraph.<String>builder().pattern(producer).pattern(target).stock("raw", 1).build();
        assertNull(MaterialDagReplay.trySmallPlan(insufficient,
                plan.firings(), "T", 1));
    }

    @Test void cyclesStillNeedARealSeedAndPrimarySupport() {
        var recipe = new CraftPattern<>("T", 1, List.of(CraftInput.of("seed", 1)),
                List.of(CraftOutput.of("seed", 1)), null);
        var empty = CraftGraph.<String>builder().pattern(recipe).build();
        assertNull(MaterialDagReplay.tryPlan(empty, Map.of(recipe, 1L), "T", 1));
        assertNull(MaterialDagReplay.trySmallPlan(empty, Map.of(recipe, 1L), "T", 1));
        var stocked = empty.withAdditionalStock(Map.of("seed", 1L));
        assertTrue(MaterialDagReplay.trySmallPlan(stocked, Map.of(recipe, 1L), "T", 1).feasible());
        assertNull(MaterialDagReplay.trySmallPlan(stocked, Map.of(recipe, 2L), "T", 1));
    }

    @Test void topologicalTraversalChecksCancellationWithinWideFanout() {
        var graph = new PlannerTopology.MaterialGraph<Integer>();
        var root = graph.node();
        for (int i = 0; i < 4096; i++) graph.edge(root, graph.item(i));
        var cancelled = new CancellationException("cancel topological fanout");
        int[] checks = {0};
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() {
                // The first 4097 checks initialize degrees, the next enters the root,
                // and the following check occurs after at most 256 outgoing arcs.
                if (++checks[0] == 4099) throw cancelled;
            }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(cancelled, assertThrows(CancellationException.class, graph::isAcyclic));
        }
        assertTrue(graph.isAcyclic());
        assertDoesNotThrow(PlanningCancellation::check);
    }
}
