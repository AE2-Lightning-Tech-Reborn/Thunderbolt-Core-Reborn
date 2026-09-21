package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stock-independent recipe policy for an ordinary material DAG. It supplies a reproducible
 * replenishment witness when optional route optimization cannot finish. Side outputs are either absent
 * or ignored surplus: substituting additional stock for a producer must never remove promised supply.
 * For this policy, increasing any inventory can only decrease upstream demand.
 *
 * <p>Stateful recipes retain the planner's existing ordered bootstrap path. This class never treats
 * a catalyst, host-private pool or cut feedback arc as an ordinary consumable.
 */
final class ConservativeReplenishment<K> {
    private static final BigInteger EXECUTABLE_LIMIT = BigInteger.valueOf(Sat.SAT);
    private final CraftGraph<K> graph;
    private final List<K> order;
    private final Map<K, CraftPattern<K>> routes;
    private final Map<K, BigInteger> resourceWeights;
    private final BigInteger stockCost;
    private final boolean ignoresByproducts;

    private ConservativeReplenishment(
            CraftGraph<K> graph, List<K> order, Map<K, CraftPattern<K>> routes,
            Map<K, BigInteger> resourceWeights, boolean ignoresByproducts) {
        this.graph = graph;
        this.order = order;
        this.routes = Map.copyOf(routes);
        this.resourceWeights = Map.copyOf(resourceWeights);
        this.ignoresByproducts = ignoresByproducts;
        BigInteger stockCost = BigInteger.ZERO;
        for (var entry : resourceWeights.entrySet()) {
            stockCost = stockCost.add(BigInteger.valueOf(graph.stock(entry.getKey()))
                    .multiply(entry.getValue()));
        }
        this.stockCost = stockCost;
    }

    static <K> ConservativeReplenishment<K> compile(
            CraftGraph<K> graph, List<K> order, Map<K, List<CraftPattern<K>>> patterns) {
        return compile(graph, order, patterns, true, false);
    }

    static <K> ConservativeReplenishment<K> compileFixed(
            CraftGraph<K> graph, List<K> order, Map<K, List<CraftPattern<K>>> patterns) {
        return compile(graph, order, patterns, false, false);
    }

    /** Monotone fallback: real side outputs are harmless surplus, never promised as supply. */
    static <K> ConservativeReplenishment<K> compileIgnoringByproducts(
            CraftGraph<K> graph, List<K> order, Map<K, List<CraftPattern<K>>> patterns) {
        return compile(graph, order, patterns, false, true);
    }

