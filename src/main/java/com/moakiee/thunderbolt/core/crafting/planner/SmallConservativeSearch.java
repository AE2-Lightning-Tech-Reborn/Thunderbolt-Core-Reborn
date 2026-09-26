package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Optional full-cycle recovery for small ordinary, unit-weight non-growing graphs. */
final class SmallConservativeSearch {
    static final int MAX_WORK = 128;
    static final int MAX_STATES = 4_096;
    static final int MAX_FIRINGS = 32;
    static final long MAX_NANOS = 20_000_000L;
    private static final int MAX_ITEMS = 12;
    private static final int MAX_PATTERNS = 16;
    private static final int MAX_STOCK = 256;

    private SmallConservativeSearch() {}

    /** Null means no certified improvement within this portfolio, never an infeasibility proof. */
    static <K> CraftPlan<K> tryPlan(CraftGraph<K> graph, K target, long amount, int stateLimit) {
        return tryPlan(graph, target, amount, stateLimit, () -> true);
    }

    static <K> CraftPlan<K> tryPlan(CraftGraph<K> graph, K target, long amount, int stateLimit,
            BooleanSupplier tryConsumeWork) {
        if (amount <= 0 || amount > MAX_STOCK || stateLimit <= 0) return null;
        var indices = new LinkedHashMap<K, Integer>();
        var keys = new ArrayList<K>();
        var patterns = new ArrayList<CraftPattern<K>>();
        indices.put(target, 0); keys.add(target);
        // Primary-demand ancestry only: never discover a producer through its side output.
        for (int cursor = 0; cursor < keys.size(); cursor++) {
            PlanningCancellation.check();
            for (var pattern : graph.patternsFor(keys.get(cursor))) {
                if (patterns.size() == MAX_PATTERNS) return null;
                patterns.add(pattern);
                for (var input : pattern.inputs()) {
                    if (input.returned() || input.remainder() != null || input.reusableStockSource() != null)
                        return null;
                    if (!indices.containsKey(input.key())) {
                        if (keys.size() == MAX_ITEMS) return null;
                        indices.put(input.key(), keys.size()); keys.add(input.key());
                    }
                }
            }
        }
        // Side-output-only keys are accounted for in the growth proof, but need no inventory
        // slot: they have no input consumer in this primary-demand ancestry.
        int n = keys.size(), m = patterns.size();
        if (m == 0) return null;
        var inputs = new int[m][n]; var outputs = new int[m][n];
        for (int r = 0; r < m; r++) {
            var pattern = patterns.get(r);
            BigInteger consumed = BigInteger.ZERO, produced = pattern.exactOutputAmount();
            if (produced.signum() <= 0 || produced.compareTo(BigInteger.valueOf(MAX_STOCK)) > 0) return null;
            outputs[r][indices.get(pattern.output())] = produced.intValueExact();
            for (var input : pattern.inputs()) {
                if (input.exactAmount().signum() <= 0
                        || input.exactAmount().compareTo(BigInteger.valueOf(MAX_STOCK)) > 0) return null;
                consumed = consumed.add(input.exactAmount());
                inputs[r][indices.get(input.key())] += input.exactAmount().intValueExact();
                if (consumed.compareTo(BigInteger.valueOf(MAX_STOCK)) > 0) return null;
            }
            for (var output : pattern.byproducts()) {
                if (output.exactAmount().signum() < 0) return null;
                produced = produced.add(output.exactAmount());
                if (produced.compareTo(BigInteger.valueOf(MAX_STOCK)) > 0) return null;
                Integer index = indices.get(output.key());
                if (index != null) outputs[r][index] += output.exactAmount().intValueExact();
            }
            // Exact positive potential w=(1,...,1), including every side output.
            // Weighted conversion rings not satisfying this sufficient proof keep their old path.
            if (produced.compareTo(consumed) > 0) return null;
        }
        int[] stock = new int[n]; int total = 0;
        for (int i = 0; i < n; i++) {
            BigInteger available = graph.exactStock(keys.get(i));
            if (available.compareTo(BigInteger.valueOf(MAX_STOCK - total)) > 0) return null;
            stock[i] = available.intValueExact(); total += stock[i];
        }
        if (total < amount) return null;
        var queue = new ArrayDeque<Node>();
        var seen = new HashSet<Counts>();
        var first = new Counts(new int[m]);
        var admittedMasks = new HashMap<Integer, Boolean>();
        if (!tryConsumeWork.getAsBoolean()) return null;
        queue.add(new Node(first, stock, 0)); seen.add(first);
        while (!queue.isEmpty()) {
            PlanningCancellation.check();
            Node node = queue.removeFirst();
            if (node.stock[0] >= amount) {
                Map<CraftPattern<K>, Long> vector = new LinkedHashMap<>();
                int mask = 0;
                for (int r = 0; r < m; r++) if (node.counts.values[r] > 0) {
                    vector.put(patterns.get(r), (long) node.counts.values[r]);
                    mask |= 1 << r;
                }
                // A successful sequential witness alone is not safe to export to an unordered
                // CPU. This also checks primary-demand support for every selected final batch.
                CraftPlan<K> certified = MaterialDagReplay.trySmallPlan(graph, vector, target, amount);
                if (certified != null && admittedMasks.computeIfAbsent(mask, ignored -> {
                    var selected = new LinkedHashMap<K, List<CraftPattern<K>>>();
                    for (var pattern : vector.keySet())
                        selected.computeIfAbsent(pattern.output(), key -> new ArrayList<>()).add(pattern);
                    // External fuel may pay for growth of an internal cycle even when the
                    // global unit-weight total decreases. Prove each active SCC separately.
                    var analysis = ConservativeFeedbackAnalysis.analyzeAll(keys, selected);
                    return analysis.components().size() + analysis.fallbacks().size()
                            == analysis.cyclicComponents().size();
                }))
                    // CPU dispatch may take any stock-backed prefix of the remaining copies.
                    // Such a batch is a sequence of enabled unit firings, already covered by
                    // trySmallPlan's all-orders proof. Requiring every copy of one pattern to
                    // start together would wrongly reject seed reuse between partial batches.
                    return certified;
            }
            if (node.depth == MAX_FIRINGS) continue;
            for (int r = 0; r < m; r++) {
                boolean enabled = true;
                for (int i = 0; i < n; i++) if (node.stock[i] < inputs[r][i]) { enabled = false; break; }
                if (!enabled) continue;
                int[] counts = node.counts.values.clone(); counts[r]++;
                var key = new Counts(counts);
                // Equal count vectors have equal inventory and the same all-orders obligation.
                // Inventory alone is insufficient: two schedules can require different recipes.
                if (seen.contains(key)) continue;
                if (seen.size() >= stateLimit || !tryConsumeWork.getAsBoolean()) return null;
                seen.add(key);
                int[] next = node.stock.clone();
                for (int i = 0; i < n; i++) next[i] += outputs[r][i] - inputs[r][i];
                queue.addLast(new Node(key, next, node.depth + 1));
            }
        }
        return null;
    }

    private static final class Counts {
        final int[] values;
        private final int hash;
        Counts(int[] values) { this.values = values; hash = Arrays.hashCode(values); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof Counts counts && Arrays.equals(values, counts.values);
        }
    }

    private record Node(Counts counts, int[] stock, int depth) {}
}
