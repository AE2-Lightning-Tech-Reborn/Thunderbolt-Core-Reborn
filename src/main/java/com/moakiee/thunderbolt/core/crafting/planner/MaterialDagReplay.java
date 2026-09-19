package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Exact count-vector certificate for ordinary material DAGs, including every side output. */
final class MaterialDagReplay {
    private MaterialDagReplay() {}

    static <K> CraftPlan<K> tryPlan(CraftGraph<K> graph, Map<CraftPattern<K>, Long> counts,
            K target, long amount) {
        return tryPlan(graph, counts, target, amount, false, false);
    }

    /** A complete DAG can diagnose shortages only at original leaves with no selected producer. */
    static <K> CraftPlan<K> tryLeafMissingPlan(CraftGraph<K> graph, Map<CraftPattern<K>, Long> counts,
            K target, long amount) {
        return tryPlan(graph, counts, target, amount, false, true);
    }

    /** Small cyclic vectors require proof of every enabled order, not one successful schedule. */
    static <K> CraftPlan<K> trySmallPlan(CraftGraph<K> graph, Map<CraftPattern<K>, Long> counts,
            K target, long amount) {
        if (counts.size() > 16) return null;
        long total = 0;
        for (long n : counts.values()) {
            if (n < 0 || n > 64-total) return null;
            total += n;
        }
        return tryPlan(graph, counts, target, amount, true, false);
    }

    private static <K> CraftPlan<K> tryPlan(CraftGraph<K> graph, Map<CraftPattern<K>, Long> counts,
            K target, long amount, boolean proveEveryOrder, boolean allowLeafMissing) {
        var demand = new HashMap<K, BigInteger>();
        var produced = new HashMap<K, BigInteger>();
        var minimumPrimaryDemand = new HashMap<K, BigInteger>();
        var edges = new HashMap<Object, Set<Object>>();
        var indegree = new HashMap<Object, Integer>();
        var active = new IdentityHashMap<CraftPattern<K>, Long>();
        add(demand, target, BigInteger.valueOf(amount));
        for (var entry : counts.entrySet()) {
            PlanningCancellation.check();
            long firings = entry.getValue();
            if (firings <= 0) continue;
            var pattern = entry.getKey();
            var node = new PatternNode(active.size());
            active.put(pattern, firings);
            var times = BigInteger.valueOf(firings);
            var outputAmount = pattern.exactOutputAmount();
            add(produced, pattern.output(), outputAmount.multiply(times));
            // Each selected recipe must have enough primary demand to justify its final batch.
            // Surplus within that final batch is allowed; extra byproduct-only batches are not.
            add(minimumPrimaryDemand, pattern.output(),
                    outputAmount.multiply(times.subtract(BigInteger.ONE)).add(BigInteger.ONE));
            edge(edges, indegree, node, new ItemNode<>(pattern.output()));
            for (var input : pattern.inputs()) {
                if (input.returned() || input.remainder() != null || input.reusableStockSource() != null)
                    return null;
                add(demand, input.key(), input.exactAmount().multiply(times));
                edge(edges, indegree, new ItemNode<>(input.key()), node);
            }
            for (var output : pattern.byproducts()) {
                add(produced, output.key(), output.exactAmount().multiply(times));
                edge(edges, indegree, node, new ItemNode<>(output.key()));
            }
        }
        for (var entry : minimumPrimaryDemand.entrySet()) {
            if (entry.getValue().compareTo(demand.getOrDefault(entry.getKey(), BigInteger.ZERO)) > 0)
                return null;
        }
        // Use a bipartite graph so a recipe with many inputs and outputs still costs O(V+E).
        // The original input arcs remain present: a self-returning seed is not a material DAG.
        var ready = new ArrayDeque<Object>();
        indegree.forEach((node, degree) -> { if (degree == 0) ready.addLast(node); });
        int visited = 0;
        while (!ready.isEmpty()) {
            PlanningCancellation.check();
            Object node = ready.removeFirst();
            visited++;
            for (Object next : edges.getOrDefault(node, Set.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) ready.addLast(next);
            }
        }
        if (!proveEveryOrder && visited != indegree.size()) return null;
        var used = new HashMap<K, Long>();
        var missing = new HashMap<K, Long>();
        var gross = new HashMap<K, Long>();
        for (var entry : demand.entrySet()) {
            PlanningCancellation.check();
            K key = entry.getKey();
            BigInteger needed = entry.getValue().subtract(produced.getOrDefault(key, BigInteger.ZERO));
            BigInteger stock = BigInteger.valueOf(graph.stock(key));
            if (needed.compareTo(stock) > 0) {
                BigInteger deficit = needed.subtract(stock);
                if (!allowLeafMissing || key.equals(target) || !graph.patternsFor(key).isEmpty()
                        || produced.containsKey(key) || deficit.compareTo(BigInteger.valueOf(Sat.SAT)) > 0)
                    return null;
                missing.put(key, deficit.longValueExact());
                needed = stock;
            }
            // A cyclic vector may need startup stock even when its net balance is zero. Draw only
            // available stock, bounded by the selected recipes' gross consumption. No missing or
            // invented stock is permitted, and all-order execution below must certify this draw.
            if (proveEveryOrder) needed = entry.getValue().min(BigInteger.valueOf(graph.stock(key)));
            if (needed.signum() > 0) used.put(key, needed.longValueExact());
            gross.put(key, entry.getValue().min(BigInteger.valueOf(Sat.SAT)).longValueExact());
        }
        // In a material DAG, complete net balance is also an all-enabled-orders certificate:
        // the earliest unfinished producer has no unfinished supplier and therefore has its inputs.
        var plan = new CraftPlan<>(true, missing.isEmpty(), Map.copyOf(active), Map.copyOf(used), Map.of(), Map.copyOf(missing),
                Map.copyOf(gross), demand.size(), false);
        return !proveEveryOrder || UnorderedByproductSafety.allOrdersFinishSmall(plan, target, amount)
                ? plan : null;
    }

    private static <K> void add(Map<K, BigInteger> map, K key, BigInteger amount) {
        map.merge(key, amount, BigInteger::add);
    }

    private static void edge(Map<Object, Set<Object>> edges, Map<Object, Integer> indegree,
            Object from, Object to) {
        indegree.putIfAbsent(from, 0);
        indegree.putIfAbsent(to, 0);
        if (edges.computeIfAbsent(from, ignored -> new HashSet<>()).add(to))
            indegree.merge(to, 1, Integer::sum);
    }

    private record ItemNode<K>(K key) {}
    private record PatternNode(int index) {}
}
