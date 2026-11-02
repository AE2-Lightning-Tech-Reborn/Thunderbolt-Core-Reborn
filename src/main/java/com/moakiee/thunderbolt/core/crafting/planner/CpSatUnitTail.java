package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Eliminate an isolated, forced unit-conversion tail before compiling the ranked core. */
final class CpSatUnitTail {
    private CpSatUnitTail() { }

    record Reduction<K>(K target, long amount, Map<CraftPattern<K>, Long> firings,
                        Map<K, Long> usedStock, Map<K, Long> grossDemand, int itemsProcessed) {
        CraftPlan<K> expand(CraftPlan<K> core) {
            var fired = new IdentityHashMap<CraftPattern<K>, Long>(core.firings());
            fired.putAll(firings);
            var used = new LinkedHashMap<K, Long>(core.usedStock());
            used.putAll(usedStock);
            var gross = new LinkedHashMap<K, Long>(core.grossDemand());
            gross.putAll(grossDemand);
            return new CraftPlan<>(core.supported(), core.feasible(), Map.copyOf(fired), Map.copyOf(used),
                    core.usedReusableStock(), core.missing(), Map.copyOf(gross), itemsProcessed,
                    core.budgetExhausted());
        }
    }

    static <K> Reduction<K> prepare(CraftGraph<K> graph, K target, long amount, int minimumLength) {
        var tail = new HashMap<K, CraftPattern<K>>();
        var consumer = new HashMap<K, CraftPattern<K>>();
        var fired = new IdentityHashMap<CraftPattern<K>, Long>();
        var used = new LinkedHashMap<K, Long>();
        var gross = new LinkedHashMap<K, Long>();
        K current = target;
        long needed = amount;
        while (!tail.containsKey(current)) {
            PlanningCancellation.check();
            var routes = graph.patternsFor(current);
            if (routes.size() != 1) break;
            var pattern = routes.get(0);
            if (pattern.executionCost() != 1 || !pattern.exactOutputAmount().equals(BigInteger.ONE)
                    || !pattern.byproducts().isEmpty() || pattern.inputs().size() != 1) break;
            var input = pattern.inputs().get(0);
            if (input.returned() || input.remainder() != null || input.reusableStockSource() != null
                    || !input.exactAmount().equals(BigInteger.ONE)) break;
            tail.put(current, pattern);
            consumer.put(input.key(), pattern);
            if (needed > 0L) {
                gross.put(current, needed);
                long draw = Math.min(graph.stock(current), needed);
                if (draw > 0L) used.put(current, draw);
                needed -= draw;
                if (needed > 0L) fired.put(pattern, needed);
            }
            current = input.key();
        }
        if (tail.size() < minimumLength || tail.containsKey(current)
                || (needed > 0L && graph.patternsFor(current).isEmpty())) return null;

        // A core recipe must neither supply nor consume a tail item. Otherwise stock-first
        // elimination could steal a seed, discard useful side credit, or change the optimum.
        var seen = new HashSet<K>();
        var pending = new ArrayDeque<K>();
        seen.add(target);
        pending.add(target);
        while (!pending.isEmpty()) {
            PlanningCancellation.check();
            for (var pattern : graph.patternsFor(pending.removeFirst())) {
                PlanningCancellation.check();
                for (var side : pattern.byproducts()) {
                    PlanningCancellation.check();
                    if (tail.containsKey(side.key())) return null;
                }
                for (var input : pattern.inputs()) {
                    PlanningCancellation.check();
                    if (tail.containsKey(input.key()) && consumer.get(input.key()) != pattern) return null;
                    if (seen.add(input.key())) pending.addLast(input.key());
                }
            }
        }
        // Every removed firing and draw is forced. All remaining missing-distance tiers shift
        // by the same tail length, so the core retains the original lexicographic objective.
        return new Reduction<>(current, needed, fired, used, gross, seen.size());
    }
}
