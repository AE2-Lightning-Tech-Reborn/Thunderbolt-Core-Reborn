package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class MaterialDagReplayCancellationTest {
    @Test void directCertificateStopsWithinAWideByproductRow() {
        checkCancellation(false, false);
    }

    @Test void publicPlannerStopsWithinAWideByproductCertificate() {
        checkCancellation(true, false);
    }

    @Test void directCertificateStopsWithinAWideTopologicalFanout() {
        checkCancellation(false, true);
    }

    @Test void publicPlannerStopsWithinAWideTopologicalFanout() {
        checkCancellation(true, true);
    }

    private static void checkCancellation(boolean publicEntry, boolean topology) {
        int width = 4096;
        var probe = new Probe(width, topology);
        var target = new Key(-1, probe);
        var product = new Key(-2, probe);
        var intermediate = new Key(-3, probe);
        var credit = new Key(-4, probe);
        var rawA = new Key(-5, probe);
        var rawB = new Key(-6, probe);
        var sideOutputs = new ArrayList<CraftOutput<Key>>();
        for (int i = 0; i < width; i++) sideOutputs.add(CraftOutput.of(new Key(i, probe), 1));
        // Reduced from material-DAG fixture 4192. The unused reverse route exposes the core
        // certificate path; unused target side outputs preserve its original executable vector.
        var reverse = new CraftPattern<>(intermediate, 1, List.of(CraftInput.of(product, 1)), null);
        var producer = new CraftPattern<>(product, 1, List.of(CraftInput.of(rawA, 1),
                CraftInput.of(rawB, 1), CraftInput.of(intermediate, 1)), List.of(CraftOutput.of(credit, 2)), null);
        var root = new CraftPattern<>(target, 1, List.of(CraftInput.of(credit, 1), CraftInput.of(product, 1)),
                sideOutputs, null);
        var graph = CraftGraph.<Key>builder().stock(rawA, 1).stock(rawB, 2).stock(intermediate, 2).stock(product, 1)
                .pattern(reverse).pattern(producer).pattern(root).build();
        var firings = Map.of(producer, 1L, root, 1L);
        var expectedStock = Map.of(rawA, 1L, rawB, 1L, intermediate, 1L);
        var healthy = publicEntry ? CraftPlannerV2.plan(graph, target, 1)
                : MaterialDagReplay.tryPlan(graph, firings, target, 1);
        assertNotNull(healthy);
        assertTrue(healthy.feasible());
        assertEquals(firings, healthy.firings());
        assertEquals(expectedStock, healthy.usedStock());

        probe.armed = true;
        try {
            assertThrows(CancellationException.class, () -> {
                if (publicEntry) CraftPlannerV2.plan(graph, target, 1);
                else MaterialDagReplay.tryPlan(graph, firings, target, 1);
            });
            assertTrue(probe.triggered, "the public route must actually enter the material certificate");
            assertTrue(probe.distinct > 0 && probe.distinct < 256,
                    () -> "visited " + probe.distinct + " side-output keys after cancellation");
        } finally {
            probe.armed = false;
            Thread.interrupted();
        }
        assertEquals(firings, healthy.firings());
        assertEquals(expectedStock, healthy.usedStock());
    }

    private static final class Probe {
        final boolean[] visited;
        final boolean topology;
        boolean armed, triggered;
        int distinct;

        Probe(int width, boolean topology) { visited = new boolean[width]; this.topology = topology; }

        void observe(int id) {
            if (!armed || id < 0) return;
            if (!triggered && (topology || id == 0) && StackWalker.getInstance().walk(frames -> {
                boolean merging = false;
                var iterator = frames.iterator();
                while (iterator.hasNext()) {
                    var frame = iterator.next();
                    merging |= frame.getClassName().equals("java.util.HashMap") && frame.getMethodName().equals("merge");
                    if (!frame.getClassName().endsWith("MaterialDagReplay")) continue;
                    // Topology uses merge directly. Ignore merges in balance/edge construction;
                    // HashSet encounter order also means the first successor need not have id 0.
                    if (topology && (frame.getMethodName().equals("add") || frame.getMethodName().equals("edge")))
                        return false;
                    if (frame.getMethodName().equals("tryPlan")) return !topology || merging;
                }
                return false;
            })) {
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
