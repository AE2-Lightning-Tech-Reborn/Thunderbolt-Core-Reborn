package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/** Bounded exact integer witnesses for a small raw-stock target with three to six routes. */
final class MultiRouteBatchOptimizer {
    static final int MAX_WORK = 16_384;
    static final int MAX_COUNT = 64;
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger SAT = BigInteger.valueOf(Sat.SAT);

    private MultiRouteBatchOptimizer() {}

    static <K> BatchOptimizationResult<K> solve(CraftGraph<K> graph, K target, long amount,
            CraftPlan<K> initial, IntPredicate sharedWork) {
        PlanningCancellation.check();
        List<CraftPattern<K>> routes = new ArrayList<>(graph.patternsFor(target));
        OrdinaryBatchOptimizer.sortRoutes(routes);
        int size = routes.size();
        if (size < 3 || size > 6 || amount <= 0 || amount >= Sat.SAT
                || !initial.supported() || !initial.feasible() || !initial.missing().isEmpty()
                || !initial.usedReusableStock().isEmpty() || initial.usedStock().size() > 9
                || new HashSet<>(routes).size() != size) return BatchOptimizationResult.unsupported(initial);
        long count = 0;
        for (var entry : initial.firings().entrySet()) {
            if (!routes.contains(entry.getKey()) || entry.getValue() <= 0 || entry.getValue() > MAX_COUNT
                    || (count += entry.getValue()) > MAX_COUNT) return BatchOptimizationResult.unsupported(initial);
        }
        if (initial.grossDemand().values().stream().anyMatch(Sat::isSaturated))
            return BatchOptimizationResult.unsupported(initial);
        int slots = 0;
        for (var route : routes) if ((slots += route.inputs().size()) > 32)
            return BatchOptimizationResult.unsupported(initial);
        if (!sharedWork.test(1 + slots)) return BatchOptimizationResult.exhausted(initial);
        var costs = new ArrayList<Map<K, BigInteger>>();
        var resources = new ArrayList<K>();
        BigInteger maxOutput = ZERO;
        for (var route : routes) {
            PlanningCancellation.check();
            // Enumerating total firings proves the objective only when every route costs one.
            if (route.executionCost() != 1 || !route.byproducts().isEmpty()
                    || route.exactOutputAmount().compareTo(SAT) >= 0)
                return BatchOptimizationResult.unsupported(initial);
            maxOutput = maxOutput.max(route.exactOutputAmount());
            var cost = new HashMap<K, BigInteger>();
            for (var input : route.inputs()) {
                if (input.returned() || input.remainder() != null || input.reusableStockSource() != null
                        || input.key().equals(target) || !graph.patternsFor(input.key()).isEmpty()
                        || input.exactAmount().signum() <= 0 || input.exactAmount().compareTo(SAT) >= 0)
                    return BatchOptimizationResult.unsupported(initial);
                cost.merge(input.key(), input.exactAmount(), BigInteger::add);
                if (!resources.contains(input.key())) resources.add(input.key());
            }
            costs.add(cost);
        }
        if (resources.isEmpty() || resources.size() > 8) return BatchOptimizationResult.unsupported(initial);
        for (var entry : initial.usedStock().entrySet()) {
            if ((!entry.getKey().equals(target) && !resources.contains(entry.getKey()))
                    || entry.getValue() < 0 || entry.getValue() >= Sat.SAT
                    || entry.getValue() > graph.stock(entry.getKey())) return BatchOptimizationResult.unsupported(initial);
        }
        long targetStock = initial.usedStock().getOrDefault(target, 0L);
        BigInteger demand = BigInteger.valueOf(amount).subtract(BigInteger.valueOf(targetStock));
        if (demand.signum() <= 0) return BatchOptimizationResult.unsupported(initial);
        var caps = new BigInteger[resources.size()];
        var use = new BigInteger[size][resources.size()];
        var outputs = new BigInteger[size];
        for (int r = 0; r < resources.size(); r++) caps[r] = BigInteger.valueOf(initial.usedStock().getOrDefault(resources.get(r), 0L));
        for (int p = 0; p < size; p++) {
            outputs[p] = routes.get(p).exactOutputAmount();
            for (int r = 0; r < resources.size(); r++) use[p][r] = costs.get(p).getOrDefault(resources.get(r), ZERO);
        }
        var search = new Search(outputs, use, caps, demand, sharedWork, MAX_WORK - 1 - slots);
        BigInteger lowerBound = demand.subtract(BigInteger.ONE).divide(maxOutput).add(BigInteger.ONE);
        if (lowerBound.compareTo(BigInteger.valueOf(count)) > 0) return BatchOptimizationResult.unsupported(initial);
        int lower = lowerBound.intValueExact();
        for (int total = lower; total <= count; total++) {
            search.best = null;
            search.bestStock = null;
            search.seedSingleRouteWitness(total);
            search.visit(0, total, ZERO, caps.clone(), new long[size]);
            if (search.best != null) {
                var candidate = certificate(target, amount, targetStock, initial, routes, resources, outputs, use, caps, demand, search.best);
                if (candidate == null) return BatchOptimizationResult.unsupported(initial);
                CraftPlan<K> result = FeasibleConsumptionOptimizer.improves(initial, candidate) ? candidate : initial;
                return search.exhausted ? BatchOptimizationResult.exhausted(result) : BatchOptimizationResult.complete(result);
            }
            if (search.exhausted) return BatchOptimizationResult.exhausted(initial);
        }
        return BatchOptimizationResult.unsupported(initial); // No feasible witness despite a certified incumbent: fail closed.
    }

