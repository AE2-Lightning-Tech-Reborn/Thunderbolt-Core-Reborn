package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, quantity-independent ordinary material DAGs, including side-output dependencies. */
final class MaterialDagOrders {
    private static final int MAX_ORDERED_ITEMS = 6;
    static final int MAX_GRAPH_WORK = 128;

    record Candidate<K>(CraftGraph<K> graph, long optimisticCapacity,
                        Map<CraftPattern<K>, CraftPattern<K>> originals) {
        boolean maySupply(long amount) {
            return Sat.isSaturated(optimisticCapacity) || optimisticCapacity >= amount;
        }

        CraftPlan<K> restore(CraftPlan<K> plan) {
            if (originals.isEmpty()) return plan;
            var firings = new IdentityHashMap<CraftPattern<K>, Long>();
            plan.firings().forEach((pattern, times) ->
                    firings.put(originals.getOrDefault(pattern, pattern), times));
            return new CraftPlan<>(plan.supported(), plan.feasible(), Map.copyOf(firings),
                    plan.usedStock(), plan.usedReusableStock(), plan.missing(), plan.grossDemand(),
                    plan.itemsProcessed(), plan.budgetExhausted());
        }
    }

    private MaterialDagOrders() {}

    static <K> List<Candidate<K>> compile(CraftGraph<K> graph, K target) {
        return compile(graph, target, BoundedIntegerLinearSolver.WorkBudget.unlimited());
    }

    static <K> List<Candidate<K>> compile(CraftGraph<K> graph, K target,
            BoundedIntegerLinearSolver.WorkBudget budget) {
        var result = new ArrayList<Candidate<K>>();
        try {
            result.addAll(compile(graph, target, 0, budget));
            result.addAll(compile(graph, target, 2, budget));
            result.addAll(compile(graph, target, 1, budget));
        } catch (WorkLimit exhausted) {
            // Already completed projection families remain valid; unfinished ones are discarded.
        }
        return List.copyOf(result);
    }

    private static <K> List<Candidate<K>> compile(CraftGraph<K> graph, K target, int sidePolicy,
            BoundedIntegerLinearSolver.WorkBudget budget) {
        boolean ignoreSideOutputs = sidePolicy != 0;
        boolean retainedSelfReturn = false;
        var originals = new IdentityHashMap<CraftPattern<K>, CraftPattern<K>>();
        var reachable = new LinkedHashSet<K>();
        var queue = new ArrayDeque<K>();
        var patterns = new ArrayList<CraftPattern<K>>();
        var produced = new HashSet<K>();
        int work = 0;
        reachable.add(target);
        queue.add(target);
        while (!queue.isEmpty()) {
            charge(budget, 1);
            K key = queue.removeFirst();
            if (++work > MAX_GRAPH_WORK) return List.of();
            for (var pattern : graph.patternsFor(key)) {
                charge(budget, 1L+pattern.inputs().size()+pattern.byproducts().size());
                if (++work > MAX_GRAPH_WORK) return List.of();
                CraftPattern<K> projected = pattern;
                if (ignoreSideOutputs && !pattern.byproducts().isEmpty()) {
                    var inputs = sidePolicy == 2 ? conservativeInputs(pattern) : pattern.inputs();
                    retainedSelfReturn |= inputs.stream().anyMatch(CraftInput::returned);
                    projected = new CraftPattern<>(pattern.output(), pattern.exactOutputAmount(),
                            inputs, List.of(), pattern.source(), pattern.executionSlots());
                    originals.put(projected, pattern);
                }
                patterns.add(projected);
                produced.add(pattern.output());
                for (var input : pattern.inputs()) {
                    if (++work > MAX_GRAPH_WORK || input.returned() || input.remainder() != null
                            || input.reusableStockSource() != null || input.key().equals(target)) return List.of();
                    if (reachable.add(input.key())) queue.addLast(input.key());
                }
                for (var output : pattern.byproducts()) {
                    if (++work > MAX_GRAPH_WORK) return List.of();
                    if (!ignoreSideOutputs) produced.add(output.key());
                }
            }
        }
        if (ignoreSideOutputs && originals.isEmpty() || sidePolicy == 2 && !retainedSelfReturn)
            return List.of();
        var ordered = new ArrayList<K>();
        for (K key : reachable) {
            if (!key.equals(target) && produced.contains(key)) ordered.add(key);
        }
        if (ordered.size() > MAX_ORDERED_ITEMS) return List.of();
        var result = new ArrayList<Candidate<K>>();
        permute(graph, target, reachable, ordered, 0, patterns, new HashSet<>(), Map.copyOf(originals), result, budget);
        return List.copyOf(result);
    }

    /** A consumed return needs its retained seed plus net consumption, independently of amount.
     * Other side outputs are ignored. Restoring the real recipe and its final execution certificate
     * prevents this conservative abstraction from exporting the synthetic returned input. */
    private static <K> List<CraftInput<K>> conservativeInputs(CraftPattern<K> pattern) {
        var inputs = new LinkedHashMap<K, BigInteger>();
        var returned = new HashMap<K, BigInteger>();
        for (var input : pattern.inputs()) inputs.merge(input.key(), input.exactAmount(), BigInteger::add);
        for (var output : pattern.byproducts()) returned.merge(output.key(), output.exactAmount(), BigInteger::add);
        var result = new ArrayList<CraftInput<K>>();
        for (var entry : inputs.entrySet()) {
            BigInteger seed = entry.getValue().min(returned.getOrDefault(entry.getKey(), BigInteger.ZERO));
            BigInteger net = entry.getValue().subtract(seed);
            if (net.signum() > 0) result.add(exactInput(entry.getKey(), net, false));
            if (seed.signum() > 0) result.add(exactInput(entry.getKey(), seed, true));
        }
        return List.copyOf(result);
    }

