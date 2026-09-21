package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wide-number diagnostic sweep over V2's normalized graph and byproduct schedule.
 * Uses the same aggregated demand/stock/recipe-capacity model as its linear backbone.
 * Quantities are never rounded or capped. The result is display-only: bounded route selection
 * and conservative cycle cuts are not a proof that every alternative is infeasible.
 */
final class ExactDiagnosticPlanner<K> {
    private static final BigInteger ZERO = BigInteger.ZERO;
    private final CraftGraph<K> graph;
    private final List<K> order;
    private final Map<K, List<CraftPattern<K>>> patterns;
    private final Map<CraftPattern<K>, Set<K>> reusableByproducts;
    private final Map<CraftPattern<K>, Map<K, Long>> bootstrap;
    private final Set<K> emitted;
    private final Map<K, BigInteger> capacity = new HashMap<>();
    private final Set<K> unlimited = new java.util.HashSet<>();
    private final Map<K, BigInteger> need = new HashMap<>();
    private final Map<K, BigInteger> surplus = new HashMap<>();
    private final Map<K, BigInteger> used = new HashMap<>();
    private final Map<K, BigInteger> missing = new HashMap<>();
    private final Map<K, BigInteger> gross = new HashMap<>();
    private final Map<Object, Map<K, BigInteger>> seeds = new HashMap<>();
    private final Map<ReusableStockKey<K>, BigInteger> privateLeft = new HashMap<>();
    private final Map<CraftPattern<K>, BigInteger> fired = new IdentityHashMap<>();

    ExactDiagnosticPlanner(CraftGraph<K> graph, List<K> order,
            Map<K, List<CraftPattern<K>>> patterns,
            Map<CraftPattern<K>, Set<K>> reusableByproducts,
            Map<CraftPattern<K>, Map<K, Long>> bootstrap, Set<K> emitted) {
        this.graph = graph;
        this.order = order;
        this.patterns = patterns;
        this.reusableByproducts = reusableByproducts;
        this.bootstrap = bootstrap;
        this.emitted = emitted;
        graph.reusableStock().forEach((key, value) -> privateLeft.put(key, BigInteger.valueOf(value)));
    }

    ExactCraftPlan<K> plan(K target, BigInteger amount, boolean incomplete) {
        buildCapacity();
        need.put(target, amount);
        for (K key : order) {
            PlanningCancellation.check();
            BigInteger requested = get(need, key);
            if (requested.signum() == 0) continue;
            gross.put(key, requested);
            BigInteger fromSurplus = requested.min(get(surplus, key));
            surplus.put(key, get(surplus, key).subtract(fromSurplus));
            BigInteger remaining = requested.subtract(fromSurplus);
            BigInteger fromStock = emitted.contains(key) ? remaining : remaining.min(graph.exactStock(key));
            if (fromStock.signum() > 0) used.put(key, fromStock);
            remaining = remaining.subtract(fromStock);
            if (remaining.signum() == 0) continue;
            List<CraftPattern<K>> candidates = new ArrayList<>(patterns.getOrDefault(key, List.of()));
            if (candidates.isEmpty()) {
                missing.put(key, remaining);
                continue;
            }
            BigInteger demand = remaining;
            candidates.sort((a, b) -> supported(b, demand, true).compareTo(supported(a, demand, true)));
            for (CraftPattern<K> pattern : candidates) {
                if (remaining.signum() == 0) break;
                BigInteger times = supported(pattern, remaining, true);
                if (times.signum() == 0) continue;
                BigInteger allocated = times.multiply(pattern.exactOutputAmount()).min(remaining);
                fire(pattern, times, allocated);
                remaining = remaining.subtract(allocated);
            }
            if (remaining.signum() > 0) {
                // As in V2's diagnostic fallback, keep one concrete route and propagate its
                // entire shortfall to leaves. Never loop over individual recipe executions.
                CraftPattern<K> route = candidates.get(0);
                fire(route, ceilDiv(remaining, route.exactOutputAmount()), remaining);
            }
        }
        // A normalized cycle/bootstrap may refer to a key outside the acyclic sweep.
        // Keep that unresolved demand visible instead of silently dropping it.
        for (var entry : need.entrySet()) {
            BigInteger unresolved = entry.getValue().subtract(get(gross, entry.getKey())).max(ZERO);
            if (unresolved.signum() > 0) {
                gross.put(entry.getKey(), entry.getValue());
                add(missing, entry.getKey(), unresolved);
                incomplete = true;
            }
        }
        return new ExactCraftPlan<>(fired, used, missing, gross, incomplete);
    }

