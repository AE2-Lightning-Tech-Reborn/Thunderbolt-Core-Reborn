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
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Bounded, quantity-independent ordinary material DAGs, including side-output dependencies. */
final class MaterialDagOrders {
    private static final int MAX_ORDERED_ITEMS = 6;
    static final int MAX_GRAPH_WORK = 128;

    record Portfolio<K>(List<Candidate<K>> candidates, boolean complete) {}

    /** Capacities belong to this exact immutable stock snapshot and target. */
    record CertificateFamily<K>(CraftGraph<K> source, K target, Portfolio<K> portfolio) {
        boolean reusableFor(CraftGraph<K> graph, K requestedTarget) {
            return source == graph && java.util.Objects.equals(target, requestedTarget) && portfolio.complete();
        }
    }

    record Candidate<K>(CraftGraph<K> graph, long optimisticCapacity,
                        Map<CraftPattern<K>, CraftPattern<K>> originals,
                        List<CraftPattern<K>> supplyOrder) {
        boolean maySupply(long amount) {
            return Sat.isSaturated(optimisticCapacity) || optimisticCapacity >= amount;
        }

        /** Cheap monotone policy for ordinary projections; failure still leaves the full planner. */
        CraftPlan<K> tryFixedPlan(K target, long amount) {
            return tryFixedPlan(target, amount, BoundedIntegerLinearSolver.WorkBudget.unlimited());
        }

        CraftPlan<K> tryFixedPlan(K target, long amount, BoundedIntegerLinearSolver.WorkBudget budget) {
            var selected = new LinkedHashMap<K, List<CraftPattern<K>>>();
            var adjacency = new HashMap<K, LinkedHashSet<K>>();
            var keys = new LinkedHashSet<K>();
            keys.add(target);
            for (var pattern : supplyOrder) {
                PlanningCancellation.check();
                if (!pattern.byproducts().isEmpty()) return null;
                keys.add(pattern.output());
                selected.computeIfAbsent(pattern.output(), ignored -> new ArrayList<>()).add(pattern);
                for (var input : pattern.inputs()) {
                    if (input.returned() || input.remainder() != null || input.reusableStockSource() != null) return null;
                    keys.add(input.key());
                    adjacency.computeIfAbsent(pattern.output(), ignored -> new LinkedHashSet<>()).add(input.key());
                }
            }
            var stable = List.copyOf(keys);
            var order = PlannerTopology.stableTopologicalOrder(stable, PlannerTopology.freezeAdjacency(stable, adjacency));
            if (order.size() != keys.size()) return null;
            var policy = ConservativeReplenishment.compileFixed(graph, order, selected);
            var plan = policy == null ? null : policy.planBatchAware(target, amount, budget);
            if (plan != null && !plan.feasible()) plan = policy.plan(target, amount, Map.of());
            // Replay certifies the counts, but its minimal net draw need not equal the stable
            // policy's stock-first withdrawal. Preserve that policy's existing stock contract;
            // the general optimizer may normalize surplus after route search has completed.
            return plan != null && plan.feasible()
                    && MaterialDagReplay.tryPlan(graph, plan.firings(), target, amount) != null ? plan : null;
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

    /**
     * The small primary DAG family is a core certificate path, bounded structurally and by
     * shared cell work. Its existence must not depend on an optional optimization deadline.
     * It still inherits the caller's deadline/cancellation (including a replenishment probe's
     * optional parent). Real side outputs are restored and protected by the caller.
     */
    static <K> Portfolio<K> compileCertificates(CraftGraph<K> graph, K target,
            BoundedIntegerLinearSolver.WorkBudget budget, Predicate<Candidate<K>> stop,
            List<K> demandOrder) {
        var result = new ArrayList<Candidate<K>>();
        try {
            compile(graph, target, 3, budget, candidate -> {
                result.add(candidate);
                if (stop.test(candidate)) throw new SupportAccepted();
            }, demandOrder);
        } catch (SupportAccepted | WorkLimit | PlanningCancellation.OptionalWorkLimit exhausted) {
            return new Portfolio<>(List.copyOf(result), false);
        }
        return new Portfolio<>(List.copyOf(result), true);
    }

    static <K> CertificateFamily<K> compileCertificateFamily(CraftGraph<K> graph, K target,
            BoundedIntegerLinearSolver.WorkBudget budget, Predicate<Candidate<K>> stop,
            List<K> demandOrder) {
        return new CertificateFamily<>(graph, target, compileCertificates(graph, target, budget, stop, demandOrder));
    }

    /** Replenishment certificates share their parent's remaining optional deadline. */
    static PlanningCancellation.Scope limitWork(long spentNanos, boolean replenishmentProbe) {
        long available = PlanningCancellation.remainingNanos(Long.MAX_VALUE);
        long remaining = Math.min(100_000_000L - spentNanos,
                replenishmentProbe ? available : available / 4L);
        return remaining <= 0L ? null : PlanningCancellation.limitOptionalWork(remaining);
    }

    static <K> List<Candidate<K>> compile(CraftGraph<K> graph, K target) {
        return compile(graph, target, BoundedIntegerLinearSolver.WorkBudget.unlimited());
    }

    static <K> List<Candidate<K>> compile(CraftGraph<K> graph, K target,
            BoundedIntegerLinearSolver.WorkBudget budget) {
        return compile(graph, target, budget, candidate -> false);
    }

    /** Try completed supports before spending the remaining deadline on more permutations. */
    static <K> List<Candidate<K>> compile(CraftGraph<K> graph, K target,
            BoundedIntegerLinearSolver.WorkBudget budget, Predicate<Candidate<K>> stop) {
        return compile(graph, target, budget, stop, List.of());
    }

    static <K> List<Candidate<K>> compile(CraftGraph<K> graph, K target,
            BoundedIntegerLinearSolver.WorkBudget budget, Predicate<Candidate<K>> stop, List<K> demandOrder) {
        return compilePortfolio(graph, target, budget, stop, demandOrder).candidates();
    }

    static <K> Portfolio<K> compilePortfolio(CraftGraph<K> graph, K target,
            BoundedIntegerLinearSolver.WorkBudget budget, Predicate<Candidate<K>> stop, List<K> demandOrder) {
        return compilePortfolio(graph, target, budget, stop, demandOrder, candidates -> false);
    }

    static <K> Portfolio<K> compilePortfolio(CraftGraph<K> graph, K target,
            BoundedIntegerLinearSolver.WorkBudget budget, Predicate<Candidate<K>> stop, List<K> demandOrder,
            Predicate<List<Candidate<K>>> familyComplete) {
        return compilePortfolio(graph, target, budget, stop, demandOrder, familyComplete, null);
    }

    static <K> Portfolio<K> compilePortfolio(CraftGraph<K> graph, K target,
            BoundedIntegerLinearSolver.WorkBudget budget, Predicate<Candidate<K>> stop, List<K> demandOrder,
            Predicate<List<Candidate<K>>> familyComplete, CertificateFamily<K> primary) {
        var result = new ArrayList<Candidate<K>>();
        Consumer<Candidate<K>> completed = candidate -> {
            result.add(candidate);
            if (stop.test(candidate)) throw new SupportAccepted();
        };
        try {
            // Certify a primary-only projection before spending the slice on feedback-rich
            // supports. Real recipe identities and their side outputs are restored and protected
            // by the caller; an ignored surplus never supplies material in this projection.
            boolean reusePrimary = primary != null && primary.reusableFor(graph, target);
            if (reusePrimary) {
                for (var candidate : primary.portfolio().candidates()) {
                    // Compilation has already paid for projection and capacity construction.
                    // Traversal and every new plan/probe still debit their shared work budgets.
                    charge(budget, 1L);
                    completed.accept(candidate);
                }
            } else {
                compile(graph, target, 1, budget, completed, demandOrder);
            }
            if (familyComplete.test(List.copyOf(result))) throw new SupportAccepted();
            // Without side outputs, the primary and full families are the same supports.
            // Feedback-rich supports remain a separate family and must still be compiled.
            if (!reusePrimary || graph.hasByproducts()) {
                compile(graph, target, 0, budget, completed, demandOrder);
                if (familyComplete.test(List.copyOf(result))) throw new SupportAccepted();
                compile(graph, target, 2, budget, completed, demandOrder);
                if (familyComplete.test(List.copyOf(result))) throw new SupportAccepted();
            }
        } catch (SupportAccepted | WorkLimit | PlanningCancellation.OptionalWorkLimit exhausted) {
            // Already completed supports remain valid even if the next family expires.
            return new Portfolio<>(List.copyOf(result), false);
        }
        return new Portfolio<>(List.copyOf(result), true);
    }

    /** Reuse only immutable arc admission/order; capacities and graph stock are probe-local. */
    static <K> List<Candidate<K>> withAdditionalStock(List<Candidate<K>> templates,
            Map<K, Long> supplied, K target, BoundedIntegerLinearSolver.WorkBudget budget) {
        return withAdditionalStock(templates, supplied, target, budget, candidate -> false);
    }

    static <K> List<Candidate<K>> withAdditionalStock(List<Candidate<K>> templates,
            Map<K, Long> supplied, K target, BoundedIntegerLinearSolver.WorkBudget budget,
            Predicate<Candidate<K>> stop) {
        return withAdditionalStock(templates, supplied, target, budget, stop, 0);
    }

    static <K> List<Candidate<K>> withAdditionalStock(List<Candidate<K>> templates,
            Map<K, Long> supplied, K target, BoundedIntegerLinearSolver.WorkBudget budget,
            Predicate<Candidate<K>> stop, int preferred) {
        return refreshPortfolio(templates, supplied, target, budget, stop, preferred).candidates();
    }

    static <K> Portfolio<K> refreshPortfolio(List<Candidate<K>> templates,
            Map<K, Long> supplied, K target, BoundedIntegerLinearSolver.WorkBudget budget,
            Predicate<Candidate<K>> stop, int preferred) {
        var result = new ArrayList<Candidate<K>>(templates.size());
        try {
            for (int offset = 0; offset < templates.size(); offset++) {
                var template = templates.get(Math.floorMod(preferred + offset, templates.size()));
                charge(budget, 1);
                for (var pattern : template.supplyOrder())
                    charge(budget, 4L * (1L + pattern.inputs().size() + pattern.byproducts().size()));
                var graph = template.graph().withAdditionalStock(supplied);
                var candidate = new Candidate<>(graph, optimisticCapacity(graph, target, template.supplyOrder()),
                        template.originals(), template.supplyOrder());
                result.add(candidate);
                if (stop.test(candidate)) return new Portfolio<>(List.copyOf(result), false);
            }
        } catch (WorkLimit | PlanningCancellation.OptionalWorkLimit exhausted) {
            // Only fully refreshed supports can be used after shared work exhaustion.
            return new Portfolio<>(List.copyOf(result), false);
        }
        return new Portfolio<>(List.copyOf(result), true);
    }

    private static <K> void compile(CraftGraph<K> graph, K target, int sidePolicy,
            BoundedIntegerLinearSolver.WorkBudget budget, Consumer<Candidate<K>> completed, List<K> demandOrder) {
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
            if (++work > MAX_GRAPH_WORK) return;
            for (var pattern : graph.patternsFor(key)) {
                charge(budget, 1L+pattern.inputs().size()+pattern.byproducts().size());
                if (++work > MAX_GRAPH_WORK) return;
                // Explicit tag conversions have no side outputs and retain their original
                // identity and zero execution cost through every projection family.
                CraftPattern<K> projected = pattern;
                if (ignoreSideOutputs && !pattern.byproducts().isEmpty()) {
                    var inputs = sidePolicy == 2 ? conservativeInputs(pattern) : pattern.inputs();
                    retainedSelfReturn |= inputs.stream().anyMatch(CraftInput::returned);
                    projected = pattern.projectMaterials(inputs, List.of());
                    originals.put(projected, pattern);
                }
                patterns.add(projected);
                produced.add(pattern.output());
                for (var input : pattern.inputs()) {
                    if (++work > MAX_GRAPH_WORK || input.returned() || input.remainder() != null
                            || input.reusableStockSource() != null || input.key().equals(target)) return;
                    if (reachable.add(input.key())) queue.addLast(input.key());
                }
                for (var output : pattern.byproducts()) {
                    if (++work > MAX_GRAPH_WORK) return;
                    if (!ignoreSideOutputs) produced.add(output.key());
                }
            }
        }
        if (ignoreSideOutputs && originals.isEmpty() && sidePolicy != 3 || sidePolicy == 2 && !retainedSelfReturn)
            return;
        var ordered = new ArrayList<K>();
        for (K key : reachable) {
            if (!key.equals(target) && produced.contains(key)) ordered.add(key);
        }
        if (ordered.size() > MAX_ORDERED_ITEMS) return;
        // The core has already computed a cut orientation. Try its reverse supply order first;
        // all other permutations remain available if that orientation cannot certify this stock.
        if (!demandOrder.isEmpty()) {
            charge(budget, demandOrder.size() + (long) ordered.size() * ordered.size());
            var positions = new HashMap<K, Integer>();
            for (int i = 0; i < demandOrder.size(); i++) positions.put(demandOrder.get(i), i);
            ordered.sort(Comparator.comparingInt(key -> -positions.getOrDefault(key, -1)));
        }

        var ids = new HashMap<K, Integer>();
        int[] permutation = new int[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            ids.put(ordered.get(i), i);
            permutation[i] = i;
        }
        ids.put(target, ordered.size());
        // At most six intermediates plus the target fit in these masks. Compile the immutable
        // slot relationships once; permutations change only the ranks, not arc membership.
        int[] inputMasks = new int[patterns.size()], outputMasks = new int[patterns.size()];
        for (int p = 0; p < patterns.size(); p++) {
            PlanningCancellation.check();
            var pattern = patterns.get(p);
            outputMasks[p] = 1 << ids.get(pattern.output());
            for (var input : pattern.inputs()) {
                Integer id = ids.get(input.key());
                if (id != null) inputMasks[p] |= 1 << id;
            }
            for (var output : pattern.byproducts()) {
                Integer id = ids.get(output.key());
                if (id != null) outputMasks[p] |= 1 << id;
            }
        }
        int[] ranks = new int[ordered.size() + 1];
        ranks[ordered.size()] = ordered.size() + 1;
        permute(graph, target, ordered, permutation, 0, patterns, new HashSet<>(),
                Map.copyOf(originals), completed, budget, ranks, inputMasks, outputMasks);
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

    private static <K> void permute(CraftGraph<K> graph, K target, List<K> ordered, int[] permutation,
            int index, List<CraftPattern<K>> patterns, Set<BitSet> seen,
            Map<CraftPattern<K>, CraftPattern<K>> originals, Consumer<Candidate<K>> completed,
            BoundedIntegerLinearSolver.WorkBudget budget, int[] ranks, int[] inputMasks, int[] outputMasks) {
        charge(budget, 1);
        if (index < permutation.length) {
            for (int next = index; next < permutation.length; next++) {
                int saved = permutation[index];
                permutation[index] = permutation[next];
                permutation[next] = saved;
                permute(graph, target, ordered, permutation, index + 1, patterns, seen, originals,
                        completed, budget, ranks, inputMasks, outputMasks);
                permutation[next] = permutation[index];
                permutation[index] = saved;
            }
            return;
        }
        for (int i = 0; i < permutation.length; i++) ranks[permutation[i]] = i + 1;
        var support = new BitSet(patterns.size());
        for (int i = 0; i < patterns.size(); i++) {
            var pattern = patterns.get(i);
            // Cover arc admission, projection construction, sorting, and its forward supply bound.
            charge(budget, 4L*(1L+pattern.inputs().size()+pattern.byproducts().size()));
            int earliestOutput = ranks.length;
            for (int mask = outputMasks[i]; mask != 0; mask &= mask - 1) {
                earliestOutput = Math.min(earliestOutput, ranks[Integer.numberOfTrailingZeros(mask)]);
            }
            boolean admitted = true;
            // Unproduced raw inputs have rank zero and precede every possible output.
            for (int mask = inputMasks[i]; mask != 0; mask &= mask - 1) {
                if (ranks[Integer.numberOfTrailingZeros(mask)] >= earliestOutput) {
                    admitted = false;
                    break;
                }
            }
            if (admitted) {
                support.set(i);
            }
        }
        if (support.isEmpty() || !seen.add(support)) return;
        var rank = new HashMap<K, Integer>();
        rank.put(target, ranks.length);
        for (int i = 0; i < ordered.size(); i++) rank.put(ordered.get(i), ranks[i]);
        // Most permutations rediscover an existing support. Materialize only the first one,
        // preserving original registration order before the stable supply-order sort.
        var selected = new LinkedHashMap<K, List<CraftPattern<K>>>();
        var supplyOrder = new ArrayList<CraftPattern<K>>(support.cardinality());
        for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
            var pattern = patterns.get(i);
            selected.computeIfAbsent(pattern.output(), ignored -> new ArrayList<>()).add(pattern);
            supplyOrder.add(pattern);
        }
        // All outputs lie after every input, so ordering producers by their last input is a
        // forward schedule even when a side output lies before that producer's primary output.
        supplyOrder.sort(Comparator.comparingInt(pattern -> lastInputRank(pattern, rank)));
        long capacity = optimisticCapacity(graph, target, supplyOrder);
        // A zero-stock support can become productive after replenishment. Retain it as a
        // topology template, but maySupply still rejects its current zero capacity. Filtering
        // here would make reuse silently miss routes enabled by the hypothetical inventory.
        completed.accept(new Candidate<>(graph.withPatterns(selected), capacity, originals, List.copyOf(supplyOrder)));
    }

    private static void charge(BoundedIntegerLinearSolver.WorkBudget budget, long work) {
        PlanningCancellation.check();
        if (!budget.tryConsume(work)) throw new WorkLimit();
    }

    private static final class WorkLimit extends RuntimeException {}

    private static final class SupportAccepted extends RuntimeException {
        SupportAccepted() { super(null, null, false, false); }
    }

    private static <K> int lastInputRank(CraftPattern<K> pattern, Map<K, Integer> rank) {
        int last = -1;
        var inputs = pattern.inputs();
        for (int slot = 0; slot < inputs.size(); slot++)
            last = Math.max(last, rank.getOrDefault(inputs.get(slot).key(), 0));
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
        for (int p = 0; p < supplyOrder.size(); p++) {
            var pattern = supplyOrder.get(p);
            PlanningCancellation.check();
            long times = Sat.SAT;
            var inputs = pattern.inputs();
            for (int slot = 0; slot < inputs.size(); slot++) {
                var input = inputs.get(slot);
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
            var byproducts = pattern.byproducts();
            for (int slot = 0; slot < byproducts.size(); slot++) {
                var output = byproducts.get(slot);
                supply.put(output.key(), Sat.add(
                        supply.getOrDefault(output.key(), graph.stock(output.key())),
                        Sat.mul(times, output.amount())));
            }
        }
        return supply.getOrDefault(target, graph.stock(target));
    }
}
