package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Optional anytime improvement of an already certified plan; probes never replace its witness. */
final class FeasibleConsumptionOptimizer {
    static final int MAX_PROBES = 32;
    static final long MAX_NANOS = 2_800_000_000L;
    static final long EXPORT_RESERVE_NANOS = 50_000_000L;

    record Result<K>(CraftPlan<K> plan, int probes, int improvements) {}

    private FeasibleConsumptionOptimizer() {}

    static <K> Result<K> optimize(CraftGraph<K> graph, K target, long amount, CraftPlan<K> initial,
            int probeLimit, Function<CraftGraph<K>, CraftPlan<K>> oracle) {
        var state = new Search<>(graph, target, amount, initial, probeLimit, oracle);
        try {
            state.run();
        } catch (PlanningCancellation.OptionalWorkLimit exhausted) {
            // External cancellation and the enclosing router deadline must still propagate.
        }
        return new Result<>(state.best, state.probes, state.improvements);
    }

    private static final class Search<K> {
        private final CraftGraph<K> graph;
        private final K target;
        private final long amount;
        private final int limit;
        private final Function<CraftGraph<K>, CraftPlan<K>> oracle;
        private CraftPlan<K> best;
        private int probes;
        private int improvements;
        private boolean exhausted;

        Search(CraftGraph<K> graph, K target, long amount, CraftPlan<K> initial, int limit,
                Function<CraftGraph<K>, CraftPlan<K>> oracle) {
            this.graph = graph;
            this.target = target;
            this.amount = amount;
            this.best = initial;
            this.limit = Math.min(MAX_PROBES, limit);
            this.oracle = oracle;
        }

        void run() {
            if (limit <= 0 || !best.feasible() || amount <= 0 || amount >= Sat.SAT) return;
            // Saturated accounting belongs to the existing exact display continuation. It is not
            // an executable incumbent whose truncated coordinates can safely be optimized.
            if (best.usedStock().values().stream().anyMatch(Sat::isSaturated)
                    || best.firings().values().stream().anyMatch(Sat::isSaturated)
                    || best.grossDemand().values().stream().anyMatch(Sat::isSaturated)) return;
            var patterns = new LinkedHashMap<K, List<CraftPattern<K>>>();
            var pending = new ArrayDeque<K>();
            pending.add(target);
            boolean choices = false;
            while (!pending.isEmpty()) {
                PlanningCancellation.check();
                K key = pending.removeFirst();
                if (patterns.containsKey(key)) continue;
                var routes = graph.patternsFor(key);
                patterns.put(key, routes);
                choices |= routes.size() > 1;
                for (var pattern : routes) {
                    if (pattern.exactOutputAmount().compareTo(BigInteger.valueOf(Sat.SAT)) >= 0) return;
                    for (var output : pattern.byproducts())
                        if (output.exactAmount().compareTo(BigInteger.valueOf(Sat.SAT)) >= 0) return;
                    for (var input : pattern.inputs()) {
                        if (input.exactAmount().compareTo(BigInteger.valueOf(Sat.SAT)) >= 0) return;
                        pending.addLast(input.key());
                    }
                }
            }
            if (!choices) return; // Fixed ordinary recipe chains have no allocation to improve.

            List<K> order = dagOrder(patterns);
            Map<K, BigInteger> weights = order.isEmpty() ? Map.of() : RawResourcePotential.weights(order, patterns);
            if (!weights.isEmpty()) {
                // Per-unit cost misses the waste of firing a large batch for a tiny request.
                // Use incumbent demand only to propose a policy; replay recomputes every demand.
                probePolicy(materialPolicy(order, patterns, weights, best), order);
            }
            // A single cheap whole-DAG proposal also improves equal-material plans' firing counts.
            // It is only a heuristic: stock, batch rounding and shared inputs are checked by the oracle.
            if (!weights.isEmpty()) {
                probePolicy(executionPolicy(order, patterns), order);
            }

            var keys = new ArrayList<K>();
            for (K key : patterns.keySet()) if (best.usedStock().getOrDefault(key, 0L) > 0) keys.add(key);
            keys.sort((a, b) -> Long.compare(best.usedStock().getOrDefault(b, 0L),
                    best.usedStock().getOrDefault(a, 0L)));
            for (K key : keys) {
                PlanningCancellation.check();
                if (exhausted || probes >= limit) break;
                long upper = best.usedStock().getOrDefault(key, 0L);
                if (upper == 0 || upper >= Sat.SAT) continue;
                long lower = lowerBound(weights, target, amount, best.usedStock(), key);
                if (lower >= upper) continue;
                boolean improved = probeLimit(key, lower);
                if (exhausted) break;
                if (improved) continue;
                // A rejected heuristic probe is not an infeasibility proof. Bracketing only guides
                // optional quality search; it can never invalidate the incumbent.
                long rejected = lower;
                if (lower < upper - 1 && !probeLimit(key, upper - 1)) continue;
                upper = best.usedStock().getOrDefault(key, 0L);
                // A route switch may jump straight to the optimum. Test its actual extraction
                // before bisecting a trillion-wide gap left by a fractional resource lower bound.
                if (upper - rejected > 1 && !probeLimit(key, upper - 1)) continue;
                upper = best.usedStock().getOrDefault(key, 0L);
                while (!exhausted && probes < limit && upper - rejected > 1) {
                    long middle = rejected + (upper - rejected) / 2;
                    if (probeLimit(key, middle)) upper = best.usedStock().getOrDefault(key, 0L);
                    else rejected = middle;
                }
            }
        }

