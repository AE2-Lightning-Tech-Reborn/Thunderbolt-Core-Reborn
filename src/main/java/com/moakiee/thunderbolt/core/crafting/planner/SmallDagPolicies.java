package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete, small family of fixed ordinary DAG policies. Unlike optional search, the family is
 * independent of stock, requested quantity, and previously spent search work. Thus a supplement
 * certified by one member remains sufficient on a later calculation with that supplement present.
 * This is structural enumeration, capped before construction; craft counts are never enumerated.
 */
final class SmallDagPolicies {
    static final int MAX_CHOICES = 256;
    private SmallDagPolicies() {}

    static <K> List<ConservativeReplenishment<K>> compile(CraftGraph<K> graph, K target) {
        var cycles = CycleAnalysis.analyze(graph, target);
        var cyclic = cycles.ordinaryCycleMembers(5);
        if (cyclic.isEmpty()) return List.of();
        var choices = new LinkedHashMap<K, List<CraftPattern<K>>>();
        var queue = new ArrayDeque<K>();
        choices.put(target, ordinaryRoutes(graph, target)); queue.add(target);
        int combinations = 1;
        while (!queue.isEmpty()) {
            PlanningCancellation.check();
            K key = queue.removeFirst();
            var routes = choices.get(key);
            int count = routes.size() + (cyclic.contains(key) || graph.patternsFor(key).isEmpty() ? 1 : 0);
            if (count == 0 || count > MAX_CHOICES / combinations) return List.of();
            combinations *= count;
            for (var route : routes) {
                for (var input : route.inputs()) {
                    if (input.returned() || input.remainder() != null || input.reusableStockSource() != null)
                        return List.of();
                    if (!choices.containsKey(input.key())) {
                        choices.put(input.key(), ordinaryRoutes(graph, input.key())); queue.addLast(input.key());
                    }
                }
            }
        }
        var keys = List.copyOf(choices.keySet());
        var result = new ArrayList<ConservativeReplenishment<K>>();
        var seen = new HashSet<List<CraftPattern<K>>>();
        for (int combination = 0; combination < combinations; combination++) {
            PlanningCancellation.check();
            int cursor = combination;
            var selected = new LinkedHashMap<K, List<CraftPattern<K>>>();
            for (K key : keys) {
                var routes = choices.get(key);
                int count = routes.size() + (cyclic.contains(key) || graph.patternsFor(key).isEmpty() ? 1 : 0);
                int choice = cursor % count; cursor /= count;
                if (choice == routes.size()) continue; // original leaf or explicit cyclic boundary
                var route = routes.get(choice);
                selected.put(key, List.of(route));
            }
            // Unused choices do not change a policy and cannot contribute a physical cycle.
            var reachable = new LinkedHashSet<K>();
            reachable.add(target); queue.add(target);
            while (!queue.isEmpty()) {
                K key = queue.removeFirst();
                for (var route : selected.getOrDefault(key, List.of())) {
                    for (var input : route.inputs()) if (reachable.add(input.key())) queue.addLast(input.key());
                }
            }
            selected.keySet().retainAll(reachable);
            var signature = selected.values().stream().map(routes -> routes.get(0)).toList();
            if (!seen.add(signature)) continue;
            var indegree = new HashMap<K, Integer>();
            for (K key : reachable) indegree.put(key, 0);
            for (var routes : selected.values()) {
                for (var input : routes.get(0).inputs()) indegree.merge(input.key(), 1, Integer::sum);
            }
            var ready = new ArrayDeque<K>();
            for (K key : reachable) if (indegree.get(key) == 0) ready.addLast(key);
            var order = new ArrayList<K>();
            while (!ready.isEmpty()) {
                K key = ready.removeFirst(); order.add(key);
                for (var route : selected.getOrDefault(key, List.of())) {
                    for (var input : route.inputs()) {
                        if (indegree.merge(input.key(), -1, Integer::sum) == 0) ready.addLast(input.key());
                    }
                }
            }
            if (order.size() != reachable.size()) continue; // includes a cycle: never export this policy
            var policy = ConservativeReplenishment.compileFixed(graph, order, selected);
            if (policy != null) result.add(policy);
        }
        return List.copyOf(result);
    }
    private static <K> List<CraftPattern<K>> ordinaryRoutes(CraftGraph<K> graph, K key) {
        // An unused byproduct alternative must not disable an otherwise ordinary policy. We keep
        // only recipes whose production cannot disappear when more inventory is supplied.
        return graph.patternsFor(key).stream().filter(route -> route.byproducts().isEmpty()).toList();
    }

}
