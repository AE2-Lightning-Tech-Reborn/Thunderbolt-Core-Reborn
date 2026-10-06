package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/** Exact, bounded mixed-batch witnesses for two ordinary routes fed directly by raw stock. */
final class TwoRouteBatchOptimizer {
    static final int MAX_WORK = 4096;
    private static final int MAX_INPUTS = 32;
    private static final int MAX_RESOURCES = 8;
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger SAT = BigInteger.valueOf(Sat.SAT);

    private TwoRouteBatchOptimizer() {}

    static <K> CraftPlan<K> tryImprove(CraftGraph<K> graph, K target, long amount,
            CraftPlan<K> initial, IntPredicate sharedWork) {
        var result = solve(graph, target, amount, initial, sharedWork);
        return result.plan() != initial ? result.plan() : null;
    }

    static <K> BatchOptimizationResult<K> solve(CraftGraph<K> graph, K target, long amount,
            CraftPlan<K> initial, IntPredicate sharedWork) {
        PlanningCancellation.check();
        var routes = graph.patternsFor(target);
        if (routes.size() != 2 || !initial.supported() || !initial.feasible()
                || !initial.missing().isEmpty() || !initial.usedReusableStock().isEmpty()
                || initial.firings().size() > 2 || initial.usedStock().size() > MAX_RESOURCES + 1
                || amount <= 0 || amount >= Sat.SAT) return BatchOptimizationResult.unsupported(initial);
        var a = routes.get(0);
        var b = routes.get(1);
        if (a == b) return BatchOptimizationResult.unsupported(initial);
        // The count-based optimum below is a certificate only for unit-cost routes.
        if (a.executionCost() != 1 || b.executionCost() != 1)
            return BatchOptimizationResult.unsupported(initial);
        if (a.inputs().size() + (long) b.inputs().size() > MAX_INPUTS) return BatchOptimizationResult.unsupported(initial);
        var budget = new Budget(sharedWork);
        if (!budget.charge(1 + a.inputs().size() + b.inputs().size())) return BatchOptimizationResult.exhausted(initial);
        var ca = rawCosts(graph, target, a);
        var cb = rawCosts(graph, target, b);
        if (ca == null || cb == null) return BatchOptimizationResult.unsupported(initial);
        var resources = new ArrayList<K>(ca.keySet());
        for (K key : cb.keySet()) if (!ca.containsKey(key)) resources.add(key);
        if (resources.isEmpty() || resources.size() > MAX_RESOURCES) return BatchOptimizationResult.unsupported(initial);
        for (var entry : initial.firings().entrySet()) {
            if ((entry.getKey() != a && entry.getKey() != b)
                    || entry.getValue() <= 0 || entry.getValue() >= Sat.SAT) return BatchOptimizationResult.unsupported(initial);
        }
        for (var entry : initial.usedStock().entrySet()) {
            if ((!entry.getKey().equals(target) && !resources.contains(entry.getKey()))
                    || entry.getValue() < 0 || entry.getValue() >= Sat.SAT
                    || entry.getValue() > graph.stock(entry.getKey())) return BatchOptimizationResult.unsupported(initial);
        }
        if (initial.grossDemand().values().stream().anyMatch(Sat::isSaturated)) return BatchOptimizationResult.unsupported(initial);
        long targetStock = initial.usedStock().getOrDefault(target, 0L);
        BigInteger demand = BigInteger.valueOf(amount).subtract(BigInteger.valueOf(targetStock));
        if (demand.signum() <= 0) return BatchOptimizationResult.unsupported(initial);
        BigInteger ao = a.exactOutputAmount(), bo = b.exactOutputAmount();
        var caps = new HashMap<K, BigInteger>();
        for (K key : resources) caps.put(key, BigInteger.valueOf(initial.usedStock().getOrDefault(key, 0L)));

        // On the continuous optimum output equals demand. Substitute y=(demand-ao*x)/bo
        // into every stock constraint to bound x. Ceil of that LP objective is a safe starting
        // count, not a certificate: integer feasibility is checked again for every count below.
        Fraction low = new Fraction(ZERO, ONE), high = new Fraction(demand, ao);
        for (K key : resources) {
            BigInteger ac = ca.getOrDefault(key, ZERO), bc = cb.getOrDefault(key, ZERO);
            BigInteger slope = ac.multiply(bo).subtract(bc.multiply(ao));
            BigInteger room = caps.get(key).multiply(bo).subtract(bc.multiply(demand));
            if (slope.signum() > 0) high = high.min(new Fraction(room, slope));
            else if (slope.signum() < 0) low = low.max(new Fraction(room.negate(), slope.negate()));
            else if (room.signum() < 0) return BatchOptimizationResult.unsupported(initial);
        }
        if (low.compareTo(high) > 0) return BatchOptimizationResult.unsupported(initial);
        Fraction endpoint = ao.compareTo(bo) > 0 ? high : low;
        BigInteger count = ceil(demand.multiply(endpoint.denominator())
                .add(bo.subtract(ao).multiply(endpoint.numerator())), bo.multiply(endpoint.denominator()));
        BigInteger incumbentCount = initial.firings().values().stream()
                .map(BigInteger::valueOf).reduce(ZERO, BigInteger::add);
        BigInteger stockSlope = ca.values().stream().reduce(ZERO, BigInteger::add)
                .subtract(cb.values().stream().reduce(ZERO, BigInteger::add));
        for (; count.compareTo(incumbentCount) <= 0 && count.compareTo(SAT) < 0; count = count.add(ONE)) {
            if (!budget.charge(1 + resources.size())) return BatchOptimizationResult.exhausted(initial);
            BigInteger lo = ZERO, hi = count;
            BigInteger outputSlope = ao.subtract(bo), deficit = demand.subtract(count.multiply(bo));
            if (outputSlope.signum() > 0) lo = lo.max(ceil(deficit, outputSlope));
            else if (outputSlope.signum() < 0) hi = hi.min(floor(deficit.negate(), outputSlope.negate()));
            else if (deficit.signum() > 0) continue;
            for (K key : resources) {
                BigInteger ac = ca.getOrDefault(key, ZERO), bc = cb.getOrDefault(key, ZERO);
                BigInteger slope = ac.subtract(bc), room = caps.get(key).subtract(count.multiply(bc));
                if (slope.signum() > 0) hi = hi.min(floor(room, slope));
                else if (slope.signum() < 0) lo = lo.max(ceil(room.negate(), slope.negate()));
                else if (room.signum() < 0) { hi = ONE.negate(); break; }
            }
            if (lo.compareTo(hi) > 0) continue;
            BigInteger x = stockSlope.signum() > 0 ? lo : hi, y = count.subtract(x);
            var firings = new HashMap<CraftPattern<K>, Long>();
            if (x.signum() > 0) firings.put(a, x.longValueExact());
            if (y.signum() > 0) firings.put(b, y.longValueExact());
            var used = new HashMap<K, Long>();
            if (targetStock > 0) used.put(target, targetStock);
            var gross = new HashMap<K, Long>();
            gross.put(target, amount);
            // Independent exact reconstruction: original pattern objects, exact input amounts,
            // no intermediate/stateful/byproduct arcs. Raw stock then these firings is executable.
            if (ao.multiply(x).add(bo.multiply(y)).compareTo(demand) < 0) return BatchOptimizationResult.unsupported(initial);
            for (K key : resources) {
                BigInteger use = ca.getOrDefault(key, ZERO).multiply(x).add(cb.getOrDefault(key, ZERO).multiply(y));
                if (use.signum() < 0 || use.compareTo(caps.get(key)) > 0 || use.compareTo(SAT) >= 0) return BatchOptimizationResult.unsupported(initial);
                if (use.signum() > 0) { used.put(key, use.longValueExact()); gross.put(key, use.longValueExact()); }
            }
            var candidate = new CraftPlan<>(true, true, Map.copyOf(firings), Map.copyOf(used),
                    initial.usedReusableStock(), Map.<K, Long>of(), Map.copyOf(gross),
                    resources.size() + 1, initial.budgetExhausted());
            return BatchOptimizationResult.complete(
                    FeasibleConsumptionOptimizer.improves(initial, candidate) ? candidate : initial);
        }
        return BatchOptimizationResult.unsupported(initial);
    }

