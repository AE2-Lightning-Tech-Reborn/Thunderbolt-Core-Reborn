package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class BatchCertificationCancellationTest {
    @Test void stopsInventoryCollectionBeforeFinishingAWideInputRow() {
        assertBoundedCancellation("inventoryFor");
    }

    @Test void stopsTrimBeforeFinishingAWideInputRow() {
        assertBoundedCancellation("trim");
    }

    @Test void stopsMaterialReplayBeforeFinishingAWideInputRow() {
        assertBoundedCancellation("MaterialDagReplay");
    }

    private static void assertBoundedCancellation(String site) {
        int width = 4096;
        var probe = new Probe(site, width);
        var target = new Key(-1, probe);
        var inputsA = new ArrayList<CraftInput<Key>>();
        var inputsB = new ArrayList<CraftInput<Key>>();
        var stock = new HashMap<Key, Long>();
        var builder = CraftGraph.<Key>builder();
        for (int i = 0; i < width; i++) {
            var key = new Key(i, probe);
            inputsA.add(CraftInput.of(key, 1));
            inputsB.add(CraftInput.of(key, 3));
            builder.stock(key, 4);
            stock.put(key, 4L);
        }
        var first = new CraftPattern<>(target, 2, inputsA, null);
        var second = new CraftPattern<>(target, 4, inputsB, null);
        var graph = builder.pattern(first).pattern(second).build();
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(first, 3L), target, 5);
        assertNotNull(incumbent);
        var originalFirings = Map.copyOf(incumbent.firings());
        var originalStock = Map.copyOf(incumbent.usedStock());
        var proposed = Map.of(first, 1L, second, 1L);
        // Arm only after fixture setup. The first raw-key access in the selected stage issues
        // cancellation, so an outer per-pattern checkpoint alone cannot satisfy this test.
        probe.armed = true;
        try {
            assertThrows(CancellationException.class, () -> {
                switch (site) {
                    case "inventoryFor" -> OrdinaryBatchOptimizer.tryImprove(graph, target, 5, incumbent,
                            List.of(target), work -> true, () -> true);
                    case "trim" -> OrdinaryBatchOptimizer.certify(graph, proposed, incumbent, target, 5,
                            stock, work -> true, () -> true);
                    default -> MaterialDagReplay.tryPlan(graph, proposed, target, 5);
                }
            });
            assertTrue(probe.triggered);
            assertTrue(probe.distinct > 0 && probe.distinct <= 256,
                    () -> site + " visited " + probe.distinct + " inputs after cancellation");
        } finally {
            probe.armed = false;
            Thread.interrupted();
        }
        assertEquals(originalFirings, incumbent.firings());
        assertEquals(originalStock, incumbent.usedStock());
    }

    private static final class Probe {
        final String site;
        final boolean[] visited;
        boolean armed;
        boolean triggered;
        int distinct;

        Probe(String site, int width) { this.site = site; visited = new boolean[width]; }

        void observe(int id) {
            if (!armed || id < 0) return;
            if (!triggered && id == 0 && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    site.equals("MaterialDagReplay") ? frame.getClassName().endsWith("MaterialDagReplay")
                            : frame.getClassName().endsWith("OrdinaryBatchOptimizer")
                                    && frame.getMethodName().equals(site)))) {
                triggered = true;
                Thread.currentThread().interrupt();
            }
            if (triggered && !visited[id]) { visited[id] = true; distinct++; }
        }
    }

    private record Key(int id, Probe probe) {
        @Override public int hashCode() { probe.observe(id); return id; }
    }
}