        private void probePolicy(Map<K, List<CraftPattern<K>>> selected, List<K> order) {
            boolean changesRoute = best.firings().keySet().stream().anyMatch(p ->
                    !selected.getOrDefault(p.output(), List.of()).contains(p));
            if (!changesRoute || exhausted || probes >= limit) return;
            var candidate = graph.withPatterns(selected).withStockLimits(best.usedStock());
            // The ordinary fixed DAG has a cheap exact shortage test. Do not launch a whole
            // alternative search for a policy already known to exceed the incumbent's stock.
            var policy = ConservativeReplenishment.compileFixed(candidate, order, selected);
            if (policy == null) return;
            var proposal = policy.plan(target, amount, Map.of());
            if (proposal != null && proposal.feasible()) probe(candidate);
        }

        private boolean probeLimit(K key, long amount) {
            var limits = new HashMap<>(best.usedStock());
            if (amount == 0) limits.remove(key);
            else limits.put(key, amount);
            return probe(graph.withStockLimits(limits));
        }

        private boolean probe(CraftGraph<K> candidateGraph) {
            PlanningCancellation.check();
            if (exhausted || probes >= limit) return false;
            probes++;
            CraftPlan<K> candidate = oracle.apply(candidateGraph);
            if (candidate == null) {
                exhausted = true;
                return false;
            }
            for (var entry : candidate.usedStock().entrySet())
                if (entry.getValue() > candidateGraph.stock(entry.getKey())) return false;
            if (!improves(best, candidate)) return false;
            best = candidate;
            improvements++;
            return true;
        }
    }

    /** No material substitution, greater seed occupancy or increased net resource loss is accepted. */
    static <K> boolean improves(CraftPlan<K> best, CraftPlan<K> candidate) {
        if (!candidate.supported() || !candidate.feasible() || !candidate.missing().isEmpty()
                || !noMore(candidate.usedStock(), best.usedStock())
                || !noMore(candidate.usedReusableStock(), best.usedReusableStock())
                || !statefulFirings(best).equals(statefulFirings(candidate))) return false;
        var oldLoss = netLoss(best);
        var newLoss = netLoss(candidate);
        for (var entry : newLoss.entrySet())
            if (entry.getValue().compareTo(oldLoss.getOrDefault(entry.getKey(), BigInteger.ZERO)) > 0) return false;
        boolean reduced = MissingRefinement.strictlyDominates(candidate.usedStock(), best.usedStock())
                || MissingRefinement.strictlyDominates(candidate.usedReusableStock(), best.usedReusableStock());
        for (var entry : oldLoss.entrySet())
            reduced |= newLoss.getOrDefault(entry.getKey(), BigInteger.ZERO).compareTo(entry.getValue()) < 0;
        return reduced || executions(candidate).compareTo(executions(best)) < 0;
    }

    private static <K> boolean noMore(Map<K, Long> candidate, Map<K, Long> best) {
        for (var entry : candidate.entrySet())
            if (entry.getValue() < 0 || entry.getValue() > best.getOrDefault(entry.getKey(), 0L)) return false;
        return true;
    }

    private static <K> Map<CraftPattern<K>, Long> statefulFirings(CraftPlan<K> plan) {
        var result = new HashMap<CraftPattern<K>, Long>();
        plan.firings().forEach((pattern, count) -> {
            if (pattern.inputs().stream().anyMatch(i -> i.returned() || i.remainder() != null
                    || i.reusableStockSource() != null)) result.put(pattern, count);
        });
        return result;
    }