    private static <K> Map<K, BigInteger> rawCosts(CraftGraph<K> graph, K target, CraftPattern<K> pattern) {
        if (!pattern.byproducts().isEmpty() || pattern.exactOutputAmount().signum() <= 0
                || pattern.exactOutputAmount().compareTo(SAT) >= 0) return null;
        var costs = new HashMap<K, BigInteger>();
        for (var input : pattern.inputs()) {
            if (input.returned() || input.remainder() != null || input.reusableStockSource() != null
                    || input.key().equals(target) || !graph.patternsFor(input.key()).isEmpty()
                    || input.exactAmount().signum() <= 0 || input.exactAmount().compareTo(SAT) >= 0) return null;
            costs.merge(input.key(), input.exactAmount(), BigInteger::add);
        }
        return costs;
    }

    private static BigInteger floor(BigInteger numerator, BigInteger denominator) {
        var qr = numerator.divideAndRemainder(denominator);
        return qr[1].signum() < 0 ? qr[0].subtract(ONE) : qr[0];
    }

    private static BigInteger ceil(BigInteger numerator, BigInteger denominator) {
        return floor(numerator.negate(), denominator).negate();
    }

    private record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {
        public int compareTo(Fraction other) {
            return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
        }
        Fraction min(Fraction other) { return compareTo(other) <= 0 ? this : other; }
        Fraction max(Fraction other) { return compareTo(other) >= 0 ? this : other; }
    }

    private static final class Budget {
        private final IntPredicate shared;
        private int remaining = MAX_WORK;
        private Budget(IntPredicate shared) { this.shared = shared; }
        boolean charge(int work) {
            PlanningCancellation.check();
            if (work > remaining || !shared.test(work)) return false;
            remaining -= work;
            return true;
        }
    }
}
