package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/** Exact bounded count search for small ordinary DAGs with shared intermediate batches. */
final class SmallDagBatchOptimizer {
    private static final int MAX_PATTERNS = 12;
    private static final long MAX_EXECUTIONS = 24;
    private static final int MAX_NODES = 4_096;

    private SmallDagBatchOptimizer() {
    }

    static <K> CraftPlan<K> solve(CraftGraph<K> graph, K target, long amount,
            CraftPlan<K> initial, IntPredicate spendWork) {
        if (!initial.feasible() || !initial.missing().isEmpty() || amount <= 0
                || amount >= Sat.SAT || !initial.usedReusableStock().isEmpty()) return null;
        BigInteger executionCount = initial.executionCount();
        if (executionCount.compareTo(BigInteger.ONE) <= 0
                || executionCount.compareTo(BigInteger.valueOf(MAX_EXECUTIONS)) > 0)
            return null;

        List<CraftPattern<K>> patterns = reachablePatterns(graph, target);
        if (patterns == null || patterns.size() > MAX_PATTERNS) return null;
        for (CraftPattern<K> pattern : patterns) {
            PlanningCancellation.check();
            if (pattern.executionCost() != 1 || !OrdinaryBatchOptimizer.ordinary(pattern)) return null;
        }
        long incumbentExecutions = executionCount.longValueExact();
        long lower = lowerTargetBound(graph.patternsFor(target), amount);
        if (lower >= incumbentExecutions) return null;

        patterns.sort(Comparator
                .<CraftPattern<K>>comparingInt(pattern -> pattern.output().equals(target) ? 0 : 1)
                .thenComparing((left, right) -> right.toString().compareTo(left.toString())));
        var search = new Search<>(graph, target, amount, initial, patterns, spendWork);
        for (long total = lower; total < incumbentExecutions && !search.exhausted; total++) {
            search.total = total;
            search.search(0, total, new IdentityHashMap<>(), 0L);
            if (search.result != null) return search.result;
        }
        return null;
    }

    private static <K> List<CraftPattern<K>> reachablePatterns(CraftGraph<K> graph, K target) {
        var result = new ArrayList<CraftPattern<K>>();
        var seenPatterns = new IdentityHashMap<CraftPattern<K>, Boolean>();
        var seenItems = new LinkedHashSet<K>();
        Deque<K> pending = new ArrayDeque<>();
        pending.add(target);
        seenItems.add(target);
        while (!pending.isEmpty()) {
            PlanningCancellation.check();
            K key = pending.removeFirst();
            for (CraftPattern<K> pattern : graph.patternsFor(key)) {
                if (seenPatterns.put(pattern, Boolean.TRUE) != null) continue;
                result.add(pattern);
                for (var input : pattern.inputs()) if (seenItems.add(input.key())) pending.addLast(input.key());
            }
            if (result.size() > MAX_PATTERNS) return null;
        }
        return result;
    }

    private static <K> long lowerTargetBound(List<CraftPattern<K>> routes, long amount) {
        long largest = 0L;
        for (var route : routes) largest = Math.max(largest, route.outputAmount());
        return largest <= 0L ? Long.MAX_VALUE : Sat.ceilDiv(amount, largest);
    }

    private static final class Search<K> {
        private final CraftGraph<K> graph;
        private final K target;
        private final long amount;
        private final CraftPlan<K> initial;
        private final List<CraftPattern<K>> patterns;
        private final IntPredicate spendWork;
        private int nodes;
        private boolean exhausted;
        private long total;
        private CraftPlan<K> result;

        Search(CraftGraph<K> graph, K target, long amount, CraftPlan<K> initial,
                List<CraftPattern<K>> patterns, IntPredicate spendWork) {
            this.graph = graph;
            this.target = target;
            this.amount = amount;
            this.initial = initial;
            this.patterns = patterns;
            this.spendWork = spendWork;
        }

        void search(int index, long remaining, IdentityHashMap<CraftPattern<K>, Long> counts,
                long targetProduced) {
            PlanningCancellation.check();
            if (result != null || exhausted || ++nodes > MAX_NODES || !spendWork.test(1)) {
                exhausted = true;
                return;
            }
            if (index == patterns.size()) {
                if (remaining != 0L || targetProduced < amount) return;
                var candidate = MaterialDagReplay.tryPlan(graph, counts, target, amount);
                if (candidate != null && FeasibleConsumptionOptimizer.improves(initial, candidate))
                    result = candidate;
                return;
            }

            long maxTarget = targetProduced;
            for (int i = index; i < patterns.size(); i++) {
                var pattern = patterns.get(i);
                if (pattern.output().equals(target)) {
                    maxTarget = Sat.add(maxTarget, Sat.mul(remaining, pattern.outputAmount()));
                }
            }
            if (maxTarget < amount) return;

            if (!partialBalanceCanRecover(index, remaining, counts)) return;

            CraftPattern<K> pattern = patterns.get(index);
            long output = pattern.output().equals(target) ? pattern.outputAmount() : 0L;
            long first = output == 0L ? 0L
                    : Math.max(0L, Sat.ceilDiv(Math.max(0L, amount - targetProduced), output));
            for (long count = first; count <= remaining; count++) {
                PlanningCancellation.check();
                if (count > 0L) counts.put(pattern, count);
                else counts.remove(pattern);
                search(index + 1, remaining - count, counts,
                        Sat.add(targetProduced, Sat.mul(count, output)));
                if (result != null || exhausted) return;
            }
            counts.remove(pattern);
        }

        private boolean partialBalanceCanRecover(int index, long remaining,
                IdentityHashMap<CraftPattern<K>, Long> counts) {
            var balance = new HashMap<K, BigInteger>();
            for (var entry : counts.entrySet()) {
                var pattern = entry.getKey();
                var times = BigInteger.valueOf(entry.getValue());
                balance.merge(pattern.output(), pattern.exactOutputAmount().multiply(times), BigInteger::add);
                for (var input : pattern.inputs()) {
                    balance.merge(input.key(), input.exactAmount().multiply(times).negate(), BigInteger::add);
                }
                for (var byproduct : pattern.byproducts()) {
                    balance.merge(byproduct.key(), byproduct.exactAmount().multiply(times), BigInteger::add);
                }
            }
            for (var entry : balance.entrySet()) {
                var available = BigInteger.valueOf(graph.stock(entry.getKey())).add(entry.getValue());
                if (available.signum() >= 0) continue;
                BigInteger future = BigInteger.ZERO;
                for (int i = index; i < patterns.size(); i++) {
                    var pattern = patterns.get(i);
                    if (pattern.output().equals(entry.getKey()))
                        future = future.max(pattern.exactOutputAmount());
                    for (var byproduct : pattern.byproducts()) {
                        if (byproduct.key().equals(entry.getKey()))
                            future = future.max(byproduct.exactAmount());
                    }
                }
                if (future.signum() == 0
                        || future.multiply(BigInteger.valueOf(remaining))
                                .compareTo(available.negate()) < 0) return false;
            }
            return true;
        }
    }
}
