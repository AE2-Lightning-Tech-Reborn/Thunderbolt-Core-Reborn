package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stock-independent recipe policy for an ordinary material DAG. It supplies a reproducible
 * replenishment witness when optional route optimization cannot finish. It admits no byproducts:
 * substituting additional stock for a producer must never remove supply needed elsewhere. For this
 * policy, increasing any inventory can only decrease upstream demand.
 *
 * <p>Stateful recipes retain the planner's existing ordered bootstrap path. This class never treats
 * a catalyst, host-private pool or cut feedback arc as an ordinary consumable.
 */
final class ConservativeReplenishment<K> {
    private static final BigInteger EXECUTABLE_LIMIT = BigInteger.valueOf(Sat.SAT);
    private final CraftGraph<K> graph;
    private final List<K> order;
    private final Map<K, CraftPattern<K>> routes;
    private final Map<K, Long> unitCosts;
    private final BigInteger stockCost;

    private ConservativeReplenishment(
            CraftGraph<K> graph, List<K> order, Map<K, CraftPattern<K>> routes,
            Map<K, Long> unitCosts) {
        this.graph = graph;
        this.order = order;
        this.routes = Map.copyOf(routes);
        this.unitCosts = Map.copyOf(unitCosts);
        BigInteger stockCost = BigInteger.ZERO;
        for (var entry : unitCosts.entrySet()) {
            stockCost = stockCost.add(BigInteger.valueOf(graph.stock(entry.getKey()))
                    .multiply(BigInteger.valueOf(entry.getValue())));
        }
        this.stockCost = stockCost;
    }

    static <K> ConservativeReplenishment<K> compile(
            CraftGraph<K> graph, List<K> order, Map<K, List<CraftPattern<K>>> patterns) {
        var position = new HashMap<K, Integer>();
        for (int i = 0; i < order.size(); i++) position.put(order.get(i), i);
        var costs = new HashMap<K, Long>();
        var routes = new HashMap<K, CraftPattern<K>>();
        boolean unitOutputs = true;
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
                unitOutputs &= pattern.outputAmount() == 1L;
                if (!pattern.byproducts().isEmpty()
                        || pattern.exactOutputAmount().compareTo(EXECUTABLE_LIMIT) >= 0) return null;
                long cost = 0L;
                for (var input : pattern.inputs()) {
                    if (input.returned() || input.reusableStockSource() != null
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
        return new ConservativeReplenishment<>(graph, List.copyOf(order), routes, unitOutputs ? costs : Map.of());
    }

    /**
     * With unit outputs, c(output) <= sum c(inputs) for every recipe. Thus weighted stock + supply
     * must cover the target's weight. Matching this lower bound excludes any componentwise smaller
     * supply when every reported key has positive weight. Callers must exclude cut orientations.
     */
    boolean provesMinimumMissing(K target, long amount, Map<K, Long> missing) {
        if (unitCosts.isEmpty()) return false;
        BigInteger supplyCost = BigInteger.ZERO;
        for (var entry : missing.entrySet()) {
            long cost = unitCosts.getOrDefault(entry.getKey(), 0L);
            if (cost <= 0L) return false;
            supplyCost = supplyCost.add(BigInteger.valueOf(cost).multiply(BigInteger.valueOf(entry.getValue())));
        }
        BigInteger required = BigInteger.valueOf(unitCosts.getOrDefault(target, 0L))
                .multiply(BigInteger.valueOf(amount)).subtract(stockCost).max(BigInteger.ZERO);
        return supplyCost.equals(required);
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