    private static <K> Map<K, BigInteger> netLoss(CraftPlan<K> plan) {
        var loss = new HashMap<K, BigInteger>();
        plan.firings().forEach((pattern, count) -> {
            PlanningCancellation.check();
            var times = BigInteger.valueOf(count);
            for (var input : pattern.inputs()) if (!input.returned())
                loss.merge(input.key(), input.exactAmount().multiply(times), BigInteger::add);
            loss.merge(pattern.output(), pattern.exactOutputAmount().multiply(times).negate(), BigInteger::add);
            for (var side : pattern.byproducts())
                loss.merge(side.key(), side.exactAmount().multiply(times).negate(), BigInteger::add);
        });
        loss.entrySet().removeIf(e -> e.getValue().signum() <= 0);
        return loss;
    }

    private static BigInteger executions(CraftPlan<?> plan) {
        BigInteger total = BigInteger.ZERO;
        for (long count : plan.firings().values()) total = total.add(BigInteger.valueOf(count));
        return total;
    }

    private static <K> long lowerBound(Map<K, BigInteger> weights, K target, long amount,
            Map<K, Long> stock, K key) {
        BigInteger weight = weights.getOrDefault(key, BigInteger.ZERO);
        if (weight.signum() <= 0) return 0;
        BigInteger need = weights.getOrDefault(target, BigInteger.ZERO).multiply(BigInteger.valueOf(amount));
        for (var entry : stock.entrySet()) if (!entry.getKey().equals(key))
            need = need.subtract(weights.getOrDefault(entry.getKey(), BigInteger.ZERO)
                    .multiply(BigInteger.valueOf(entry.getValue())));
        if (need.signum() <= 0) return 0;
        return need.subtract(BigInteger.ONE).divide(weight).add(BigInteger.ONE)
                .min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    private static <K> List<K> dagOrder(Map<K, List<CraftPattern<K>>> patterns) {
        var edges = new LinkedHashMap<K, LinkedHashSet<K>>();
        var degree = new HashMap<K, Integer>();
        patterns.forEach((key, routes) -> {
            PlanningCancellation.check();
            var inputs = new LinkedHashSet<K>();
            for (var pattern : routes) for (var input : pattern.inputs()) inputs.add(input.key());
            edges.put(key, inputs);
            for (K input : inputs) degree.merge(input, 1, Integer::sum);
        });
        var pending = new ArrayDeque<K>();
        for (K key : patterns.keySet()) if (degree.getOrDefault(key, 0) == 0) pending.addLast(key);
        var result = new ArrayList<K>();
        while (!pending.isEmpty()) {
            PlanningCancellation.check();
            K key = pending.removeFirst();
            result.add(key);
            for (K input : edges.get(key)) if (degree.merge(input, -1, Integer::sum) == 0) pending.addLast(input);
        }
        return result.size() == patterns.size() ? result : List.of();
    }

    private static <K> Map<K, List<CraftPattern<K>>> executionPolicy(List<K> order,
            Map<K, List<CraftPattern<K>>> patterns) {
        var cost = new HashMap<K, Double>();
        var selected = new HashMap<K, List<CraftPattern<K>>>();
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanningCancellation.check();
            K key = order.get(i);
            CraftPattern<K> best = null;
            double minimum = Double.POSITIVE_INFINITY;
            for (var pattern : patterns.get(key)) {
                double value = 1;
                for (var input : pattern.inputs()) value += cost.get(input.key()) * input.amount();
                value /= pattern.outputAmount();
                if (best == null || value < minimum) {
                    best = pattern;
                    minimum = value;
                }
            }
            if (best != null) selected.put(key, List.of(best));
            cost.put(key, best == null ? 0 : minimum);
        }
        return selected;
    }

    private static <K> Map<K, List<CraftPattern<K>>> materialPolicy(List<K> order,
            Map<K, List<CraftPattern<K>>> patterns, Map<K, BigInteger> weights, CraftPlan<K> incumbent) {
        var selected = new HashMap<K, List<CraftPattern<K>>>();
        for (K key : order) {
            PlanningCancellation.check();
            long demand = Math.max(1L, incumbent.grossDemand().getOrDefault(key, 1L)
                    - incumbent.usedStock().getOrDefault(key, 0L));
            CraftPattern<K> best = null;
            BigInteger minimum = null;
            for (var pattern : patterns.get(key)) {
                BigInteger cost = BigInteger.ZERO;
                for (var input : pattern.inputs())
                    cost = cost.add(weights.get(input.key()).multiply(input.exactAmount()));
                cost = cost.multiply(BigInteger.valueOf(Sat.ceilDiv(demand, pattern.outputAmount())));
                if (minimum == null || cost.compareTo(minimum) < 0) {
                    best = pattern;
                    minimum = cost;
                }
            }
            if (best != null) selected.put(key, List.of(best));
        }
        return selected;
    }
}