    private static <K> ConservativeReplenishment<K> compile(
            CraftGraph<K> graph, List<K> order, Map<K, List<CraftPattern<K>>> patterns,
            boolean resourceBound, boolean ignoreByproducts) {
        var position = new HashMap<K, Integer>();
        for (int i = 0; i < order.size(); i++) position.put(order.get(i), i);
        var costs = new HashMap<K, Long>();
        var routes = new HashMap<K, CraftPattern<K>>();
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanningCancellation.check();
            K output = order.get(i);
            var alternatives = patterns.getOrDefault(output, List.of());
            if (alternatives.isEmpty()) {
                costs.put(output, 1L);
                continue;
            }
            CraftPattern<K> best = null;
            long bestCost = Long.MAX_VALUE;
            for (var pattern : alternatives) {
                if ((!ignoreByproducts && !pattern.byproducts().isEmpty())
                        || pattern.exactOutputAmount().compareTo(EXECUTABLE_LIMIT) >= 0) return null;
                long cost = 0L;
                for (var input : pattern.inputs()) {
                    if (input.returned() || input.remainder() != null || input.reusableStockSource() != null
                            || input.exactAmount().compareTo(EXECUTABLE_LIMIT) >= 0
                            || !input.exactAmount().equals(BigInteger.valueOf(input.amount()))
                            || position.getOrDefault(input.key(), -1) <= i) {
                        return null;
                    }
                    cost = Sat.add(cost, Sat.mul(costs.get(input.key()), input.amount()));
                }
                // Stable integer cost is only a heuristic; the policy's execution is checked exactly.
                cost = Sat.ceilDiv(cost, pattern.outputAmount());
                if (best == null || cost < bestCost) {
                    best = pattern;
                    bestCost = cost;
                }
            }
            routes.put(output, best);
            costs.put(output, bestCost);
        }
        return new ConservativeReplenishment<>(graph, List.copyOf(order), routes,
                resourceBound ? RawResourcePotential.weights(order, patterns) : Map.of(), ignoreByproducts);
    }

    boolean ignoresByproducts() { return ignoresByproducts; }

    /**
     * The scaled weights satisfy c(output) * batch <= sum c(inputs) for every recipe. Stock + supply
     * must cover the target's weight. Matching this lower bound excludes any componentwise smaller
     * supply when every reported key has positive weight. Callers must exclude cut orientations.
     */
    boolean provesMinimumMissing(K target, long amount, Map<K, Long> missing) {
        if (resourceWeights.isEmpty()) return false;
        BigInteger supplyCost = BigInteger.ZERO;
        for (var entry : missing.entrySet()) {
            BigInteger cost = resourceWeights.getOrDefault(entry.getKey(), BigInteger.ZERO);
            if (cost.signum() <= 0) return false;
            supplyCost = supplyCost.add(cost.multiply(BigInteger.valueOf(entry.getValue())));
        }
        BigInteger required = resourceWeights.getOrDefault(target, BigInteger.ZERO)
                .multiply(BigInteger.valueOf(amount)).subtract(stockCost).max(BigInteger.ZERO);
        return supplyCost.equals(required);
    }

    /** Necessary supply for one coordinate when every other supplied quantity stays fixed. */
    long minimumSupply(K target, long amount, Map<K, Long> supplied, K coordinate) {
        BigInteger weight = resourceWeights.getOrDefault(coordinate, BigInteger.ZERO);
        if (resourceWeights.isEmpty() || weight.signum() <= 0) return -1L;
        BigInteger needed = resourceWeights.getOrDefault(target, BigInteger.ZERO)
                .multiply(BigInteger.valueOf(amount)).subtract(stockCost);
        for (var entry : supplied.entrySet()) if (!entry.getKey().equals(coordinate)) {
            needed = needed.subtract(resourceWeights.getOrDefault(entry.getKey(), BigInteger.ZERO)
                    .multiply(BigInteger.valueOf(entry.getValue())));
        }
        if (needed.signum() <= 0) return 0L;
        return needed.subtract(BigInteger.ONE).divide(weight).add(BigInteger.ONE)
                .min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    CraftPlan<K> plan(K target, long amount, Map<K, Long> additionalStock) {
        if (amount >= Sat.SAT) return null;
        var need = new HashMap<K, BigInteger>();
        var fired = new IdentityHashMap<CraftPattern<K>, Long>();
        var used = new HashMap<K, Long>();
        var missing = new HashMap<K, Long>();
        var gross = new HashMap<K, Long>();
        need.put(target, BigInteger.valueOf(amount));
        int processed = 0;
        for (K item : order) {
            PlanningCancellation.check();
            BigInteger demand = need.getOrDefault(item, BigInteger.ZERO);
            if (demand.signum() <= 0) continue;
            processed++;
            gross.put(item, demand.longValueExact());
            BigInteger available = BigInteger.valueOf(graph.stock(item))
                    .add(BigInteger.valueOf(additionalStock.getOrDefault(item, 0L)));
            BigInteger drawn = demand.min(available);
            if (drawn.signum() > 0) used.put(item, drawn.longValueExact());
            demand = demand.subtract(drawn);
            if (demand.signum() == 0) continue;
            var route = routes.get(item);
            if (route == null) {
                missing.put(item, demand.longValueExact());
                continue;
            }
            BigInteger times = ceilDivide(demand, route.outputAmount());
            fired.put(route, times.longValueExact());
            for (var input : route.inputs()) {
                BigInteger next = need.getOrDefault(input.key(), BigInteger.ZERO)
                        .add(times.multiply(input.exactAmount()));
                if (next.compareTo(EXECUTABLE_LIMIT) >= 0) return null;
                need.put(input.key(), next);
            }
        }
        return new CraftPlan<>(true, missing.isEmpty(), fired, used, Map.of(), missing, gross, processed, false);
    }

    private static BigInteger ceilDivide(BigInteger amount, long divisor) {
        return amount.signum() == 0 ? BigInteger.ZERO
                : amount.subtract(BigInteger.ONE).divide(BigInteger.valueOf(divisor)).add(BigInteger.ONE);
    }

}
