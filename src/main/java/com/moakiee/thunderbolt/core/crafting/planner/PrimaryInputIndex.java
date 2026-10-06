package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One stable BFS index for structural cycles and production readiness. Every registered primary
 * input slot is retained, including duplicate, returned and host-backed inputs. Readiness may
 * ignore some slots later; structural SCC membership must still see the original graph.
 */
final class PrimaryInputIndex<K> {
    final List<K> keys;
    final List<CraftPattern<K>> patterns;
    final int[] output, patternStarts, inputStarts, inputKeys;

    private PrimaryInputIndex(List<K> keys, List<CraftPattern<K>> patterns,
            int[] output, int[] patternStarts, int[] inputStarts, int[] inputKeys) {
        this.keys = keys;
        this.patterns = patterns;
        this.output = output;
        this.patternStarts = patternStarts;
        this.inputStarts = inputStarts;
        this.inputKeys = inputKeys;
    }

    static <K> PrimaryInputIndex<K> build(CraftGraph<K> graph, K target) {
        var ids = new HashMap<K, Integer>();
        var keys = new ArrayList<K>();
        var patterns = new ArrayList<CraftPattern<K>>();
        var output = new IntBuffer();
        var patternStarts = new IntBuffer();
        var inputStarts = new IntBuffer();
        var inputKeys = new IntBuffer();
        ids.put(target, 0);
        keys.add(target);
        for (int k = 0; k < keys.size(); k++) {
            PlanningCancellation.check();
            patternStarts.add(patterns.size());
            for (var pattern : graph.patternsFor(keys.get(k))) {
                if ((patterns.size() & 1023) == 0) PlanningCancellation.check();
                patterns.add(pattern);
                output.add(k);
                inputStarts.add(inputKeys.size);
                for (var input : pattern.inputs()) {
                    if ((inputKeys.size & 4095) == 0) PlanningCancellation.check();
                    Integer id = ids.get(input.key());
                    if (id == null) {
                        id = keys.size();
                        ids.put(input.key(), id);
                        keys.add(input.key());
                    }
                    inputKeys.add(id);
                }
            }
        }
        patternStarts.add(patterns.size());
        inputStarts.add(inputKeys.size);
        return new PrimaryInputIndex<>(keys, patterns, output.finish(), patternStarts.finish(),
                inputStarts.finish(), inputKeys.finish());
    }

    /** Iterative Tarjan traversal over existing slot storage; no reverse graph or object frames. */
    Map<K, Set<K>> cyclicMembership() {
        int n = keys.size();
        int[] discovered = new int[n], low = new int[n], cursor = new int[n];
        int[] frames = new int[n], active = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(discovered, -1);
        for (int k = 0; k < n; k++) cursor[k] = inputStarts[patternStarts[k]];
        var result = new HashMap<K, Set<K>>();
        int serial = 0, activeSize = 0, visits = 0;
        for (int root = 0; root < n; root++) {
            if (discovered[root] >= 0) continue;
            int depth = 0;
            discovered[root] = low[root] = serial++;
            frames[depth++] = root;
            active[activeSize++] = root;
            onStack[root] = true;
            while (depth > 0) {
                if ((visits++ & 4095) == 0) PlanningCancellation.check();
                int node = frames[depth - 1];
                int end = inputStarts[patternStarts[node + 1]];
                if (cursor[node] < end) {
                    int next = inputKeys[cursor[node]++];
                    if (discovered[next] < 0) {
                        discovered[next] = low[next] = serial++;
                        frames[depth++] = next;
                        active[activeSize++] = next;
                        onStack[next] = true;
                    } else if (onStack[next]) {
                        low[node] = Math.min(low[node], discovered[next]);
                    }
                    continue;
                }
                depth--;
                if (depth > 0) {
                    int parent = frames[depth - 1];
                    low[parent] = Math.min(low[parent], low[node]);
                }
                if (low[node] != discovered[node]) continue;
                if (active[activeSize - 1] == node) {
                    activeSize--;
                    onStack[node] = false;
                    for (int at = inputStarts[patternStarts[node]]; at < end; at++) {
                        if ((at & 4095) == 0) PlanningCancellation.check();
                        if (inputKeys[at] == node) {
                            result.put(keys.get(node), Set.of(keys.get(node)));
                            break;
                        }
                    }
                } else {
                    var members = new HashSet<K>();
                    int member;
                    do {
                        if ((activeSize & 4095) == 0) PlanningCancellation.check();
                        member = active[--activeSize];
                        onStack[member] = false;
                        members.add(keys.get(member));
                    } while (member != node);
                    Set<K> frozen = Set.copyOf(members);
                    for (K key : frozen) result.put(key, frozen);
                }
            }
        }
        return result;
    }

    private static final class IntBuffer {
        private int[] data = new int[64];
        private int size;

        void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data, Math.multiplyExact(size, 2));
            data[size++] = value;
        }

        int[] finish() { return Arrays.copyOf(data, size); }
    }
}
