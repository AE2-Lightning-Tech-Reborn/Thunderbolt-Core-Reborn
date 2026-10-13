package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialFootprintIndexTest {
    @Test
    void equalShapesKeepBothPatternIdentitiesInAnIndependentReadOnlyMap() {
        Object source = new Object();
        var first = new CraftPattern<>("target", 1, List.of(CraftInput.of("raw", 1)), source);
        var second = new CraftPattern<>("target", 1, first.inputs(), source);
        var graph = CraftGraph.<String>builder().pattern(first).pattern(second).build();
        var selected = new HashMap<String, List<CraftPattern<String>>>();
        selected.put("target", List.of(first, second, first));

        var index = MaterialFootprintIndex.build(graph, List.of("target", "raw"), selected);
        selected.clear();

        assertEquals(2, index.size());
        assertNotNull(index.get(first));
        assertEquals(index.get(first), index.get(second));
        assertTrue(index.keySet().stream().anyMatch(pattern -> pattern == first));
        assertTrue(index.keySet().stream().anyMatch(pattern -> pattern == second));
        assertFalse(index.containsKey(new CraftPattern<>("target", 1, first.inputs(), source)));
        assertThrows(UnsupportedOperationException.class, () -> index.put(first, 99));
        assertThrows(UnsupportedOperationException.class,
                () -> index.entrySet().iterator().next().setValue(99));
        assertThrows(UnsupportedOperationException.class, () -> index.keySet().remove(first));
        assertThrows(UnsupportedOperationException.class, index::clear);
    }

    @Test
    void inventoryProjectionsMakeOnlyStockedIntermediatesIntoLeafBarriers() {
        var viaLeft = ordinary("target", "left");
        var viaRight = ordinary("target", "right");
        var left = ordinary("left", "raw");
        var right = ordinary("right", "raw");
        var graph = CraftGraph.<String>builder().pattern(viaLeft).pattern(viaRight)
                .pattern(left).pattern(right).stock("raw", 10).build();
        var selected = Map.of("target", List.of(viaLeft, viaRight),
                "left", List.of(left), "right", List.of(right));
        var order = List.of("target", "left", "right", "raw");

        var original = MaterialFootprintIndex.build(graph, order, selected);
        var stocked = graph.withAdditionalStock(Map.of("left", 1L));
        var stockedIndex = MaterialFootprintIndex.build(stocked, order, selected);
        var limited = stocked.withStockLimits(Map.of("raw", 10L));
        var limitedIndex = MaterialFootprintIndex.build(limited, order, selected);

        assertNotNull(original.get(viaLeft));
        assertEquals(original.get(viaLeft), original.get(viaRight));
        assertNotNull(stockedIndex.get(left));
        assertNotNull(stockedIndex.get(viaLeft));
        assertNotNull(stockedIndex.get(viaRight));
        assertEquals(stockedIndex.get(left), stockedIndex.get(right),
                "existing output stock must not alter the producer's own recipe shape");
        assertNotEquals(stockedIndex.get(viaLeft), stockedIndex.get(viaRight),
                "a parent must distinguish the stocked concrete intermediate");
        assertEquals(0L, limited.stock("left"));
        assertNotNull(limitedIndex.get(viaLeft));
        assertEquals(limitedIndex.get(viaLeft), limitedIndex.get(viaRight));
        assertEquals(original.get(viaLeft), original.get(viaRight),
                "later builds must not mutate the earlier index");
    }

    @Test
    void explicitlyCutProducersBecomeRawEvenWhenTheOriginalGraphStillHasRecipes() {
        var viaLeft = ordinary("target", "left");
        var viaRight = ordinary("target", "right");
        var left = ordinary("left", "raw");
        var right = ordinary("right", "raw");
        var graph = CraftGraph.<String>builder().pattern(viaLeft).pattern(viaRight)
                .pattern(left).pattern(right).build();
        var selected = new HashMap<String, List<CraftPattern<String>>>();
        selected.put("target", List.of(viaLeft, viaRight));
        selected.put("left", List.of(left));
        selected.put("right", List.of(right));
        var order = List.of("target", "left", "right", "raw");
        var uncut = MaterialFootprintIndex.build(graph, order, selected);

        selected.put("left", List.of());
        var cut = MaterialFootprintIndex.build(graph, order, selected);

        assertEquals(List.of(left), graph.patternsFor("left"));
        assertNotNull(uncut.get(viaLeft));
        assertEquals(uncut.get(viaLeft), uncut.get(viaRight));
        assertNotNull(cut.get(viaLeft));
        assertNotNull(cut.get(viaRight));
        assertNotEquals(cut.get(viaLeft), cut.get(viaRight));
        assertFalse(cut.containsKey(left));
        assertTrue(cut.containsKey(right));
    }

    @Test
    void equalBatchRecipesStillKeepTheirDistinctSurplusPoolsAtTheirParents() {
        var viaLeft = ordinary("target", "left");
        var viaRight = ordinary("target", "right");
        var left = new CraftPattern<>("left", 2, List.of(CraftInput.of("raw", 1)), null);
        var right = new CraftPattern<>("right", 2, List.of(CraftInput.of("raw", 1)), null);
        var graph = CraftGraph.<String>builder().pattern(viaLeft).pattern(viaRight)
                .pattern(left).pattern(right).build();
        var selected = Map.of("target", List.of(viaLeft, viaRight),
                "left", List.of(left), "right", List.of(right));

        var index = MaterialFootprintIndex.build(graph,
                List.of("target", "left", "right", "raw"), selected);

        assertNotNull(index.get(left));
        assertEquals(index.get(left), index.get(right));
        assertNotNull(index.get(viaLeft));
        assertNotNull(index.get(viaRight));
        assertNotEquals(index.get(viaLeft), index.get(viaRight),
                "equal recipes cannot merge surplus held under different output keys");
    }

    @Test
    void byproductAndReusableInputPoolsPreventParentEquivalenceWithoutClassifyingTheirOwner() {
        var viaLeft = ordinary("target", "left");
        var viaRight = ordinary("target", "right");
        var left = ordinary("left", "raw");
        var right = ordinary("right", "raw");
        var barriers = List.of(
                new CraftPattern<>("owner", 1, List.of(CraftInput.of("aux", 1)),
                        List.of(CraftOutput.of("left", 1)), null),
                new CraftPattern<>("owner", 1, List.of(CraftInput.returned("left", 1)), null),
                new CraftPattern<>("owner", 1,
                        List.of(CraftInput.returnedFrom("left", 1,
                                new ReusableStockSource("host", "pool"))), null),
                new CraftPattern<>("owner", 1,
                        List.of(CraftInput.consumedReturning("aux", 1, "left")), null));
        for (var barrier : barriers) {
            var graph = CraftGraph.<String>builder().pattern(viaLeft).pattern(viaRight)
                    .pattern(left).pattern(right).pattern(barrier).build();
            var selected = Map.of("target", List.of(viaLeft, viaRight),
                    "left", List.of(left), "right", List.of(right), "owner", List.of(barrier));

            var index = MaterialFootprintIndex.build(graph,
                    List.of("target", "owner", "left", "right", "raw", "aux"), selected);

            assertNotNull(index.get(viaLeft));
            assertNotNull(index.get(viaRight));
            assertNotNull(index.get(left));
            assertEquals(index.get(left), index.get(right));
            assertNotEquals(index.get(viaLeft), index.get(viaRight), barrier.toString());
            assertFalse(index.containsKey(barrier), "stateful/byproduct recipes remain unclassified");
        }
    }

    @Test
    void cancellationPropagatesFromBothTheMetadataScanAndBottomUpTraversal() {
        var pattern = ordinary("target", "raw");
        var graph = CraftGraph.<String>builder().pattern(pattern).build();
        var selected = Map.of("target", List.of(pattern));
        var order = List.of("target", "raw");
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> MaterialFootprintIndex.build(graph, order, selected));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        var exit = new PlanningExitException("attempt ended during footprint traversal");
        int[] checkpoints = {0};
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void checkpoint() {
                if (++checkpoints[0] == 2) throw exit;
            }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) { }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> MaterialFootprintIndex.build(graph, order, selected)));
        }
        assertEquals(2, checkpoints[0]);
    }

    private static CraftPattern<String> ordinary(String output, String input) {
        return new CraftPattern<>(output, 1, List.of(CraftInput.of(input, 1)), null);
    }
}
