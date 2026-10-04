package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
            frozen.put(node, List.copyOf(mutable.getOrDefault(node, new LinkedHashSet<>())));
        }
        return frozen;
    }

    static <K> Map<K, Integer> stronglyConnectedComponents(
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
