package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles conservative material equivalence for one frozen graph, dependency order and selected
 * producer table. Direct inventory and possible surplus keep an intermediate's concrete identity.
 *
 * <p>Ids are local to one build and cannot be compared across stock or producer projections. The
 * returned table is immutable and keyed by pattern identity; no graph or mutable request state is
 * retained. It supports local pruning in the planner's bounded long arithmetic only and must never
 * replace the selected producer table: distinct exact amounts can share the same bounded amount,
 * and exact continuation still needs every original producer. Cancellation checks run before the
 * completed table is published by the caller.
 */
final class MaterialFootprintIndex {
    private MaterialFootprintIndex() {
    }

    /**
     * Gives simple deterministic recipe trees a canonical material-transformation id. Intermediate
     * item names disappear from the shape, so {@code E→C→A1} and {@code E→D→A2} receive the same id,
     * while exact batch sizes and branching structure remain part of it.
     *
     * <p>This is deliberately a proof, not a heuristic. A craftable intermediate with direct stock or
     * possible dynamic pool credit keeps its own identity, and patterns with returned inputs,
     * remainders, reusable hosts or byproducts are left unclassified. Those routes still search
     * normally; only a pair with identical classified ids may skip a repeated failed expansion.
     */
    static <K> Map<CraftPattern<K>, Integer> build(
            CraftGraph<K> graph, List<K> order,
            Map<K, List<CraftPattern<K>>> patternsByOutput) {
        Map<CraftPattern<K>, Integer> footprints = new IdentityHashMap<>();
        Set<K> dynamicPoolKeys = new HashSet<>();
        int metadataWork = 0;
        for (Map.Entry<K, List<CraftPattern<K>>> entry : patternsByOutput.entrySet()) {
            PlanningCancellation.check();
            var routes = entry.getValue();
            for (int p = 0; p < routes.size(); p++) {
                CraftPattern<K> pattern = routes.get(p);
                if ((++metadataWork & 255) == 0) PlanningCancellation.check();
                if (pattern.outputAmount() > 1) {
                    dynamicPoolKeys.add(pattern.output());
                }
                var byproducts = pattern.byproducts();
                for (int slot = 0; slot < byproducts.size(); slot++) {
                    CraftOutput<K> output = byproducts.get(slot);
                    if ((++metadataWork & 255) == 0) PlanningCancellation.check();
                    dynamicPoolKeys.add(output.key());
                }
                var inputs = pattern.inputs();
                for (int slot = 0; slot < inputs.size(); slot++) {
                    CraftInput<K> input = inputs.get(slot);
                    if ((++metadataWork & 255) == 0) PlanningCancellation.check();
                    if (input.returned() || input.remainder() != null
                            || input.reusableStockSource() != null) {
                        dynamicPoolKeys.add(input.key());
                        if (input.remainder() != null) {
                            dynamicPoolKeys.add(input.remainder());
                        }
                    }
                }
            }
        }

        FootprintInterner interner = new FootprintInterner();
        int[] scratchIds = new int[8];
        long[] scratchAmounts = new long[8];
        Map<K, Integer> footprintByKey = new HashMap<>();
        int patternsVisited = 0;
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanningCancellation.check();
            K key = order.get(i);
            List<CraftPattern<K>> patterns = patternsByOutput.getOrDefault(key, List.of());
            if (patterns.isEmpty()) {
                footprintByKey.put(key, interner.intern(new MaterialLeaf(key)));
                continue;
            }

            Integer common = null;
            boolean allEquivalent = true;
            for (int p = 0; p < patterns.size(); p++) {
                CraftPattern<K> pattern = patterns.get(p);
                if ((++patternsVisited & 255) == 0) PlanningCancellation.check();
                Integer footprint = materialFootprint(pattern, footprintByKey, interner, scratchIds, scratchAmounts);
                if (footprint != null) {
                    footprints.put(pattern, footprint);
                }
                if (footprint == null) {
                    allEquivalent = false;
                } else if (common == null) {
                    common = footprint;
                } else if (!common.equals(footprint)) {
                    allEquivalent = false;
                }
            }

            // Direct stock and dynamic surplus/byproduct credit belong to this concrete intermediate,
            // not merely to its production tree. Keep its identity when it is used by a parent.
            if (graph.stock(key) > 0 || dynamicPoolKeys.contains(key)
                    || !allEquivalent || common == null) {
                footprintByKey.put(key, interner.intern(new MaterialLeaf(key)));
            } else {
                footprintByKey.put(key, common);
            }
        }
        PlanningCancellation.check();
        return java.util.Collections.unmodifiableMap(footprints);
    }

    private static <K> Integer materialFootprint(
            CraftPattern<K> pattern,
            Map<K, Integer> footprintByKey,
            FootprintInterner interner, int[] scratchIds, long[] scratchAmounts) {
        if (!pattern.byproducts().isEmpty()) {
            return null;
        }

        if (pattern.inputs().size() <= scratchIds.length) {
            int size = 0;
            // Small recipes share sorted scratch storage; size limits all reads to this recipe.
            var inputs = pattern.inputs();
            for (int slot = 0; slot < inputs.size(); slot++) {
                CraftInput<K> input = inputs.get(slot);
                if (input.returned() || input.remainder() != null
                        || input.reusableStockSource() != null) return null;
                Integer footprint = footprintByKey.get(input.key());
                if (footprint == null) return null;
                int at = 0;
                while (at < size && scratchIds[at] < footprint) at++;
                if (at < size && scratchIds[at] == footprint) {
                    if (Long.MAX_VALUE - scratchAmounts[at] < input.amount()) return null;
                    scratchAmounts[at] += input.amount();
                } else {
                    System.arraycopy(scratchIds, at, scratchIds, at + 1, size - at);
                    System.arraycopy(scratchAmounts, at, scratchAmounts, at + 1, size - at);
                    scratchIds[at] = footprint;
                    scratchAmounts[at] = input.amount();
                    size++;
                }
            }
            List<MaterialTerm> terms = new ArrayList<>(size);
            for (int at = 0; at < size; at++) terms.add(new MaterialTerm(scratchIds[at], scratchAmounts[at]));
            return interner.intern(new MaterialRecipe(pattern.outputAmount(), List.copyOf(terms)));
        }
        Map<Integer, Long> amounts = new HashMap<>();
        int inputIndex = 0;
        var inputs = pattern.inputs();
        for (int slot = 0; slot < inputs.size(); slot++) {
            CraftInput<K> input = inputs.get(slot);
            if ((++inputIndex & 255) == 0) PlanningCancellation.check();
            if (input.returned() || input.remainder() != null
                    || input.reusableStockSource() != null) {
                return null;
            }
            Integer inputFootprint = footprintByKey.get(input.key());
            if (inputFootprint == null) {
                return null;
            }
            long previous = amounts.getOrDefault(inputFootprint, 0L);
            if (Long.MAX_VALUE - previous < input.amount()) {
                return null; // exact proof only; never merge two different saturated totals
            }
            amounts.put(inputFootprint, previous + input.amount());
        }

        List<MaterialTerm> terms = new ArrayList<>(amounts.size());
        for (Map.Entry<Integer, Long> entry : amounts.entrySet()) {
            if ((++inputIndex & 255) == 0) PlanningCancellation.check();
            terms.add(new MaterialTerm(entry.getKey(), entry.getValue()));
        }
        terms.sort((left, right) -> Integer.compare(left.footprint(), right.footprint()));
        return interner.intern(new MaterialRecipe(pattern.outputAmount(), List.copyOf(terms)));
    }

    private record MaterialLeaf(Object key) {
    }

    private record MaterialTerm(int footprint, long amount) {
    }

    private record MaterialRecipe(long outputAmount, List<MaterialTerm> inputs) {
    }

    private static final class FootprintInterner {
        private final Map<Object, Integer> ids = new HashMap<>();

        private int intern(Object shape) {
            Integer existing = ids.get(shape);
            if (existing != null) {
                return existing;
            }
            int id = ids.size() + 1;
            ids.put(shape, id);
            return id;
        }
    }
}
