package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.AbstractMap;
import java.util.Objects;

/** Exact count-vector certificate for ordinary material DAGs, including every side output. */
final class MaterialDagReplay {
    private static final BigInteger SAT_LIMIT = BigInteger.valueOf(Sat.SAT);

    private MaterialDagReplay() {}

    /** Proof ownership follows the exact immutable result, graph snapshot, and request. */
    static boolean hasCertificate(CraftGraph<?> graph, CraftPlan<?> plan, Object target, long amount) {
        Object firings = plan.firings();
        return firings instanceof CertifiedFirings<?> proof && proof.plan == plan
                && proof.graph == graph && Objects.equals(proof.target, target) && proof.amount == amount;
    }

    private static final class CertifiedFirings<K> extends AbstractMap<CraftPattern<K>, Long> {
        private final Map<CraftPattern<K>, Long> entries;
        private final CraftGraph<K> graph;
        private final K target;
        private final long amount;
        private CraftPlan<K> plan;

        private CertifiedFirings(Map<CraftPattern<K>, Long> entries,
                CraftGraph<K> graph, K target, long amount) {
            this.entries = Map.copyOf(entries);
            this.graph = graph;
            this.target = target;
            this.amount = amount;
        }

        @Override public Set<Entry<CraftPattern<K>, Long>> entrySet() { return entries.entrySet(); }
        @Override public Long get(Object key) { return entries.get(key); }
        @Override public boolean containsKey(Object key) { return entries.containsKey(key); }
        @Override public Long getOrDefault(Object key, Long fallback) { return entries.getOrDefault(key, fallback); }
        @Override public int size() { return entries.size(); }
    }

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
        var topology = new MaterialTopology<K>();
        var active = new IdentityHashMap<CraftPattern<K>, Long>();
        add(demand, target, BigInteger.valueOf(amount));
        int slotIndex = 0;
        for (var entry : counts.entrySet()) {
            PlanningCancellation.check();
            long firings = entry.getValue();
            if (firings <= 0) continue;
            var pattern = entry.getKey();
            int node = topology.newNode();
            active.put(pattern, firings);
            var times = BigInteger.valueOf(firings);
            var outputAmount = pattern.exactOutputAmount();
            var primaryProduced = scaledAmount(outputAmount, times);
            add(produced, pattern.output(), primaryProduced);
            // Each selected recipe must have enough primary demand to justify its final batch.
            // Surplus within that final batch is allowed; extra byproduct-only batches are not.
            add(minimumPrimaryDemand, pattern.output(),
                    firings == 1 ? BigInteger.ONE : outputAmount.equals(BigInteger.ONE) ? times
                            : primaryProduced.subtract(outputAmount).add(BigInteger.ONE));
            topology.edge(node, topology.item(pattern.output()));
            for (var input : pattern.inputs()) {
                if ((++slotIndex & 255) == 0) PlanningCancellation.check();
                if (input.returned() || input.remainder() != null || input.reusableStockSource() != null)
                    return null;
                add(demand, input.key(), scaledAmount(input.exactAmount(), times));
                topology.edge(topology.item(input.key()), node);
            }
            for (var output : pattern.byproducts()) {
                if ((++slotIndex & 255) == 0) PlanningCancellation.check();
                add(produced, output.key(), scaledAmount(output.exactAmount(), times));
                topology.edge(node, topology.item(output.key()));
            }
        }
        for (var entry : minimumPrimaryDemand.entrySet()) {
            if (entry.getValue().compareTo(demand.getOrDefault(entry.getKey(), BigInteger.ZERO)) > 0)
                return null;
        }
        // Use a bipartite graph so a recipe with many inputs and outputs still costs O(V+E).
        // The original input arcs remain present: a self-returning seed is not a material DAG.
        if (!topology.isDag() && !proveEveryOrder) return null;
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
                        || produced.containsKey(key) || deficit.compareTo(SAT_LIMIT) > 0)
                    return null;
                missing.put(key, deficit.longValueExact());
                needed = stock;
            }
            // A cyclic vector may need startup stock even when its net balance is zero. Draw only
            // available stock, bounded by the selected recipes' gross consumption. No missing or
            // invented stock is permitted, and all-order execution below must certify this draw.
            if (proveEveryOrder) needed = entry.getValue().min(stock);
            if (needed.signum() > 0) used.put(key, needed.longValueExact());
            gross.put(key, entry.getValue().min(SAT_LIMIT).longValueExact());
        }
        // In a material DAG, complete net balance is also an all-enabled-orders certificate:
        // the earliest unfinished producer has no unfinished supplier and therefore has its inputs.
        var proof = new CertifiedFirings<>(active, graph, target, amount);
        var plan = new CraftPlan<>(true, missing.isEmpty(), proof, Map.copyOf(used), Map.of(), Map.copyOf(missing),
                Map.copyOf(gross), demand.size(), false);
        if (proveEveryOrder && !UnorderedByproductSafety.allOrdersFinishSmall(plan, target, amount)) return null;
        proof.plan = plan;
        return plan;
    }

    private static <K> void add(Map<K, BigInteger> map, K key, BigInteger amount) {
        map.merge(key, amount, BigInteger::add);
    }

    /** Material amounts and positive firing counts stay exact even beyond the long domain. */
    private static BigInteger scaledAmount(BigInteger amount, BigInteger times) {
        if (times.equals(BigInteger.ONE)) return amount;
        if (amount.equals(BigInteger.ONE)) return times;
        return amount.multiply(times);
    }

    /** Compact bipartite adjacency; parallel arcs are counted and removed individually. */
    private static final class MaterialTopology<K> {
        private final Map<K, Integer> items = new HashMap<>();
        private int[] heads = new int[16];
        private int[] indegrees = new int[16];
        private int[] destinations = new int[32];
        private int[] next = new int[32];
        private int nodes, edges;

        int newNode() {
            if (nodes == heads.length) {
                heads = Arrays.copyOf(heads, heads.length * 2);
                indegrees = Arrays.copyOf(indegrees, indegrees.length * 2);
            }
            heads[nodes] = -1;
            return nodes++;
        }

        int item(K key) {
            Integer node = items.get(key);
            if (node != null) return node;
            int created = newNode();
            items.put(key, created);
            return created;
        }

        void edge(int from, int to) {
            if (edges == destinations.length) {
                destinations = Arrays.copyOf(destinations, destinations.length * 2);
                next = Arrays.copyOf(next, next.length * 2);
            }
            destinations[edges] = to;
            next[edges] = heads[from];
            heads[from] = edges++;
            indegrees[to]++;
        }

        boolean isDag() {
            int[] ready = new int[nodes];
            int read = 0, written = 0, work = 0;
            for (int node = 0; node < nodes; node++) {
                if ((++work & 255) == 0) PlanningCancellation.check();
                if (indegrees[node] == 0) ready[written++] = node;
            }
            while (read < written) {
                PlanningCancellation.check();
                int node = ready[read++];
                for (int arc = heads[node]; arc >= 0; arc = next[arc]) {
                    if ((++work & 255) == 0) PlanningCancellation.check();
                    int to = destinations[arc];
                    if (--indegrees[to] == 0) ready[written++] = to;
                }
            }
            return read == nodes;
        }
    }
}
