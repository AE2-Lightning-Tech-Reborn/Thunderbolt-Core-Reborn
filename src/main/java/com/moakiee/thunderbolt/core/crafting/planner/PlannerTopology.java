package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Immutable adjacency preparation and iterative ordering; independent of stock and quantities. */
final class PlannerTopology {
    private PlannerTopology() {}

    static <K> Map<K, List<K>> freezeAdjacency(
            List<K> nodes, Map<K, LinkedHashSet<K>> mutable) {
        Map<K, List<K>> frozen = new HashMap<>(nodes.size() * 2);
        for (K node : nodes) {
            var neighbors = mutable.get(node);
            frozen.put(node, neighbors == null ? List.of() : List.copyOf(neighbors));
        }
        return frozen;
    }

    static <K> Map<K, Integer> stronglyConnectedComponents(
            List<K> nodes, Map<K, List<K>> adjacency) {
        if (nodes.size() >= 128) {
            var indexed = IndexedGraph.compile(nodes, adjacency);
            if (indexed != null) return indexed.components();
        }
        return genericComponents(nodes, adjacency);
    }

    private static <K> Map<K, Integer> genericComponents(
            List<K> nodes, Map<K, List<K>> adjacency) {
        Set<K> seen = new HashSet<>();
        List<K> finished = new ArrayList<>(nodes.size());
        for (K start : nodes) {
            PlanningCancellation.check();
            if (!seen.add(start)) {
                continue;
            }
            Deque<SccFrame<K>> stack = new ArrayDeque<>();
            stack.push(new SccFrame<>(start, adjacency.getOrDefault(start, List.of())));
            while (!stack.isEmpty()) {
                PlanningCancellation.check();
                SccFrame<K> frame = stack.peek();
                if (frame.next < frame.neighbors.size()) {
                    K next = frame.neighbors.get(frame.next++);
                    if (seen.add(next)) {
                        stack.push(new SccFrame<>(
                                next, adjacency.getOrDefault(next, List.of())));
                    }
                } else {
                    finished.add(frame.node);
                    stack.pop();
                }
            }
        }

        Map<K, List<K>> reverse = new HashMap<>(nodes.size() * 2);
        for (K node : nodes) {
            PlanningCancellation.check();
            reverse.put(node, new ArrayList<>());
        }
        adjacency.forEach((from, targets) -> {
            for (K target : targets) {
                reverse.computeIfAbsent(target, ignored -> new ArrayList<>()).add(from);
            }
        });

        Map<K, Integer> component = new HashMap<>(nodes.size() * 2);
        int nextComponent = 0;
        for (int index = finished.size() - 1; index >= 0; index--) {
            PlanningCancellation.check();
            K start = finished.get(index);
            if (component.containsKey(start)) {
                continue;
            }
            Deque<K> stack = new ArrayDeque<>();
            stack.push(start);
            component.put(start, nextComponent);
            while (!stack.isEmpty()) {
                PlanningCancellation.check();
                K node = stack.pop();
                for (K previous : reverse.getOrDefault(node, List.of())) {
                    if (!component.containsKey(previous)) {
                        component.put(previous, nextComponent);
                        stack.push(previous);
                    }
                }
            }
            nextComponent++;
        }
        return component;
    }

    static <K> List<K> stableTopologicalOrder(
            List<K> stableOrder, Map<K, List<K>> adjacency) {
        if (stableOrder.size() >= 128) {
            var indexed = IndexedGraph.compile(stableOrder, adjacency);
            if (indexed != null) return indexed.topologicalOrder();
        }
        Map<K, Integer> stableIndex = new HashMap<>(stableOrder.size() * 2);
        Map<K, Integer> indegree = new HashMap<>(stableOrder.size() * 2);
        for (int index = 0; index < stableOrder.size(); index++) {
            PlanningCancellation.check();
            K node = stableOrder.get(index);
            stableIndex.put(node, index);
            indegree.put(node, 0);
        }
        adjacency.forEach((ignored, targets) -> {
            for (K target : targets) {
                indegree.merge(target, 1, Integer::sum);
            }
        });

        PriorityQueue<K> ready = new PriorityQueue<>(
                java.util.Comparator.comparingInt(stableIndex::get));
        for (K node : stableOrder) {
            if (indegree.getOrDefault(node, 0) == 0) {
                ready.add(node);
            }
        }
        List<K> result = new ArrayList<>(stableOrder.size());
        while (!ready.isEmpty()) {
            PlanningCancellation.check();
            K node = ready.poll();
            result.add(node);
            for (K target : adjacency.getOrDefault(node, List.of())) {
                int remaining = indegree.merge(target, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(target);
                }
            }
        }
        return result;
    }

    /** Request-local CSR: arbitrary keys are hashed only when indexing arcs, not on every DFS,
     * reverse lookup or heap comparison. Small graphs keep the cheaper generic path above. */
    private static final class IndexedGraph<K> {
        private final List<K> nodes;
        private final int[] offsets;
        private final int[] targets;

        private IndexedGraph(List<K> nodes, int[] offsets, int[] targets) {
            this.nodes = nodes;
            this.offsets = offsets;
            this.targets = targets;
        }