    private static <K> CraftPlan<K> certificate(K target, long amount, long targetStock, CraftPlan<K> initial,
            List<CraftPattern<K>> routes, List<K> resources, BigInteger[] outputs, BigInteger[][] use,
            BigInteger[] caps, BigInteger demand, long[] counts) {
        var fired = new HashMap<CraftPattern<K>, Long>();
        BigInteger produced = ZERO;
        for (int p = 0; p < counts.length; p++) {
            if (counts[p] < 0) return null;
            if (counts[p] > 0) fired.put(routes.get(p), counts[p]);
            produced = produced.add(outputs[p].multiply(BigInteger.valueOf(counts[p])));
        }
        if (produced.compareTo(demand) < 0) return null;
        var used = new HashMap<K, Long>();
        var gross = new HashMap<K, Long>();
        gross.put(target, amount);
        if (targetStock > 0) used.put(target, targetStock);
        for (int r = 0; r < resources.size(); r++) {
            BigInteger consumed = ZERO;
            for (int p = 0; p < counts.length; p++) consumed = consumed.add(use[p][r].multiply(BigInteger.valueOf(counts[p])));
            if (consumed.compareTo(caps[r]) > 0 || consumed.compareTo(SAT) >= 0) return null;
            if (consumed.signum() > 0) { used.put(resources.get(r), consumed.longValueExact()); gross.put(resources.get(r), consumed.longValueExact()); }
        }
        return new CraftPlan<>(true, true, Map.copyOf(fired), Map.copyOf(used), Map.of(), Map.of(),
                Map.copyOf(gross), resources.size() + 1, initial.budgetExhausted());
    }

    private static final class Search {
        final BigInteger[] outputs, caps, pairSlopes;
        final BigInteger[][] use;
        final BigInteger demand, outputSlope;
        final BigInteger stockSlope, lastStock, totalStock;
        final IntPredicate shared;
        int remaining;
        boolean exhausted;
        long[] best;
        BigInteger bestStock;

