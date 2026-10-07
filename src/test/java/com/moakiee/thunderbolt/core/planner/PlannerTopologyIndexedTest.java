package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class PlannerTopologyIndexedTest {
    @Test void exactComponentIdsAndStableOrderMatchTheOriginalAlgorithm() {
        var random = new Random(20261006L);
        for (int trial = 0; trial < 24; trial++) {
            int n = 128 + random.nextInt(128);
            var nodes = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) nodes.add(i);
            Collections.shuffle(nodes, random);
            var adjacency = new HashMap<Integer, List<Integer>>();
            for (int i = 0; i < n; i++) {
                var row = new ArrayList<Integer>();
                for (int j = 0; j < 5; j++) {
                    int next = random.nextInt(n);
                    if (trial % 2 == 0 || next > i) row.add(nodes.get(next));
                }
                // Empty rows can be omitted, and duplicate arcs must be counted consistently.
                if (!row.isEmpty()) adjacency.put(nodes.get(i), row);
            }
            assertEquals(referenceComponents(nodes, adjacency),
                    PlannerTopology.stronglyConnectedComponents(nodes, adjacency));
            assertEquals(referenceOrder(nodes, adjacency),
                    PlannerTopology.stableTopologicalOrder(nodes, adjacency));
        }
    }

    @Test void denseTraversalBoundsKeyHashingEvenWithWideFanout() {
        int n = 4096;
        var probe = new HashProbe();
        var nodes = new ArrayList<CountedKey>();
        var adjacency = new HashMap<CountedKey, List<CountedKey>>();
        for (int i = 0; i < n; i++) nodes.add(new CountedKey(i, probe));
        adjacency.put(nodes.get(0), nodes.subList(1, n));
        probe.calls = 0;
        assertEquals(nodes, PlannerTopology.stableTopologicalOrder(nodes, adjacency));
        assertTrue(probe.calls <= 8L * n, () -> "hashed keys " + probe.calls + " times");
        probe.calls = 0;
        var components = PlannerTopology.stronglyConnectedComponents(nodes, adjacency);
        assertEquals(n, new HashSet<>(components.values()).size());
        assertTrue(probe.calls <= 8L * n, () -> "hashed keys " + probe.calls + " times");
    }

    @Test void incompleteAndDuplicateNodeListsKeepTheGenericBehavior() {
        var nodes = new ArrayList<Integer>();
        for (int i = 0; i < 128; i++) nodes.add(i);
        var adjacency = new HashMap<Integer, List<Integer>>();
        adjacency.put(0, List.of(128));
        adjacency.put(128, List.of(0));
        assertEquals(referenceComponents(nodes, adjacency),
                PlannerTopology.stronglyConnectedComponents(nodes, adjacency));
        adjacency.clear();
        nodes.add(0);
        assertEquals(referenceComponents(nodes, adjacency),
                PlannerTopology.stronglyConnectedComponents(nodes, adjacency));
        assertEquals(referenceOrder(nodes, adjacency), PlannerTopology.stableTopologicalOrder(nodes, adjacency));
    }

    @Test void reverseArcConstructionAndTopologicalFanoutPropagateCancellation() {
        var nodes = new ArrayList<Integer>();
        for (int i = 0; i < 8192; i++) nodes.add(i);
        var adjacency = Map.of(0, nodes.subList(1, nodes.size()));
        for (boolean scc : List.of(false, true)) {
            var exit = new PlanningExitException("cancel dense topology");
            int[] arcChecks = {0};
            var context = new PlanningAttemptContext() {
                public long deadlineNanos() { return Long.MAX_VALUE; }
                public void report(PlanningDiagnosticSnapshot snapshot) {}
                public void checkpoint() {
                    boolean inside = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                            frame.getClassName().endsWith("PlannerTopology$IndexedGraph")
                                    && frame.getMethodName().equals(scc ? "components" : "topologicalOrder")));
                    if (inside && ++arcChecks[0] == 4) throw exit;
                }
            };
            try (var ignored = PlanningCancellation.bind(context)) {
                assertSame(exit, assertThrows(PlanningExitException.class, () -> {
                    if (scc) PlannerTopology.stronglyConnectedComponents(nodes, adjacency);
                    else PlannerTopology.stableTopologicalOrder(nodes, adjacency);
                }));
            }
            assertEquals(4, arcChecks[0]);
            assertDoesNotThrow(PlanningCancellation::check);
        }
    }

    @Test void interruptedIndexingDoesNotPublishAReusablePartialGraph() {
        var nodes = new ArrayList<Integer>();
        for (int i = 0; i < 256; i++) nodes.add(i);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () ->
                    PlannerTopology.stronglyConnectedComponents(nodes, Map.of()));
        } finally { Thread.interrupted(); }
        assertEquals(256, PlannerTopology.stronglyConnectedComponents(nodes, Map.of()).size());
    }

    @Test void longChainsAreIterativeAndSmallGraphsKeepTheirStableTieBreak() {
        int n = 20000;
        var nodes = new ArrayList<Integer>();
        var adjacency = new HashMap<Integer, List<Integer>>();
        for (int i = 0; i < n; i++) {
            nodes.add(i);
            if (i + 1 < n) adjacency.put(i, List.of(i + 1));
        }
        assertEquals(n, new HashSet<>(PlannerTopology.stronglyConnectedComponents(nodes, adjacency).values()).size());
        assertEquals(nodes, PlannerTopology.stableTopologicalOrder(nodes, adjacency));
        assertEquals(List.of(3, 2, 1, 0), PlannerTopology.stableTopologicalOrder(
                List.of(3, 2, 1, 0), Map.of(3, List.of(0), 2, List.of(1))));
    }

    private static final class HashProbe { long calls; }
    private record CountedKey(int id, HashProbe probe) {
        @Override public int hashCode() { probe.calls++; return id; }
    }

    /** Original iterative Kosaraju traversal, including its component numbering. */
    private static <K> Map<K, Integer> referenceComponents(List<K> nodes, Map<K, List<K>> adjacency) {
        var seen = new HashSet<K>();
        var finished = new ArrayList<K>();
        for (K start : nodes) {
            if (!seen.add(start)) continue;
            var stack = new ArrayDeque<Frame<K>>();
            stack.push(new Frame<>(start, adjacency.getOrDefault(start, List.of())));
            while (!stack.isEmpty()) {
                var frame = stack.peek();
                if (frame.next < frame.neighbors.size()) {
                    K next = frame.neighbors.get(frame.next++);
                    if (seen.add(next)) stack.push(new Frame<>(next, adjacency.getOrDefault(next, List.of())));
                } else { finished.add(frame.node); stack.pop(); }
            }
        }
        var reverse = new HashMap<K, List<K>>();
        for (K node : nodes) reverse.put(node, new ArrayList<>());
        adjacency.forEach((from, row) -> row.forEach(to ->
                reverse.computeIfAbsent(to, ignored -> new ArrayList<>()).add(from)));
        var component = new HashMap<K, Integer>();
        int id = 0;
        for (int i = finished.size() - 1; i >= 0; i--) {
            K start = finished.get(i);
            if (component.containsKey(start)) continue;
            var stack = new ArrayDeque<K>();
            stack.push(start); component.put(start, id);
            while (!stack.isEmpty()) for (K previous : reverse.getOrDefault(stack.pop(), List.of())) {
                if (!component.containsKey(previous)) { component.put(previous, id); stack.push(previous); }
            }
            id++;
        }
        return component;
    }

    private static <K> List<K> referenceOrder(List<K> nodes, Map<K, List<K>> adjacency) {
        var rank = new HashMap<K, Integer>();
        var indegree = new HashMap<K, Integer>();
        for (int i = 0; i < nodes.size(); i++) { rank.put(nodes.get(i), i); indegree.put(nodes.get(i), 0); }
        adjacency.values().forEach(row -> row.forEach(to -> indegree.merge(to, 1, Integer::sum)));
        var ready = new PriorityQueue<K>(Comparator.comparingInt(rank::get));
        for (K node : nodes) if (indegree.get(node) == 0) ready.add(node);
        var result = new ArrayList<K>();
        while (!ready.isEmpty()) {
            K node = ready.remove(); result.add(node);
            for (K next : adjacency.getOrDefault(node, List.of()))
                if (indegree.merge(next, -1, Integer::sum) == 0) ready.add(next);
        }
        return result;
    }

    private static final class Frame<K> {
        final K node;
        final List<K> neighbors;
        int next;
        Frame(K node, List<K> neighbors) { this.node = node; this.neighbors = neighbors; }
    }
}
