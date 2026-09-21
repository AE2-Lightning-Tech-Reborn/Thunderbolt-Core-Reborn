package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Sparse integral flow for single-input DAG recipes with a consistent batch size for each material. */
final class UnitMaterialFlow {
    record Result<K>(BoundedIntegerLinearSolver.Status status, Map<CraftPattern<K>, Long> firings,
            Map<CraftPattern<K>, Long> leafSupplyFirings) {}

    private UnitMaterialFlow() {}

    /** Null means this component needs the general solver. A cutoff is never infeasibility. */
    static <K> Result<K> trySolve(CraftGraph<K> graph, List<K> items, List<CraftPattern<K>> patterns,
            Map<K, Long> demand, Map<K, Long> supply, BoundedIntegerLinearSolver.WorkBudget budget) {
        return trySolve(graph, items, patterns, demand, supply, budget, false);
    }

    /** Supplemented counts are a separate diagnosis proposal, never an actual-stock solution. */
    static <K> Result<K> trySolveWithLeafSupply(CraftGraph<K> graph, List<K> items,
            List<CraftPattern<K>> patterns, Map<K, Long> demand, Map<K, Long> supply,
            BoundedIntegerLinearSolver.WorkBudget budget) {
        return trySolve(graph, items, patterns, demand, supply, budget, true);
    }

    private static <K> Result<K> trySolve(CraftGraph<K> graph, List<K> items, List<CraftPattern<K>> patterns,
            Map<K, Long> demand, Map<K, Long> supply, BoundedIntegerLinearSolver.WorkBudget budget,
            boolean allowLeafSupply) {
        if (patterns.isEmpty()) return null;
        var quantum = new HashMap<K, BigInteger>();
        for (var pattern : patterns) {
            PlanningCancellation.check();
            if (!pattern.exactOutputAmount().equals(BigInteger.valueOf(pattern.outputAmount()))
                    || !pattern.byproducts().isEmpty()
                    || pattern.inputs().size() != 1) return null;
            var input = pattern.inputs().get(0);
            if (!input.exactAmount().equals(BigInteger.valueOf(input.amount()))
                    || input.returned() || input.remainder() != null
                    || input.reusableStockSource() != null || input.key().equals(pattern.output())) return null;
            if (!sameQuantum(quantum, input.key(), input.exactAmount())
                    || !sameQuantum(quantum, pattern.output(), pattern.exactOutputAmount())) return null;
        }
        var index = new HashMap<K, Integer>();
        for (K item : items) index.put(item, index.size());
        int source = index.size(), sink = source+1;
        var flow = new Network(sink+1, budget);
        var recipeEdges = new IdentityHashMap<CraftPattern<K>, Edge>();
        var deliveries = new ArrayList<Edge>();
        try {
            flow.charge(items.size() + (long) patterns.size());
            for (K key : items) {
                int node = index.get(key);
                long available = Sat.add(graph.stock(key), supply.getOrDefault(key, 0L));
                BigInteger deficit = BigInteger.valueOf(demand.getOrDefault(key, 0L))
                        .subtract(BigInteger.valueOf(available));
                // Every incidence on this row is exactly +/- one material quantum. Dividing the
                // balance and rounding its right side upward is equivalent for integral counts;
                // subtract stock before rounding so a partial existing batch is not lost.
                BigInteger[] divided = deficit.divideAndRemainder(quantum.getOrDefault(key, BigInteger.ONE));
                BigInteger needed = divided[0].add(divided[1].signum() > 0 ? BigInteger.ONE : BigInteger.ZERO);
                if (needed.abs().compareTo(BigInteger.valueOf(Sat.SAT)) > 0) return null;
                if (needed.signum() < 0) flow.add(source, node, needed.negate().longValueExact(), false);
                if (needed.signum() > 0) deliveries.add(flow.add(node, sink, needed.longValueExact(), false));
            }
            for (var pattern : patterns) {
                Integer from = index.get(pattern.inputs().get(0).key()), to = index.get(pattern.output());
                if (from == null || to == null) return null;
                recipeEdges.put(pattern, flow.add(from, to, Sat.SAT, true));
            }
            // Keep the same ordinary DAG contract as component replay. A circulation with no
            // startup stock must not acquire authority merely because its net balance is zero.
            if (!flow.acyclic(source)) return null;
            flow.maximize(source, sink);
            boolean missing = deliveries.stream().anyMatch(edge -> edge.remaining != 0);
            if (missing) {
                if (!allowLeafSupply)
                    return new Result<>(BoundedIntegerLinearSolver.Status.INFEASIBLE, null, null);
                // Maximize real stock first. Each scaled recipe transfers one token, so filling
                // the remaining deliveries uses the minimum total added leaf tokens. Preserve
                // the residual network: a later path may reassign an earlier real-stock choice.
                // Exact physical shortages (including partial batches) are derived by the caller's
                // whole-plan certificate, not by multiplying these virtual capacities.
                var produced = new java.util.HashSet<K>();
                for (var pattern : patterns) produced.add(pattern.output());
                for (K key : items) {
                    flow.charge(1);
                    if (graph.patternsFor(key).isEmpty() && !produced.contains(key))
                        flow.add(source, index.get(key), Sat.SAT, false);
                }
                flow.maximize(source, sink);
                if (deliveries.stream().anyMatch(edge -> edge.remaining != 0))
                    return new Result<>(BoundedIntegerLinearSolver.Status.INFEASIBLE, null, null);
            }
            var counts = new IdentityHashMap<CraftPattern<K>, Long>();
            recipeEdges.forEach((pattern, edge) -> {
                long count = edge.initial-edge.remaining;
                if (count > 0) counts.put(pattern, count);
            });
            return missing
                    ? new Result<>(BoundedIntegerLinearSolver.Status.INFEASIBLE, null, Map.copyOf(counts))
                    : new Result<>(BoundedIntegerLinearSolver.Status.SOLVED, Map.copyOf(counts), null);
        } catch (WorkLimit exhausted) {
            return new Result<>(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, null, null);
        }
    }

