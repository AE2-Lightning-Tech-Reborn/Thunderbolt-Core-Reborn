package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
        if (!proveEveryOrder && counts.size() >= 128) {
            var indexed = IndexedReplay.<K>create(counts);
            if (indexed != null) return indexed.tryPlan(graph, counts, target, amount, allowLeafMissing);
        }
        var demand = new HashMap<K, BigInteger>();
        var produced = new HashMap<K, BigInteger>();
        var minimumPrimaryDemand = new HashMap<K, BigInteger>();
        var edges = new HashMap<Object, Set<Object>>();
        var indegree = new HashMap<Object, Integer>();
        var active = new IdentityHashMap<CraftPattern<K>, Long>();
        add(demand, target, BigInteger.valueOf(amount));
        int slotIndex = 0;
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
                if ((++slotIndex & 255) == 0) PlanningCancellation.check();
                if (input.returned() || input.remainder() != null || input.reusableStockSource() != null)
                    return null;
                add(demand, input.key(), input.exactAmount().multiply(times));
                edge(edges, indegree, new ItemNode<>(input.key()), node);
            }
            for (var output : pattern.byproducts()) {
                if ((++slotIndex & 255) == 0) PlanningCancellation.check();
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
        int topologyIndex = 0;
        for (var entry : indegree.entrySet()) {
            if ((++topologyIndex & 255) == 0) PlanningCancellation.check();
            if (entry.getValue() == 0) ready.addLast(entry.getKey());
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            PlanningCancellation.check();
            Object node = ready.removeFirst();
            visited++;
            for (Object next : edges.getOrDefault(node, Set.of())) {
                if ((++topologyIndex & 255) == 0) PlanningCancellation.check();
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
        // These tables are privately owned and finished; avoid MapN's clustered large-key lookup.
        var plan = new CraftPlan<>(true, missing.isEmpty(), java.util.Collections.unmodifiableMap(active),
                java.util.Collections.unmodifiableMap(used), Map.of(), Map.copyOf(missing),
                java.util.Collections.unmodifiableMap(gross), demand.size(), false);
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

    /** Large certificates use a bipartite integer multigraph. Duplicate arcs need not be hashed
     * away: each is counted and removed once, so Kahn's cycle test has the same result. */
    private static final class IndexedReplay<K> {
        private final Map<K, Balance<K>> items;
        private final ArrayList<Balance<K>> balances = new ArrayList<>();
        private final int[] heads, degree, targets, next;
        private int nodes, arcs;

        private IndexedReplay(int vertexBound, int edgeBound, int patterns) {
            // Most large certificates have one or two material keys per active pattern. Reserve
            // the common range up front; the edge arrays already provide the hard upper bound and
            // HashMap can still grow safely for unusually wide recipes.
            long estimatedMaterials = Math.min(Integer.MAX_VALUE - 8L, patterns * 2L + 1L);
            int materialCapacity = (int) Math.max(16L, estimatedMaterials);
            items = new HashMap<>(materialCapacity);
            balances.ensureCapacity(materialCapacity);
            heads = new int[vertexBound];
            Arrays.fill(heads, -1);
            degree = new int[vertexBound];
            targets = new int[edgeBound];
            next = new int[edgeBound];
        }

        static <K> IndexedReplay<K> create(Map<CraftPattern<K>, Long> counts) {
            long edges = 0, patterns = 0;
            for (var entry : counts.entrySet()) {
                PlanningCancellation.check();
                if (entry.getValue() <= 0) continue;
                patterns++;
                edges += 1L + entry.getKey().inputs().size() + entry.getKey().byproducts().size();
                if (edges + patterns + 1L > Integer.MAX_VALUE - 8L) return null;
            }
            return new IndexedReplay<>((int) (edges + patterns + 1L), (int) edges, (int) patterns);
        }

        private Balance<K> item(K key) {
            var balance = items.get(key);
            if (balance == null) {
                balance = new Balance<>(key, nodes++);
                items.put(key, balance);
                balances.add(balance);
            }
            return balance;
        }

        private void edge(int from, int to) {
            targets[arcs] = to;
            next[arcs] = heads[from];
            heads[from] = arcs++;
            degree[to]++;
        }

        CraftPlan<K> tryPlan(CraftGraph<K> graph, Map<CraftPattern<K>, Long> counts,
                K target, long amount, boolean allowLeafMissing) {
            var active = new IdentityHashMap<CraftPattern<K>, Long>(counts.size());
            item(target).demand = BigInteger.valueOf(amount);
            int slotIndex = 0;
            for (var entry : counts.entrySet()) {
                PlanningCancellation.check();
                long firings = entry.getValue();
                if (firings <= 0) continue;
                var pattern = entry.getKey();
                int node = nodes++;
                active.put(pattern, firings);
                var times = BigInteger.valueOf(firings);
                var primary = item(pattern.output());
                var outputAmount = pattern.exactOutputAmount();
                primary.produced = primary.produced.add(outputAmount.multiply(times));
                primary.hasProducer = true;
                primary.minimumPrimary = primary.minimumPrimary.add(
                        outputAmount.multiply(times.subtract(BigInteger.ONE)).add(BigInteger.ONE));
                edge(node, primary.id);
                for (var input : pattern.inputs()) {
                    if ((++slotIndex & 255) == 0) PlanningCancellation.check();
                    if (input.returned() || input.remainder() != null || input.reusableStockSource() != null)
                        return null;
                    var balance = item(input.key());
                    balance.demand = balance.demand.add(input.exactAmount().multiply(times));
                    balance.hasDemand = true;
                    edge(balance.id, node);
                }
                for (var output : pattern.byproducts()) {
                    if ((++slotIndex & 255) == 0) PlanningCancellation.check();
                    var balance = item(output.key());
                    balance.produced = balance.produced.add(output.exactAmount().multiply(times));
                    balance.hasProducer = true;
                    edge(node, balance.id);
                }
            }
            for (var balance : balances) {
                PlanningCancellation.check();
                if (balance.minimumPrimary.compareTo(balance.demand) > 0) return null;
            }
            var ready = new int[nodes];
            int read = 0, write = 0;
            for (int node = 0; node < nodes; node++) {
                if ((node & 255) == 0) PlanningCancellation.check();
                if (degree[node] == 0) ready[write++] = node;
            }
            int traversed = 0;
            while (read < write) {
                PlanningCancellation.check();
                int node = ready[read++];
                for (int arc = heads[node]; arc >= 0; arc = next[arc]) {
                    if ((++traversed & 255) == 0) PlanningCancellation.check();
                    if (--degree[targets[arc]] == 0) ready[write++] = targets[arc];
                }
            }
            if (read != nodes) return null;
            var used = new HashMap<K, Long>(items.size());
            var missing = new HashMap<K, Long>();
            var gross = new HashMap<K, Long>(items.size());
            BigInteger limit = BigInteger.valueOf(Sat.SAT);
            int processed = 0;
            for (var balance : balances) {
                PlanningCancellation.check();
                if (!balance.hasDemand && !balance.key.equals(target)) continue;
                processed++;
                K key = balance.key;
                BigInteger needed = balance.demand.subtract(balance.produced);
                BigInteger stock = BigInteger.valueOf(graph.stock(key));
                if (needed.compareTo(stock) > 0) {
                    BigInteger deficit = needed.subtract(stock);
                    if (!allowLeafMissing || key.equals(target) || !graph.patternsFor(key).isEmpty()
                            || balance.hasProducer || deficit.compareTo(limit) > 0) return null;
                    missing.put(key, deficit.longValueExact());
                    needed = stock;
                }
                if (needed.signum() > 0) used.put(key, needed.longValueExact());
                gross.put(key, balance.demand.min(limit).longValueExact());
            }
            return new CraftPlan<>(true, missing.isEmpty(), java.util.Collections.unmodifiableMap(active),
                    java.util.Collections.unmodifiableMap(used), Map.of(), Map.copyOf(missing),
                    java.util.Collections.unmodifiableMap(gross), processed, false);
        }
    }

    private static final class Balance<K> {
        final K key;
        final int id;
        BigInteger demand = BigInteger.ZERO, produced = BigInteger.ZERO, minimumPrimary = BigInteger.ZERO;
        boolean hasDemand, hasProducer;
        private Balance(K key, int id) { this.key = key; this.id = id; }
    }

    private record ItemNode<K>(K key) {}
    private record PatternNode(int index) {}
}
