package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Reduces a bounded terminal inventory problem before using the existing small recovery search.
 * A route with no greater inputs and no smaller output can replace a dominated route; stopping
 * at the first satisfied target preserves a feasible witness without increasing its firings.
 * The reduced graph is searched again because directly replacing counts can overproduce batches
 * unsupported by primary demand. Every result still needs the original graph's certificate.
 */
final class TerminalBatchRecovery {
    private static final int MIN_ROUTES = 17;
    private static final int MAX_ROUTES = 64;
    private static final int MAX_RESOURCES = 4;
    private static final BigInteger MAX_AMOUNT = BigInteger.valueOf(SmallConservativeSearch.MAX_STOCK);

    private TerminalBatchRecovery() {}

    /** A cheap candidate filter only; tryPlan proves the complete terminal-only scope again. */
    static <K> boolean hasBoundedFootprint(CraftGraph<K> graph, K target, PlanningDiagnostics diagnostics) {
        // Diagnostics count registrations, so only the charged identity scan can enforce 64.
        // Count target registrations too: upstream duplicates do not make a wide terminal.
        return graph.patternsFor(target).size() >= MIN_ROUTES
                && diagnostics.reachablePatterns() >= MIN_ROUTES
                && diagnostics.reachableItems() >= 2 && diagnostics.reachableItems() <= MAX_RESOURCES + 1;
    }

    /** Null means uncertified or outside this portfolio, never a proof of infeasibility. */
    static <K> CraftPlan<K> tryPlan(CraftGraph<K> graph, K target, long amount, int stateLimit,
            IntPredicate spendWork) {
        PlanningCancellation.check();
        if (amount <= 0 || amount > SmallConservativeSearch.MAX_STOCK || stateLimit <= 0) return null;
        if (graph.patternsFor(target).size() < MIN_ROUTES) return null;
        var budget = new Budget(spendWork);
        var resources = new LinkedHashMap<K, Integer>();
        var seen = new IdentityHashMap<CraftPattern<K>, Boolean>();
        var routes = new ArrayList<Route<K>>();
        for (var pattern : graph.patternsFor(target)) {
            if (!budget.spend(1)) return null;
            if (seen.put(pattern, Boolean.TRUE) != null) continue;
            if (routes.size() >= MAX_ROUTES || !target.equals(pattern.output())
                    || pattern.inputs().isEmpty() || !pattern.byproducts().isEmpty()
                    || !pattern.executionSlots().isEmpty() || !bounded(pattern.exactOutputAmount())) return null;
            if (!budget.spend(1L + pattern.inputs().size())) return null;
            int[] inputs = new int[MAX_RESOURCES];
            int totalInputs = 0;
            for (var input : pattern.inputs()) {
                PlanningCancellation.check();
                if (input.returned() || input.remainder() != null || input.reusableStockSource() != null
                        || target.equals(input.key()) || !graph.patternsFor(input.key()).isEmpty()
                        || !bounded(input.exactAmount())
                        || !input.exactAmount().equals(BigInteger.valueOf(input.amount()))) return null;
                Integer key = resources.get(input.key());
                if (key == null) {
                    if (resources.size() >= MAX_RESOURCES) return null;
                    key = resources.size();
                    resources.put(input.key(), key);
                }
                int value = input.exactAmount().intValueExact();
                totalInputs += value;
                if (totalInputs > SmallConservativeSearch.MAX_STOCK) return null;
                inputs[key] += value;
            }
            routes.add(new Route<>(pattern, pattern.exactOutputAmount().intValueExact(), inputs));
        }
        if (routes.size() < MIN_ROUTES || resources.isEmpty()) return null;

        // Validate the original resource domain before pruning, including keys a deleted route
        // alone consumed. Otherwise projection could silently bypass the small stock bound.
        int totalStock = 0;
        for (K key : resources.keySet()) {
            if (!budget.spend(1)) return null;
            var stock = graph.exactStock(key);
            if (stock.signum() < 0 || stock.compareTo(MAX_AMOUNT) > 0) return null;
            totalStock += stock.intValueExact();
            if (totalStock > SmallConservativeSearch.MAX_STOCK) return null;
        }
        if (!budget.spend(1)) return null;
        var targetStock = graph.exactStock(target);
        if (targetStock.signum() < 0
                || targetStock.compareTo(BigInteger.valueOf(SmallConservativeSearch.MAX_STOCK - totalStock)) > 0)
            return null;

        var ordered = orderByOutput(routes, budget);
        if (ordered == null) return null;
        var frontier = new ArrayList<Route<K>>();
        for (var route : ordered) {
            boolean dominated = false;
            for (var kept : frontier) {
                if (noMoreInputs(kept, route, resources.size(), budget)) {
                    dominated = true;
                    break;
                }
                if (budget.exhausted) return null;
            }
            if (dominated) continue;
            // Earlier outputs are at least as large. Only ties can now be displaced; equal
            // input vectors keep the first original identity because the loop above wins first.
            for (int i = frontier.size() - 1; i >= 0; i--) {
                if (!budget.spend(1)) return null;
                var kept = frontier.get(i);
                if (kept.output() == route.output()
                        && noMoreInputs(route, kept, resources.size(), budget)) {
                    if (!budget.spend(frontier.size() - i)) return null;
                    frontier.remove(i);
                }
                if (budget.exhausted) return null;
            }
            if (!budget.spend(1)) return null;
            frontier.add(route);
        }
        // Dropping an arbitrary tail would no longer preserve a feasible witness.
        if (frontier.size() > SmallConservativeSearch.MAX_PATTERNS) return null;
        if (!budget.spend(frontier.size())) return null;
        var retained = new IdentityHashMap<CraftPattern<K>, Boolean>();
        for (var route : frontier) retained.put(route.pattern(), Boolean.TRUE);
        var selected = new ArrayList<CraftPattern<K>>();
        long inputSlots = 0;
        for (var route : routes) {
            if (!budget.spend(1)) return null;
            if (!retained.containsKey(route.pattern())) continue;
            if (!budget.spend(1)) return null;
            selected.add(route.pattern());
            inputSlots += route.pattern().inputs().size();
        }
        // Cover projection copies and the small search's bounded model construction as well
        // as its existing per-state charges. The projected graph keeps the full stock snapshot.
        long setupWork = 1L + 5L * selected.size() + 2L * inputSlots + resources.size();
        if (!budget.spend(setupWork)) return null;
        var projection = graph.withPatterns(Map.of(target, selected));
        var proposal = SmallConservativeSearch.tryPlan(projection, target, amount,
                Math.min(stateLimit, SmallConservativeSearch.MAX_STATES), () -> budget.spend(1));
        if (proposal == null || budget.exhausted) return null;
        long replayWork = 1L + proposal.firings().size();
        for (var pattern : proposal.firings().keySet()) replayWork += 2L * pattern.inputs().size();
        if (!budget.spend(replayWork)) return null;
        // Return the original graph's certificate, including normalized target-stock use.
        return MaterialDagReplay.tryPlan(graph, proposal.firings(), target, amount);
    }

