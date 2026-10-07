package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/** Stable hyperedge readiness order with one indexed queue entry per material. */
final class ProducibleRouteOrder {
    private ProducibleRouteOrder() {}

    record Ranked<K>(Map<K, Integer> ordinals, Map<K, Set<K>> cycleMembers) {}

    static <K> Ranked<K> rankWithMembership(CraftGraph<K> graph, K target, Map<K, Integer> distances,
            boolean stockSeeded, BiPredicate<CraftPattern<K>, CraftInput<K>> dependency) {
        var index = PrimaryInputIndex.build(graph, target);
        var cycles = index.cyclicMembership();
        return new Ranked<>(rank(graph, index, distances, stockSeeded, dependency), cycles);
    }

    static <K> Map<K, Integer> rank(CraftGraph<K> graph, K target, Map<K, Integer> distances,
            boolean stockSeeded, BiPredicate<CraftPattern<K>, CraftInput<K>> dependency) {
        return rank(graph, PrimaryInputIndex.build(graph, target), distances, stockSeeded, dependency);
    }

    private static <K> Map<K, Integer> rank(CraftGraph<K> graph, PrimaryInputIndex<K> index,
            Map<K, Integer> distances, boolean stockSeeded,
            BiPredicate<CraftPattern<K>, CraftInput<K>> dependency) {
        var keys = index.keys;
        var patterns = index.patterns;
        int n = keys.size(), m = patterns.size();
        int[] output = index.output, pending = new int[m], starts = new int[m + 1];
        int[] heads = new int[n + 1], stamps = new int[n], distance = new int[n];
        var inputs = new IntBuffer();
        for (int p = 0; p < m; p++) {
            if ((p & 1023) == 0) PlanningCancellation.check();
            var pattern = patterns.get(p);
            starts[p] = inputs.size;
            int slot = index.inputStarts[p];
            for (var input : pattern.inputs()) {
                int key = index.inputKeys[slot++];
                if (!dependency.test(pattern, input)) continue;
                // Multiple slots for the same material wait for one readiness event.
                if (stamps[key] == p + 1) continue;
                stamps[key] = p + 1;
                inputs.add(key);
                heads[key + 1]++;
                pending[p]++;
            }
        }
        starts[m] = inputs.size;
        for (int k = 0; k < n; k++) {
            heads[k + 1] += heads[k];
            distance[k] = distances.getOrDefault(keys.get(k), 0);
        }
        int[] consumers = new int[inputs.size], fill = Arrays.copyOf(heads, n);
        for (int p = 0; p < m; p++) {
            if ((p & 4095) == 0) PlanningCancellation.check();
            for (int at = starts[p]; at < starts[p + 1]; at++) consumers[fill[inputs.data[at]]++] = p;
        }
        var queue = new ReadyQueue(distance);
        for (int k = 0; k < n; k++) {
            K key = keys.get(k);
            if (index.patternStarts[k] == index.patternStarts[k + 1])
                queue.offer(k, stockSeeded && graph.stock(key) > 0 ? 0 : 2, 0);
            else if (stockSeeded && graph.stock(key) > 0) queue.offer(k, 1, 0);
        }
        for (int p = 0; p < m; p++) if (pending[p] == 0) queue.offer(output[p], 0, 1);
        var result = new HashMap<K, Integer>();
        while (queue.size > 0) {
            PlanningCancellation.check();
            int key = queue.remove();
            result.put(keys.get(key), result.size());
            for (int at = heads[key]; at < heads[key + 1]; at++) {
                int p = consumers[at];
                if (queue.settled(output[p])) continue;
                // Queue pops are monotone in (tier, level), so this is the latest dependency.
                if (--pending[p] == 0) queue.offer(output[p], queue.tier[key], queue.level[key] + 1);
            }
        }
        return result;
    }

    /** Decreasing a pending key's label repairs its one heap position in place. */
    private static final class ReadyQueue {
        final int[] heap, position, tier, level, distance;
        int size;

        ReadyQueue(int[] distance) {
            int n = distance.length;
            this.distance = distance;
            heap = new int[n]; position = new int[n]; tier = new int[n]; level = new int[n];
            Arrays.fill(position, -1);
        }

        boolean settled(int key) { return position[key] == -2; }

        void offer(int key, int candidateTier, int candidateLevel) {
            int at = position[key];
            if (at == -2 || at >= 0 && (tier[key] < candidateTier
                    || tier[key] == candidateTier && level[key] <= candidateLevel)) return;
            tier[key] = candidateTier;
            level[key] = candidateLevel;
            if (at == -1) at = size++;
            while (at > 0) {
                int parent = (at - 1) >>> 1;
                int other = heap[parent];
                if (!before(key, other)) break;
                heap[at] = other;
                position[other] = at;
                at = parent;
            }
            heap[at] = key;
            position[key] = at;
        }

        int remove() {
            int first = heap[0], last = heap[--size];
            position[first] = -2;
            if (size == 0) return first;
            int at = 0;
            while (at < size / 2) {
                int child = 2 * at + 1;
                if (child + 1 < size && before(heap[child + 1], heap[child])) child++;
                int other = heap[child];
                if (!before(other, last)) break;
                heap[at] = other;
                position[other] = at;
                at = child;
            }
            heap[at] = last;
            position[last] = at;
            return first;
        }

        private boolean before(int left, int right) {
            if (tier[left] != tier[right]) return tier[left] < tier[right];
            if (level[left] != level[right]) return level[left] < level[right];
            if (distance[left] != distance[right]) return distance[left] > distance[right];
            return left < right;
        }
    }

    private static final class IntBuffer {
        int[] data = new int[64];
        int size;
        void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data, Math.multiplyExact(size, 2));
            data[size++] = value;
        }
    }
}