        static <K> IndexedGraph<K> compile(List<K> nodes, Map<K, List<K>> adjacency) {
            int n = nodes.size();
            var ids = new HashMap<K, Integer>(n);
            for (int i = 0; i < n; i++) {
                PlanningCancellation.check();
                if (ids.put(nodes.get(i), i) != null) return null;
            }
            // Incomplete or duplicate caller node lists retain the old generic semantics.
            // Production topology snapshots are closed, but do not change this utility contract.
            for (K source : adjacency.keySet()) {
                PlanningCancellation.check();
                if (!ids.containsKey(source)) return null;
            }
            var offsets = new int[n + 1];
            long edges = 0;
            for (int i = 0; i < n; i++) {
                PlanningCancellation.check();
                var row = adjacency.get(nodes.get(i));
                edges += row == null ? 0 : row.size();
                if (edges > Integer.MAX_VALUE - 8L) return null;
                offsets[i + 1] = (int) edges;
            }
            var targets = new int[(int) edges];
            for (int i = 0; i < n; i++) {
                PlanningCancellation.check();
                var row = adjacency.get(nodes.get(i));
                if (row == null) continue;
                int cursor = offsets[i];
                for (K target : row) {
                    if ((cursor & 255) == 0) PlanningCancellation.check();
                    Integer id = ids.get(target);
                    if (id == null) return null;
                    targets[cursor++] = id;
                }
            }
            return new IndexedGraph<>(nodes, offsets, targets);
        }

        Map<K, Integer> components() {
            int n = nodes.size();
            var reverseOffsets = new int[n + 1];
            for (int e = 0; e < targets.length; e++) {
                if ((e & 255) == 0) PlanningCancellation.check();
                reverseOffsets[targets[e] + 1]++;
            }
            for (int i = 1; i <= n; i++) {
                if ((i & 255) == 0) PlanningCancellation.check();
                reverseOffsets[i] += reverseOffsets[i - 1];
            }
            var reverse = new int[targets.length];
            var cursor = reverseOffsets.clone();
            for (int from = 0; from < n; from++) {
                PlanningCancellation.check();
                for (int e = offsets[from]; e < offsets[from + 1]; e++) {
                    if ((e & 255) == 0) PlanningCancellation.check();
                    reverse[cursor[targets[e]]++] = from;
                }
            }
            var seen = new boolean[n];
            var stack = new int[n];
            var finished = new int[n];
            int finishedCount = 0;
            for (int start = 0; start < n; start++) {
                PlanningCancellation.check();
                if (seen[start]) continue;
                int top = 0;
                stack[0] = start;
                cursor[start] = offsets[start];
                seen[start] = true;
                while (top >= 0) {
                    PlanningCancellation.check();
                    int node = stack[top];
                    if (cursor[node] < offsets[node + 1]) {
                        int next = targets[cursor[node]++];
                        if (!seen[next]) {
                            seen[next] = true;
                            cursor[next] = offsets[next];
                            stack[++top] = next;
                        }
                    } else {
                        finished[finishedCount++] = node;
                        top--;
                    }
                }
            }
            var component = new int[n];
            Arrays.fill(component, -1);
            int nextComponent = 0;
            for (int i = finishedCount - 1; i >= 0; i--) {
                PlanningCancellation.check();
                int start = finished[i];
                if (component[start] >= 0) continue;
                int size = 1;
                stack[0] = start;
                component[start] = nextComponent;
                while (size > 0) {
                    PlanningCancellation.check();
                    int node = stack[--size];
                    for (int e = reverseOffsets[node]; e < reverseOffsets[node + 1]; e++) {
                        if ((e & 255) == 0) PlanningCancellation.check();
                        int previous = reverse[e];
                        if (component[previous] < 0) {
                            component[previous] = nextComponent;
                            stack[size++] = previous;
                        }
                    }
                }
                nextComponent++;
            }
            var result = new HashMap<K, Integer>(n);
            for (int i = 0; i < n; i++) {
                PlanningCancellation.check();
                result.put(nodes.get(i), component[i]);
            }
            return result;
        }

        List<K> topologicalOrder() {
            int n = nodes.size();
            var indegree = new int[n];
            for (int e = 0; e < targets.length; e++) {
                if ((e & 255) == 0) PlanningCancellation.check();
                indegree[targets[e]]++;
            }
            // The integer is the caller's stable rank, so heap comparisons never hash a key.
            var ready = new PriorityQueue<Integer>();
            for (int i = 0; i < n; i++) {
                PlanningCancellation.check();
                if (indegree[i] == 0) ready.add(i);
            }
            var result = new ArrayList<K>(n);
            while (!ready.isEmpty()) {
                PlanningCancellation.check();
                int node = ready.remove();
                result.add(nodes.get(node));
                for (int e = offsets[node]; e < offsets[node + 1]; e++) {
                    if ((e & 255) == 0) PlanningCancellation.check();
                    int next = targets[e];
                    if (--indegree[next] == 0) ready.add(next);
                }
            }
            return result;
        }
    }

    private static final class SccFrame<K> {
        final K node;
        final List<K> neighbors;
        int next;

        private SccFrame(K node, List<K> neighbors) {
            this.node = node;
            this.neighbors = neighbors;
        }
    }
}