    private static <K> CraftInput<K> exactInput(K key, BigInteger amount, boolean returned) {
        return new CraftInput<>(key, amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(),
                returned, CraftInput.INFINITE_USES, null, null, amount);
    }

    private static <K> void permute(CraftGraph<K> graph, K target, Set<K> reachable, List<K> ordered,
            int index, List<CraftPattern<K>> patterns, Set<BitSet> seen,
            Map<CraftPattern<K>, CraftPattern<K>> originals, List<Candidate<K>> result,
            BoundedIntegerLinearSolver.WorkBudget budget) {
        charge(budget, 1);
        if (index < ordered.size()) {
            for (int next = index; next < ordered.size(); next++) {
                java.util.Collections.swap(ordered, index, next);
                permute(graph, target, reachable, ordered, index + 1, patterns, seen, originals, result, budget);
                java.util.Collections.swap(ordered, index, next);
            }
            return;
        }
        var rank = new HashMap<K, Integer>();
        for (int i = 0; i < ordered.size(); i++) rank.put(ordered.get(i), i + 1);
        rank.put(target, ordered.size() + 1);
        var support = new BitSet(patterns.size());
        var selected = new LinkedHashMap<K, List<CraftPattern<K>>>();
        var supplyOrder = new ArrayList<CraftPattern<K>>();
        for (int i = 0; i < patterns.size(); i++) {
            var pattern = patterns.get(i);
            // Cover arc admission, projection construction, sorting, and its forward supply bound.
            charge(budget, 4L*(1L+pattern.inputs().size()+pattern.byproducts().size()));
            int earliestOutput = rank.get(pattern.output());
            for (var output : pattern.byproducts()) {
                if (reachable.contains(output.key()))
                    earliestOutput = Math.min(earliestOutput, rank.get(output.key()));
            }
            boolean admitted = true;
            for (var input : pattern.inputs()) {
                if (rank.getOrDefault(input.key(), 0) >= earliestOutput) {
                    admitted = false;
                    break;
                }
            }
            if (admitted) {
                support.set(i);
                selected.computeIfAbsent(pattern.output(), ignored -> new ArrayList<>()).add(pattern);
                supplyOrder.add(pattern);
            }
        }
        if (support.isEmpty() || !seen.add(support)) return;
        // All outputs lie after every input, so ordering producers by their last input is a
        // forward schedule even when a side output lies before that producer's primary output.
        supplyOrder.sort(Comparator.comparingInt(pattern -> lastInputRank(pattern, rank)));
        long capacity = optimisticCapacity(graph, target, supplyOrder);
        if (capacity > 0) result.add(new Candidate<>(graph.withPatterns(selected), capacity, originals));
    }

    private static void charge(BoundedIntegerLinearSolver.WorkBudget budget, long work) {
        PlanningCancellation.check();
        if (!budget.tryConsume(work)) throw new WorkLimit();
    }

    private static final class WorkLimit extends RuntimeException {}

    private static <K> int lastInputRank(CraftPattern<K> pattern, Map<K, Integer> rank) {
        int last = -1;
        for (var input : pattern.inputs()) last = Math.max(last, rank.getOrDefault(input.key(), 0));
        return last;
    }

    /**
     * Forward supply upper bound. Every route may spend the entire accumulated supply, even when
     * another route also spends it. That deliberate overestimate can only retain extra candidates;
     * it never rejects a feasible allocation. Primary-demand policy is checked by the real replay.
     */
    private static <K> long optimisticCapacity(CraftGraph<K> graph, K target,
            List<CraftPattern<K>> supplyOrder) {
        var supply = new HashMap<K, Long>();
        for (var pattern : supplyOrder) {
            PlanningCancellation.check();
            long times = Sat.SAT;
            for (var input : pattern.inputs()) {
                long available = supply.getOrDefault(input.key(), graph.stock(input.key()));
                // Saturation means unknown upper infinity, not a finite quantity to divide.
                long viaInput = Sat.isSaturated(available) ? Sat.SAT
                        : input.returned() ? (available >= input.amount() ? Sat.SAT : 0L)
                        : available / input.amount();
                times = Math.min(times, viaInput);
            }
            if (times == 0) continue;
            supply.put(pattern.output(), Sat.add(
                    supply.getOrDefault(pattern.output(), graph.stock(pattern.output())),
                    Sat.mul(times, pattern.outputAmount())));
            for (var output : pattern.byproducts()) {
                supply.put(output.key(), Sat.add(
                        supply.getOrDefault(output.key(), graph.stock(output.key())),
                        Sat.mul(times, output.amount())));
            }
        }
        return supply.getOrDefault(target, graph.stock(target));
    }
}
