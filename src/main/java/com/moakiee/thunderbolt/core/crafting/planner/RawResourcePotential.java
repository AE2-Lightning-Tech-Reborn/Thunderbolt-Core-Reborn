package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Positive raw-material weights for an ordinary DAG, including fractional costs of batch outputs. */
final class RawResourcePotential {
    private static final int MAX_BITS = 256;
    private record Cost(BigInteger numerator, BigInteger denominator) {
        Cost {
            BigInteger gcd = numerator.gcd(denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
        Cost add(Cost other, long copies) {
            return new Cost(numerator.multiply(other.denominator).add(
                    other.numerator.multiply(BigInteger.valueOf(copies)).multiply(denominator)),
                    denominator.multiply(other.denominator));
        }
        Cost divide(long divisor) {
            return new Cost(numerator, denominator.multiply(BigInteger.valueOf(divisor)));
        }
        boolean lessThan(Cost other) {
            return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator)) < 0;
        }
        boolean tooWide() {
            return numerator.bitLength() > MAX_BITS || denominator.bitLength() > MAX_BITS;
        }
    }

    private RawResourcePotential() { }

    /** Empty means the optional bound is unavailable. The order must put consumers before producers. */
    static <K> Map<K, BigInteger> weights(List<K> order, Map<K, List<CraftPattern<K>>> producers) {
        var costs = new HashMap<K, Cost>();
        BigInteger scale = BigInteger.ONE;
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanningCancellation.check();
            K item = order.get(i);
            var routes = producers.getOrDefault(item, List.of());
            Cost best = routes.isEmpty() ? new Cost(BigInteger.ONE, BigInteger.ONE) : null;
            for (var route : routes) {
                if (!route.byproducts().isEmpty()) return Map.of();
                Cost sum = new Cost(BigInteger.ZERO, BigInteger.ONE);
                for (var input : route.inputs()) {
                    Cost cost = costs.get(input.key());
                    if (cost == null || input.returned() || input.remainder() != null
                            || input.reusableStockSource() != null) return Map.of();
                    sum = sum.add(cost, input.amount());
                    if (sum.tooWide()) return Map.of();
                }
                sum = sum.divide(route.outputAmount());
                if (sum.tooWide()) return Map.of();
                if (best == null || sum.lessThan(best)) best = sum;
            }
            costs.put(item, best);
            scale = scale.divide(scale.gcd(best.denominator)).multiply(best.denominator);
            if (scale.bitLength() > MAX_BITS) return Map.of();
        }
        var weights = new HashMap<K, BigInteger>();
        for (K item : order) {
            PlanningCancellation.check();
            Cost cost = costs.get(item);
            BigInteger weight = cost.numerator.multiply(scale.divide(cost.denominator));
            if (weight.bitLength() > MAX_BITS) return Map.of();
            weights.put(item, weight);
        }
        return Map.copyOf(weights);
    }
}