    private void buildCapacity() {
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanningCancellation.check();
            K key = order.get(i);
            if (emitted.contains(key)) {
                unlimited.add(key);
                continue;
            }
            BigInteger total = graph.exactStock(key);
            for (CraftPattern<K> pattern : patterns.getOrDefault(key, List.of())) {
                BigInteger bound = null; // Explicit infinity, never a large finite sentinel.
                Map<K, BigInteger> perFiring = new HashMap<>();
                for (CraftInput<K> input : pattern.inputs()) {
                    if (unlimited.contains(input.key())) continue;
                    BigInteger available = get(capacity, input.key());
                    if (input.reusableStockSource() != null) {
                        for (K actual : graph.reusableStockCandidates(input.reusableStockSource(), input.key())) {
                            available = available.add(BigInteger.valueOf(graph.reusableStock(
                                    input.reusableStockSource().storageScope(), actual)));
                        }
                    }
                    if (!input.returned()) {
                        perFiring.merge(input.key(), input.exactAmount(), BigInteger::add);
                        BigInteger candidate = available.divide(perFiring.get(input.key()));
                        bound = bound == null ? candidate : bound.min(candidate);
                    } else if (input.uses() == CraftInput.INFINITE_USES) {
                        if (available.compareTo(input.exactAmount()) < 0) bound = ZERO;
                    } else {
                        BigInteger candidate = available.divide(input.exactAmount())
                                .multiply(BigInteger.valueOf(input.uses()));
                        bound = bound == null ? candidate : bound.min(candidate);
                    }
                }
                if (bound == null) {
                    unlimited.add(key);
                    break;
                }
                total = checked(total.add(bound.multiply(pattern.exactOutputAmount())));
            }
            capacity.put(key, total);
        }
    }

    private BigInteger supported(CraftPattern<K> pattern, BigInteger demand, boolean reserved) {
        BigInteger limit = ceilDiv(demand, pattern.exactOutputAmount());
        Map<K, BigInteger> perFiring = new HashMap<>();
        for (CraftInput<K> input : pattern.inputs()) {
            if (unlimited.contains(input.key())) continue;
            BigInteger available = get(capacity, input.key());
            if (reserved) available = available.subtract(get(need, input.key())).max(ZERO);
            if (input.reusableStockSource() != null) {
                for (K actual : graph.reusableStockCandidates(input.reusableStockSource(), input.key())) {
                    available = available.add(privateLeft.getOrDefault(new ReusableStockKey<>(
                            input.reusableStockSource().storageScope(), actual), ZERO));
                }
            }
            if (!input.returned()) {
                perFiring.merge(input.key(), input.exactAmount(), BigInteger::add);
                limit = limit.min(available.divide(perFiring.get(input.key())));
            } else if (input.uses() == CraftInput.INFINITE_USES) {
                if (available.compareTo(input.exactAmount()) < 0) return ZERO;
            } else {
                limit = limit.min(available.divide(input.exactAmount()).multiply(BigInteger.valueOf(input.uses())));
            }
        }
        return limit;
    }

    private void fire(CraftPattern<K> pattern, BigInteger times, BigInteger allocated) {
        BigInteger previous = fired.getOrDefault(pattern, ZERO);
        BigInteger total = checked(previous.add(times));
        fired.put(pattern, total);
        for (CraftInput<K> input : pattern.inputs()) {
            BigInteger required = input.unitsForExact(total).subtract(input.unitsForExact(previous));
            if (input.returned() && input.uses() == CraftInput.INFINITE_USES) {
                Object scope = input.reusableStockSource() == null ? this : input.reusableStockSource().poolScope();
                Map<K, BigInteger> pool = seeds.computeIfAbsent(scope, ignored -> new HashMap<>());
                BigInteger old = get(pool, input.key());
                required = input.exactAmount().subtract(old).max(ZERO);
                pool.put(input.key(), old.max(input.exactAmount()));
                if (input.reusableStockSource() != null) {
                    for (K actual : graph.reusableStockCandidates(input.reusableStockSource(), input.key())) {
                        var privateKey = new ReusableStockKey<>(input.reusableStockSource().storageScope(), actual);
                        BigInteger have = privateLeft.getOrDefault(privateKey, ZERO);
                        BigInteger take = required.min(have);
                        privateLeft.put(privateKey, have.subtract(take));
                        required = required.subtract(take);
                    }
                }
            }
            if (required.signum() > 0) add(need, input.key(), required);
        }
        Map<K, Long> reserves = bootstrap.getOrDefault(pattern, Map.of());
        for (CraftOutput<K> output : pattern.byproducts()) {
            if (!reusableByproducts.getOrDefault(pattern, Set.of()).contains(output.key())) continue;
            BigInteger reserve = BigInteger.valueOf(reserves.getOrDefault(output.key(), 0L));
            BigInteger withheld = reserve.subtract(output.exactAmount().multiply(previous)).max(ZERO);
            BigInteger supply = output.exactAmount().multiply(times).subtract(withheld).max(ZERO);
            add(surplus, output.key(), supply);
        }
        add(surplus, pattern.output(), pattern.exactOutputAmount().multiply(times).subtract(allocated));
    }

    static BigInteger ceilDiv(BigInteger value, BigInteger divisor) {
        if (value.signum() == 0) return ZERO;
        return checked(value.subtract(BigInteger.ONE).divide(divisor).add(BigInteger.ONE));
    }

    /** Bound representation cost, not numeric magnitude at the old machine-integer boundary. */
    static BigInteger checked(BigInteger amount) {
        if (amount.bitLength() > 32_768) {
            throw new IllegalArgumentException("Exact crafting quantity exceeds the 32768-bit calculation limit");
        }
        return amount;
    }

    private static <K> BigInteger get(Map<K, BigInteger> values, K key) {
        return values.getOrDefault(key, ZERO);
    }

    private static <K> void add(Map<K, BigInteger> values, K key, BigInteger value) {
        if (value.signum() > 0) values.put(key, checked(get(values, key).add(value)));
    }
}