    private static boolean bounded(BigInteger amount) {
        return amount.signum() > 0 && amount.compareTo(MAX_AMOUNT) <= 0;
    }

    /** Each resource comparison spends work; output dominance follows from the sorted order. */
    private static <K> boolean noMoreInputs(Route<K> first, Route<K> second, int resources, Budget budget) {
        for (int i = 0; i < resources; i++) {
            if (!budget.spend(1)) return false;
            if (first.inputs()[i] > second.inputs()[i]) return false;
        }
        return true;
    }

    /** Stable mergesort with every comparison and copied reference charged explicitly. */
    private static <K> List<Route<K>> orderByOutput(List<Route<K>> routes, Budget budget) {
        int size = routes.size();
        if (!budget.spend(2L * size)) return null;
        var from = new ArrayList<>(routes);
        var to = new ArrayList<Route<K>>(Collections.nCopies(size, null));
        for (int width = 1; width < size; width *= 2) {
            for (int start = 0; start < size; start += 2 * width) {
                int middle = Math.min(start + width, size), end = Math.min(start + 2 * width, size);
                int left = start, right = middle, next = start;
                while (left < middle && right < end) {
                    if (!budget.spend(2)) return null;
                    if (from.get(left).output() >= from.get(right).output()) to.set(next++, from.get(left++));
                    else to.set(next++, from.get(right++));
                }
                while (left < middle) {
                    if (!budget.spend(1)) return null;
                    to.set(next++, from.get(left++));
                }
                while (right < end) {
                    if (!budget.spend(1)) return null;
                    to.set(next++, from.get(right++));
                }
            }
            var swap = from;
            from = to;
            to = swap;
        }
        return from;
    }

    private record Route<K>(CraftPattern<K> pattern, int output, int[] inputs) {}

    private static final class Budget {
        private final IntPredicate spendWork;
        boolean exhausted;

        Budget(IntPredicate spendWork) {
            this.spendWork = spendWork;
        }

        boolean spend(long work) {
            PlanningCancellation.check();
            if (exhausted || !spendWork.test((int) Math.min(Integer.MAX_VALUE, Math.max(1L, work)))) {
                exhausted = true;
                return false;
            }
            return true;
        }
    }
}
