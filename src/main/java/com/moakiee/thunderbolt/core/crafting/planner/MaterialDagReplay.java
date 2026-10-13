package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

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
        var reusableDemand = new HashMap<ReusableStockRouteKey<K>, BigInteger>();
        var topology = new PlannerTopology.MaterialGraph<K>();
        var active = new IdentityHashMap<CraftPattern<K>, Long>();
        add(demand, target, BigInteger.valueOf(amount));
        int slotIndex = 0;
        for (var entry : counts.entrySet()) {
            PlanningCancellation.check();
            long firings = entry.getValue();
            if (firings <= 0) continue;
            var pattern = entry.getKey();
            var node = topology.node();
            active.put(pattern, firings);
            var times = BigInteger.valueOf(firings);
            var outputAmount = pattern.exactOutputAmount();
            add(produced, pattern.output(), outputAmount.multiply(times));
            // Each selected recipe must have enough primary demand to justify its final batch.
            // Surplus within that final batch is allowed; extra byproduct-only batches are not.
            add(minimumPrimaryDemand, pattern.output(),
                    outputAmount.multiply(times.subtract(BigInteger.ONE)).add(BigInteger.ONE));
            topology.edge(node, topology.item(pattern.output()));
            for (var input : pattern.inputs()) {
                if ((++slotIndex & 255) == 0) PlanningCancellation.check();
                if (input.reusableStockSource() != null) {
                    var route = new ReusableStockRouteKey<>(
                            input.reusableStockSource(), input.key());
                    reusableDemand.merge(route, requiredUnits(input, times), BigInteger::add);
                    topology.edge(topology.item(input.key()), node);
                    continue;
                }
                // Consumed container remainders are already normalized into byproducts. A normal
                // catalyst still needs one physical seed; only host-private catalysts bypass the
                // ordinary stock map above.
                add(demand, input.key(), requiredUnits(input, times));
                topology.edge(topology.item(input.key()), node);
            }
            for (var output : pattern.byproducts()) {
                if ((++slotIndex & 255) == 0) PlanningCancellation.check();
                add(produced, output.key(), output.exactAmount().multiply(times));
                topology.edge(node, topology.item(output.key()));
            }
        }
        for (var entry : minimumPrimaryDemand.entrySet()) {
            if (entry.getValue().compareTo(demand.getOrDefault(entry.getKey(), BigInteger.ZERO)) > 0)
                return null;
        }
        // Use a bipartite graph so a recipe with many inputs and outputs still costs O(V+E).
        // The original input arcs remain present: a self-returning seed is not a material DAG.
        boolean cyclic = !topology.isAcyclic();
        if (!proveEveryOrder && cyclic) return null;
        var reusableLongDemand = new HashMap<ReusableStockRouteKey<K>, Long>();
        for (var entry : reusableDemand.entrySet()) {
            if (entry.getValue().signum() < 0
                    || entry.getValue().compareTo(BigInteger.valueOf(Sat.SAT)) >= 0) return null;
            reusableLongDemand.put(entry.getKey(), entry.getValue().longValueExact());
        }
        Map<ReusableStockAllocationKey<K>, Long> reusableAssignments = Map.of();
        if (!reusableLongDemand.isEmpty()) {
            var reusableAllocation = ReusableStockMatcher.allocate(
                    graph.reusableStock(), reusableLongDemand,
                    route -> graph.reusableStockCandidates(route.source(), route.plannedKey()));
            if (!reusableAllocation.feasible()) return null;
            reusableAssignments = reusableAllocation.allocation();
        }
        var used = new HashMap<K, Long>();
        var usedReusable = new HashMap<ReusableStockUsageKey<K>, Long>();
        for (var entry : reusableAssignments.entrySet()) {
            var route = entry.getKey().route();
            var source = route.source();
            var usage = new ReusableStockUsageKey<>(
                    source.storageScope(), source.poolScope(), source.routingScope(),
                    route.plannedKey(), entry.getKey().actualKey());
            usedReusable.merge(usage, entry.getValue(), Sat::add);
        }
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
            if (cyclic) needed = entry.getValue().min(BigInteger.valueOf(graph.stock(key)));
            if (needed.signum() > 0) used.put(key, needed.longValueExact());
            gross.put(key, entry.getValue().min(BigInteger.valueOf(Sat.SAT)).longValueExact());
        }
        // In a material DAG, complete net balance is also an all-enabled-orders certificate:
        // the earliest unfinished producer has no unfinished supplier and therefore has its inputs.
        // These completed tables are privately owned; freezing need not rehash large inventories.
        var plan = new CraftPlan<>(true, missing.isEmpty(), Collections.unmodifiableMap(active),
                Collections.unmodifiableMap(used), Map.copyOf(usedReusable), Map.copyOf(missing),
                Collections.unmodifiableMap(gross), demand.size(), false);
        return !cyclic || UnorderedByproductSafety.allOrdersFinishSmall(plan, target, amount)
                ? plan : null;
    }

    private static <K> void add(Map<K, BigInteger> map, K key, BigInteger amount) {
        map.merge(key, amount, BigInteger::add);
    }

    private static <K> BigInteger requiredUnits(CraftInput<K> input, BigInteger firings) {
        if (!input.returned()) return input.exactAmount().multiply(firings);
        BigInteger uses = BigInteger.valueOf(input.uses());
        return input.exactAmount().multiply(firings.add(uses).subtract(BigInteger.ONE).divide(uses));
    }

}
