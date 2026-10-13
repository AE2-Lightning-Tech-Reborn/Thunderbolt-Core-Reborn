package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialDagMaskTest {
    @Test
    void sixIntermediatesKeepBothCycleCutsAndRejectSelfSideDependencies() {
        var fromB = new CraftPattern<>("A", 2, List.of(CraftInput.of("B", 1)), "shared");
        var fromA = new CraftPattern<>("B", 2, List.of(CraftInput.of("A", 1)), "shared");
        var selfSide = new CraftPattern<>("F", 1, List.of(CraftInput.of("A", 1)),
                List.of(CraftOutput.of("A", 1)), "self-side");
        var side = new CraftPattern<>("C", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("A", 1), CraftOutput.of("outside", 1)), "side");
        var root = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1),
                CraftInput.of("C", 1), CraftInput.of("D", 1), CraftInput.of("E", 1),
                CraftInput.of("F", 1)), "root");
        var builder = CraftGraph.<String>builder().stock("raw", 100).pattern(root)
                .pattern(fromB).pattern(fromB).pattern(fromA).pattern(side).pattern(selfSide);
        for (String key : List.of("A", "B", "D", "E", "F"))
            builder.pattern(key, 1, List.of(CraftInput.of("raw", 1), CraftInput.of("raw", 1)));
        var graph = builder.build();
        var original = MaterialDagOrders.compile(graph, "T").stream()
                .filter(candidate -> candidate.originals().isEmpty()).toList();
        assertEquals(2, original.size());
        for (var candidate : original) {
            var order = candidate.supplyOrder();
            assertSame(root, order.getLast());
            assertTrue(order.contains(side));
            assertFalse(order.contains(selfSide), "an input must precede every reachable side output");
            assertNotEquals(order.contains(fromA), order.contains(fromB));
            assertEquals(order.contains(fromB) ? 2L : 0L,
                    order.stream().filter(pattern -> pattern == fromB).count());
            assertTrue(CraftPlannerV2.plan(candidate.graph(), "T", 1, 1, 1).feasible());
        }
        assertTrue(original.getFirst().supplyOrder().contains(fromA));
        assertTrue(original.getLast().supplyOrder().contains(fromB));
        assertEquals(0L, graph.stock("outside"));
    }
}
