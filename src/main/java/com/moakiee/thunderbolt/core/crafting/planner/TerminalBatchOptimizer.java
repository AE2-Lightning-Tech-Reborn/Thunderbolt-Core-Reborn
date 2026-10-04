package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Bounded batch proposals for one ordinary output made entirely from terminal inventory. */
final class TerminalBatchOptimizer {
    private static final int MIN_ROUTES = 17;
    private static final int MAX_ROUTES = 64;
    private static final int MAX_RESOURCES = 4;
    private static final int ROUTES_PER_CRITERION = 4;
    private static final int MAX_PAIRS = 28;
    private static final BigInteger MAX_AMOUNT = BigInteger.valueOf(Sat.SAT - 1);

    private TerminalBatchOptimizer() {}

    static boolean acceptsRouteCount(int count) {
        return count >= MIN_ROUTES && count <= MAX_ROUTES;
    }

    /**
     * Run after the established searches, using their remaining work and replay allowance.
     * Scan every single route, then mix the highest-output and most resource-efficient few.
     * These are proposals only: a complete replay and the shared objective accept the result.
     * The supplied list is the caller's reachable pattern index, in stable graph order.
     */
    static <K> CraftPlan<K> tryImprove(CraftGraph<K> graph, K target, long amount, CraftPlan<K> incumbent,
            List<CraftPattern<K>> patterns, IntPredicate spendWork, BooleanSupplier reserveProbe) {
        PlanningCancellation.check();
        if (!incumbent.feasible() || !incumbent.usedReusableStock().isEmpty()
                || amount <= 0 || amount >= Sat.SAT || patterns.size() < MIN_ROUTES) return null;
        long targetStock = graph.stock(target);
        if (targetStock >= amount) return null;
        long requested = amount - targetStock;
        var budget = new Budget(spendWork);
        long originalExecutions = 0;
        for (var entry : incumbent.firings().entrySet()) {
            if (!budget.spend(1) || !entry.getKey().output().equals(target)
                    || entry.getKey().executionCost() != 1) return null;
            long count = entry.getValue();
            if (count <= 0 || count >= Sat.SAT || originalExecutions >= Sat.SAT - count) return null;
            originalExecutions += count;
        }
        if (originalExecutions <= 1) return null;

        var resources = new LinkedHashSet<K>();
        var inputs = new IdentityHashMap<CraftPattern<K>, Map<K, Long>>();
        var routes = new ArrayList<CraftPattern<K>>();
        long largestOutput = 0;
        for (var pattern : patterns) {
            if (!budget.spend(1)) return null;
            // Repeated registrations are one variable; distinct fuzzy expansions stay distinct.
            if (inputs.containsKey(pattern)) continue;
            if (routes.size() >= MAX_ROUTES || !budget.spend(pattern.inputs().size())
                    || !target.equals(pattern.output()) || pattern.inputs().isEmpty()
                    || pattern.executionCost() != 1
                    || !pattern.executionSlots().isEmpty() || !OrdinaryBatchOptimizer.ordinary(pattern)) return null;
            var consumed = new LinkedHashMap<K, Long>();
            for (var input : pattern.inputs()) {
                PlanningCancellation.check();
                if (target.equals(input.key()) || !graph.patternsFor(input.key()).isEmpty()) return null;
                // The exact amount is authoritative even when its legacy long view differs.
                if (input.exactAmount().compareTo(MAX_AMOUNT) > 0) return null;
                long value = input.exactAmount().longValueExact();
                long previous = consumed.getOrDefault(input.key(), 0L);
                if (previous >= Sat.SAT - value) return null;
                consumed.put(input.key(), previous + value);
                resources.add(input.key());
                if (resources.size() > MAX_RESOURCES) return null;
            }
            inputs.put(pattern, consumed);
            routes.add(pattern);
            largestOutput = Math.max(largestOutput, pattern.outputAmount());
        }
        if (!acceptsRouteCount(routes.size()) || resources.isEmpty()) return null;
        long lowerBound = Sat.ceilDiv(requested, largestOutput);
        if (originalExecutions <= lowerBound) return null;
        for (var pattern : incumbent.firings().keySet()) {
            if (!budget.spend(1) || !inputs.containsKey(pattern)) return null;
        }
        long[] capacity = new long[resources.size()];
        int resourceIndex = 0;
        for (K resource : resources) {
            if (!budget.spend(1)) return null;
            long stock = graph.stock(resource);
            if (stock <= 0 || stock >= Sat.SAT) return null;
            capacity[resourceIndex++] = stock;
        }

        var candidates = new ArrayList<Route<K>>(routes.size());
        IdentityHashMap<CraftPattern<K>, Long> best = null;
        long bestExecutions = originalExecutions;
        for (var pattern : routes) {
            if (!budget.spend(1L + resources.size())) return null;
            long[] consumed = new long[resources.size()];
            int k = 0;
            double resourceCost = 0;
            for (K resource : resources) {
                consumed[k] = inputs.get(pattern).getOrDefault(resource, 0L);
                resourceCost += (double) consumed[k] / capacity[k];
                k++;
            }
            candidates.add(new Route<>(pattern, consumed, resourceCost / pattern.outputAmount()));
            long count = Sat.ceilDiv(requested, pattern.outputAmount());
            boolean fits = count < bestExecutions;
            for (k = 0; k < consumed.length && fits; k++)
                fits = consumed[k] == 0 || count <= capacity[k] / consumed[k];
            if (fits) {
                best = new IdentityHashMap<>();
                best.put(pattern, count);
                bestExecutions = count;
            }
        }

        if (bestExecutions > lowerBound) {
            var highestOutput = new ArrayList<Route<K>>(ROUTES_PER_CRITERION);
            var lowestConsumption = new ArrayList<Route<K>>(ROUTES_PER_CRITERION);
            Comparator<Route<K>> byOutput = (a, b) -> Long.compare(b.pattern().outputAmount(), a.pattern().outputAmount());
            Comparator<Route<K>> byConsumption = Comparator.comparingDouble(Route::resourceCost);
            for (var route : candidates) {
                if (!insert(highestOutput, route, byOutput, budget)
                        || !insert(lowestConsumption, route, byConsumption, budget)) return null;
            }
            var shortlist = new ArrayList<>(highestOutput);
            for (var route : lowestConsumption) {
                if (!budget.spend(1L + highestOutput.size())) return null;
                if (!highestOutput.contains(route)) shortlist.add(route);
            }
            int pairs = 0;
            for (int a = 0; a < shortlist.size() && bestExecutions > lowerBound; a++) {
                for (int b = a + 1; b < shortlist.size() && bestExecutions > lowerBound; b++) {
                    if (pairs++ >= MAX_PAIRS || !budget.spend(1L + 2L * resources.size())) return null;
                    var first = shortlist.get(a);
                    var second = shortlist.get(b);
                    var allocation = PairBatchAllocation.solve(requested,
                            first.pattern().outputAmount(), second.pattern().outputAmount(),
                            first.inputs(), second.inputs(), capacity, bestExecutions - 1,
                            () -> budget.spend(1L + capacity.length));
                    // The pair solver may return a partial best when its callback is denied.
                    if (budget.exhausted) return null;
                    if (allocation == null) continue;
                    best = new IdentityHashMap<>();
                    if (allocation.first() > 0) best.put(first.pattern(), allocation.first());
                    if (allocation.second() > 0) best.put(second.pattern(), allocation.second());
                    bestExecutions = allocation.executions();
                }
            }
        }
        if (best == null) return null;
        long replayWork = best.size();
        for (var pattern : best.keySet()) replayWork += 2L * pattern.inputs().size();
        if (!budget.spend(replayWork) || !reserveProbe.getAsBoolean()) return null;
        var plan = MaterialDagReplay.tryPlan(graph, best, target, amount);
        return plan != null && FeasibleConsumptionOptimizer.improves(incumbent, plan) ? plan : null;
    }

    /** Stable insertion keeps ties in graph order; every comparison spends shared work. */
    private static <K> boolean insert(List<Route<K>> routes, Route<K> candidate,
            Comparator<Route<K>> comparator, Budget budget) {
        int position = 0;
        while (position < routes.size()) {
            if (!budget.spend(1)) return false;
            if (comparator.compare(candidate, routes.get(position)) < 0) break;
            position++;
        }
        if (position < ROUTES_PER_CRITERION) {
            routes.add(position, candidate);
            if (routes.size() > ROUTES_PER_CRITERION) routes.remove(ROUTES_PER_CRITERION);
        }
        return true;
    }

    private record Route<K>(CraftPattern<K> pattern, long[] inputs, double resourceCost) {}

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
