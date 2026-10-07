package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
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
        try (var ignored = PlanningCancellation.bind(new PlanningAttemptContext() {
            private final StackWalker callers = StackWalker.getInstance();
            private int topologyChecks;
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() {
                if (!topology) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    return;
                }
                // Integer adjacency does not hash material keys while traversing. Trigger at
                // the actual traversal checkpoint inside the wide root's outgoing arcs instead.
                boolean inTopology = callers.walk(frames -> frames.skip(2).findFirst()
                        .map(frame -> frame.getClassName().equals(MaterialDagReplay.class.getName() + "$MaterialTopology")
                                && frame.getMethodName().equals("isDag"))
                        .orElse(false));
                // width + eight core nodes: initialization checks, then seven core nodes,
                // then the first checkpoint inside the root's width outgoing edges.
                if (inTopology && ++topologyChecks == (width + 8) / 256 + 8) {
                    probe.triggered = true;
                    throw new CancellationException("cancel inside compact topology fanout");
                }
            }
        })) {
            assertThrows(CancellationException.class, () -> {
                if (publicEntry) CraftPlannerV2.plan(graph, target, 1);
                else MaterialDagReplay.tryPlan(graph, firings, target, 1);
            });
            assertTrue(probe.triggered, "the public route must actually enter the material certificate");
            assertTrue(topology || probe.distinct > 0 && probe.distinct < 256,
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
            if (!armed || topology || id < 0) return;
            if (!triggered && id == 0 && StackWalker.getInstance().walk(frames -> {
                var iterator = frames.iterator();
                while (iterator.hasNext()) {
                    var frame = iterator.next();
                    if (!frame.getClassName().endsWith("MaterialDagReplay")) continue;
                    if (frame.getMethodName().equals("tryPlan")) return true;
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
