package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** Bounded coordinate descent over executable replenishments, never over individual craft units. */
final class MissingRefinement {
    private MissingRefinement() {}

    static <K> boolean strictlyDominates(Map<K, Long> less, Map<K, Long> more) {
        for (var entry : less.entrySet()) {
            if (entry.getValue() > more.getOrDefault(entry.getKey(), 0L)) return false;
        }
        return more.entrySet().stream().anyMatch(entry ->
                less.getOrDefault(entry.getKey(), 0L) < entry.getValue());
    }

    /** Null from the oracle means its shared work budget is exhausted; keep the last witness. */
    static <K> CraftPlan<K> refine(CraftGraph<K> graph, CraftPlan<K> initial, List<K> order,
            Predicate<CraftPlan<K>> minimumProven, Function<Map<K, Long>, CraftPlan<K>> oracle,
            java.util.function.LongConsumer accepted) {
        return refine(graph, initial, order, minimumProven, (plan, key) -> -1L, oracle, accepted);
    }

    static <K> CraftPlan<K> refine(CraftGraph<K> graph, CraftPlan<K> initial, List<K> order,
            Predicate<CraftPlan<K>> minimumProven,
            java.util.function.ToLongBiFunction<CraftPlan<K>, K> minimumSupply,
            Function<Map<K, Long>, CraftPlan<K>> oracle, java.util.function.LongConsumer accepted) {
        CraftPlan<K> best = initial;
        var keys = new LinkedHashSet<K>();
        for (K key : order) if (initial.missing().containsKey(key)) keys.add(key);
        keys.addAll(initial.missing().keySet());
        try {
            var orderedKeys = keys.stream().sorted((a, b) -> Long.compare(
                    initial.missing().getOrDefault(b, 0L), initial.missing().getOrDefault(a, 0L))).toList();
            for (K key : orderedKeys) {
                PlanningCancellation.check();
                long upper = best.missing().getOrDefault(key, 0L);
                if (upper <= 0) continue;
                long lower = minimumSupply.applyAsLong(best, key);
                if (lower >= upper) continue;
                if (lower >= 0L && lower < upper - 1L) {
                    // The resource bound often makes the remaining integer model much easier:
                    // at equality, every more expensive route is eliminated by sparse presolve.
                    CraftPlan<K> guided = probe(graph, best, key, lower, oracle);
                    if (guided == null) break;
                    if (strictlyDominates(guided.missing(), best.missing())) {
                        best = guided;
                        accepted.accept(1);
                        if (minimumProven.test(best)) return best;
                        upper = best.missing().getOrDefault(key, 0L);
                        if (upper == 0L) continue;
                    }
                }
                // If even one less is not verified, do not spend log(Q) probes on this coordinate.
                CraftPlan<K> probe = probe(graph, best, key, upper - 1L, oracle);
                if (probe == null) break;
                if (!strictlyDominates(probe.missing(), best.missing())) continue;
                best = probe;
                accepted.accept(1);
                if (minimumProven.test(best)) return best;
                upper = best.missing().getOrDefault(key, 0L);
                if (upper == 0) continue;
                // Grow the reduction geometrically. Near-minimal plans stop after another probe;
                // a larger overestimate yields useful intermediate improvements before a cutoff.
                long step = 1L;
                while (upper > 0L) {
                    long trial = Math.max(0L, upper - step);
                    probe = probe(graph, best, key, trial, oracle);
                    if (probe == null) return best;
                    if (strictlyDominates(probe.missing(), best.missing())) {
                        best = probe;
                        accepted.accept(1);
                        if (minimumProven.test(best)) return best;
                        upper = best.missing().getOrDefault(key, 0L);
                        step = step > upper / 2L ? upper : step * 2L;
                        continue;
                    }
                    long rejectedLower = trial + 1L;
                    while (rejectedLower < upper) {
                        long middle = rejectedLower + (upper - rejectedLower) / 2L;
                        probe = probe(graph, best, key, middle, oracle);
                        if (probe == null) return best;
                        if (strictlyDominates(probe.missing(), best.missing())) {
                            best = probe;
                            accepted.accept(1);
                            if (minimumProven.test(best)) return best;
                            upper = best.missing().getOrDefault(key, 0L);
                        } else {
                            // A heuristic false negative can reduce quality, never validity.
                            rejectedLower = middle + 1L;
                        }
                    }
                    break;
                }
            }
        } catch (PlanningCancellation.OptionalWorkLimit ignored) {
            // Only this optional stage's private deadline is recoverable. Outer cancellation and
            // PlanningExitException still propagate through the normal planning lifecycle.
        }
        return best;
    }

    private static <K> CraftPlan<K> probe(CraftGraph<K> graph, CraftPlan<K> best, K key, long amount,
            Function<Map<K, Long>, CraftPlan<K>> oracle) {
        var supplied = new HashMap<>(best.missing());
        if (amount == 0L) supplied.remove(key);
        else supplied.put(key, amount);
        CraftPlan<K> ready = oracle.apply(Map.copyOf(supplied));
        if (ready == null) return null;
        CraftPlan<K> accepted = suppliedPlan(graph, best, supplied, ready);
        if (accepted == best) return best;

        // A route switch can leave almost all of a trillion-sized trial supply unused. Jump to
        // its actual extraction in one additional probe instead of spending the shared allowance
        // on geometric unit reductions. Replanning is mandatory: less stock can change the route.
        var tight = new HashMap<K, Long>();
        for (var entry : ready.usedStock().entrySet()) {
            long extra = entry.getValue() - Math.min(graph.stock(entry.getKey()), entry.getValue());
            if (extra > 0L) tight.put(entry.getKey(), extra);
        }
        if (!strictlyDominates(tight, supplied)) return accepted;
        try {
            CraftPlan<K> rechecked = oracle.apply(Map.copyOf(tight));
            return rechecked == null ? accepted : suppliedPlan(graph, accepted, tight, rechecked);
        } catch (PlanningCancellation.OptionalWorkLimit exhausted) {
            return accepted; // The first probe is already a complete replenishment witness.
        }
    }

    private static <K> CraftPlan<K> suppliedPlan(CraftGraph<K> graph, CraftPlan<K> best,
            Map<K, Long> supplied, CraftPlan<K> ready) {
        if (!ready.supported() || !ready.feasible() || !ready.missing().isEmpty()) return best;

        // A probe plans against original stock + its hypothetical replenishment. Split the actual
        // extraction back into real stock and supply, and retain the probe's complete firing vector.
        var used = new HashMap<K, Long>();
        for (var entry : ready.usedStock().entrySet()) {
            long original = Math.min(graph.stock(entry.getKey()), entry.getValue());
            long extra = entry.getValue() - original;
            if (extra > supplied.getOrDefault(entry.getKey(), 0L) || original < 0L) return best;
            if (original > 0L) used.put(entry.getKey(), original);
        }
        // Retain the exact inventory that was tested. Silently trimming unused supplied stock can
        // change the next calculation's route choice; that reduction needs its own successful probe.
        return new CraftPlan<>(true, supplied.isEmpty(), ready.firings(), Map.copyOf(used),
                ready.usedReusableStock(), Map.copyOf(supplied), ready.grossDemand(),
                ready.itemsProcessed(), best.budgetExhausted());
    }
}