        Search(BigInteger[] outputs, BigInteger[][] use, BigInteger[] caps, BigInteger demand, IntPredicate shared, int work) {
            this.outputs = outputs; this.use = use; this.caps = caps; this.demand = demand;
            this.shared = shared; remaining = work;
            int last = outputs.length - 1;
            outputSlope = outputs[last].subtract(outputs[last - 1]);
            pairSlopes = new BigInteger[caps.length];
            BigInteger slope = ZERO, lastUse = ZERO, total = ZERO;
            for (int r = 0; r < caps.length; r++) {
                pairSlopes[r] = use[last - 1][r].subtract(use[last][r]);
                slope = slope.add(pairSlopes[r]);
                lastUse = lastUse.add(use[last][r]);
                total = total.add(caps[r]);
            }
            stockSlope = slope; lastStock = lastUse; totalStock = total;
        }

        /** Preserve a cheap certified incumbent before route-order search can exhaust its budget. */
        void seedSingleRouteWitness(int total) {
            for (int route = 0; route < outputs.length; route++) {
                BigInteger firings = demand.subtract(BigInteger.ONE)
                        .divide(outputs[route]).add(BigInteger.ONE);
                if (firings.signum() <= 0 || firings.compareTo(BigInteger.valueOf(total)) > 0
                        || firings.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) continue;
                BigInteger stock = ZERO;
                boolean fits = true;
                for (int resource = 0; resource < caps.length; resource++) {
                    BigInteger consumed = use[route][resource].multiply(firings);
                    if (consumed.compareTo(caps[resource]) > 0) {
                        fits = false;
                        break;
                    }
                    stock = stock.add(consumed);
                }
                if (!fits || bestStock != null && stock.compareTo(bestStock) >= 0) continue;
                bestStock = stock;
                best = new long[outputs.length];
                best[route] = firings.longValueExact();
            }
        }

        void visit(int route, int left, BigInteger produced, BigInteger[] available, long[] counts) {
            PlanningCancellation.check();
            if (exhausted) return;
            // A solved pair evaluates both the interval constraints and the stock objective.
            int work = (1 + caps.length) * (route == outputs.length - 2 ? 2 : 1);
            if (work > remaining || !shared.test(work)) { exhausted = true; return; }
            remaining -= work;
            BigInteger max = ZERO;
            for (int p = route; p < outputs.length; p++) max = max.max(outputs[p]);
            if (produced.add(max.multiply(BigInteger.valueOf(left))).compareTo(demand) < 0) return;
            if (route == outputs.length - 2) {
                // x + y = left removes one integer dimension. Every original resource and
                // the output requirement intersect an interval for x; stock use is linear,
                // so its minimum is an endpoint. Ties take the smaller x, as enumeration did.
                int last = route + 1;
                var n = BigInteger.valueOf(left);
                var interval = new IntegerInterval(ZERO, n);
                if (!interval.atMost(outputSlope,
                        produced.add(outputs[last].multiply(n)).subtract(demand))) return;
                BigInteger availableStock = ZERO;
                for (int r = 0; r < caps.length; r++) {
                    var intercept = use[last][r].multiply(n);
                    if (!interval.atMost(pairSlopes[r], available[r].subtract(intercept))) return;
                    availableStock = availableStock.add(available[r]);
                }
                var x = stockSlope.signum() < 0 ? interval.high : interval.low;
                var stock = totalStock.subtract(availableStock).add(lastStock.multiply(n)).add(stockSlope.multiply(x));
                if (bestStock == null || stock.compareTo(bestStock) < 0) {
                    counts[route] = x.longValueExact();
                    counts[last] = left - counts[route];
                    best = counts.clone(); bestStock = stock;
                }
                return;
            }
            for (int n = 0; n <= left && !exhausted; n++) {
                BigInteger multiplier = BigInteger.valueOf(n);
                BigInteger[] next = new BigInteger[caps.length];
                boolean fits = true;
                for (int r = 0; r < caps.length; r++) {
                    next[r] = available[r].subtract(use[route][r].multiply(multiplier));
                    fits &= next[r].signum() >= 0;
                }
                if (!fits) break;
                counts[route] = n;
                visit(route + 1, left - n, produced.add(outputs[route].multiply(multiplier)), next, counts);
            }
        }
    }
}