    private static <K> boolean sameQuantum(Map<K, BigInteger> quantum, K key, BigInteger amount) {
        if (amount.signum() <= 0) return false;
        BigInteger prior = quantum.putIfAbsent(key, amount);
        return prior == null || prior.equals(amount);
    }

    private static final class Edge {
        final int to, reverse;
        final long initial;
        final boolean recipe;
        long remaining;

        Edge(int to, int reverse, long capacity, boolean recipe) {
            this.to = to;
            this.reverse = reverse;
            this.initial = capacity;
            this.remaining = capacity;
            this.recipe = recipe;
        }
    }

    private static final class Network {
        private final List<List<Edge>> edges;
        private final int[] levels, cursor, queue, nodes, pathEdges;
        private final long[] capacity;
        private final BoundedIntegerLinearSolver.WorkBudget budget;

        Network(int size, BoundedIntegerLinearSolver.WorkBudget budget) {
            this.budget = budget;
            edges = new ArrayList<>(size);
            for (int i = 0; i < size; i++) edges.add(new ArrayList<>());
            levels = new int[size];
            cursor = new int[size];
            queue = new int[size];
            nodes = new int[size];
            pathEdges = new int[size];
            capacity = new long[size];
        }

        void charge(long work) {
            PlanningCancellation.check();
            if (!budget.tryConsume(work)) throw new WorkLimit();
        }

        Edge add(int from, int to, long amount, boolean recipe) {
            var forward = new Edge(to, edges.get(to).size(), amount, recipe);
            var reverse = new Edge(from, edges.get(from).size(), 0, false);
            edges.get(from).add(forward);
            edges.get(to).add(reverse);
            return forward;
        }

        boolean acyclic(int materialNodes) {
            var indegree = new int[materialNodes];
            for (int from = 0; from < materialNodes; from++) {
                charge(edges.get(from).size());
                for (var edge : edges.get(from)) if (edge.recipe) indegree[edge.to]++;
            }
            int head = 0, tail = 0;
            for (int node = 0; node < materialNodes; node++) if (indegree[node] == 0) queue[tail++] = node;
            while (head < tail) {
                int node = queue[head++];
                charge(edges.get(node).size());
                for (var edge : edges.get(node))
                    if (edge.recipe && --indegree[edge.to] == 0) queue[tail++] = edge.to;
            }
            return tail == materialNodes;
        }

        void maximize(int source, int sink) {
            while (levels(source, sink)) {
                Arrays.fill(cursor, 0);
                while (augment(source, sink)) { /* One residual bottleneck, never one item. */ }
            }
        }

        private boolean levels(int source, int sink) {
            Arrays.fill(levels, -1);
            int head = 0, tail = 0;
            queue[tail++] = source;
            levels[source] = 0;
            while (head < tail) {
                int node = queue[head++];
                charge(edges.get(node).size());
                for (var edge : edges.get(node)) {
                    if (edge.remaining > 0 && levels[edge.to] < 0) {
                        levels[edge.to] = levels[node]+1;
                        queue[tail++] = edge.to;
                    }
                }
            }
            return levels[sink] >= 0;
        }

        /** Iterative blocking-flow DFS: a deep production chain cannot overflow the Java stack. */
        private boolean augment(int source, int sink) {
            int depth = 0;
            nodes[0] = source;
            capacity[0] = Sat.SAT;
            while (depth >= 0) {
                charge(1);
                int node = nodes[depth];
                if (node == sink) {
                    long amount = capacity[depth];
                    charge(depth);
                    for (int i = 1; i <= depth; i++) {
                        var edge = edges.get(nodes[i-1]).get(pathEdges[i]);
                        edge.remaining -= amount;
                        edges.get(edge.to).get(edge.reverse).remaining += amount;
                    }
                    return true;
                }
                var adjacent = edges.get(node);
                while (cursor[node] < adjacent.size()) {
                    var edge = adjacent.get(cursor[node]);
                    if (edge.remaining > 0 && levels[edge.to] == levels[node]+1) break;
                    charge(1);
                    cursor[node]++;
                }
                if (cursor[node] == adjacent.size()) {
                    levels[node] = -1;
                    if (--depth >= 0) cursor[nodes[depth]]++;
                } else {
                    var edge = adjacent.get(cursor[node]);
                    pathEdges[depth+1] = cursor[node];
                    capacity[depth+1] = Math.min(capacity[depth], edge.remaining);
                    nodes[++depth] = edge.to;
                }
            }
            return false;
        }
    }

    private static final class WorkLimit extends RuntimeException {}
}
