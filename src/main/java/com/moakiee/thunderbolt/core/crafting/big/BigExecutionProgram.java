package com.moakiee.thunderbolt.core.crafting.big;

import com.moakiee.thunderbolt.core.crafting.planner.*;
import com.moakiee.thunderbolt.core.storage.big.BigAmounts;

import java.math.BigInteger;
import java.util.*;

/**
 * Executable-prefix proof with repeated blocks; neither quantities nor repetitions become loops.
 */
public record BigExecutionProgram<K>(
        List<Block<K>> blocks, Map<K, BigInteger> required, Map<K, BigInteger> delta) {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    private static final int MAX_CERTIFICATE_WORK = 200_000;

    public BigExecutionProgram {
        blocks = List.copyOf(blocks);
        required = Map.copyOf(required);
        delta = Map.copyOf(delta);
    }

    public record Step<K>(CraftPattern<K> recipe, BigInteger copies) {
        public Step {
            if (BigAmounts.nonNegative(copies).signum() == 0)
                throw new IllegalArgumentException("empty step");
        }
    }

    public record Block<K>(
            List<Step<K>> steps,
            BigInteger repetitions,
            Map<K, BigInteger> required,
            Map<K, BigInteger> delta) {
        public Block {
            steps = List.copyOf(steps);
            required = Map.copyOf(required);
            delta = Map.copyOf(delta);
        }
    }

    public record Summary<K>(Map<K, BigInteger> required, Map<K, BigInteger> delta) {}

    /** Per-firing physical pre/post arcs. Unchanged catalysts appear on both sides. */
    public static <K> Summary<K> arcs(CraftPattern<K> p) {
        var pre = new LinkedHashMap<K, BigInteger>();
        var post = new LinkedHashMap<K, BigInteger>();
        for (var in : p.inputs()) {
            if (in.reusableStockSource() != null
                    || (in.returned() && in.uses() != CraftInput.INFINITE_USES))
                throw new IllegalArgumentException(
                        "Physical exact program requires explicit tool/seed arcs");
            pre.merge(in.key(), in.exactAmount(), BigInteger::add);
            if (in.returned()) post.merge(in.key(), in.exactAmount(), BigInteger::add);
        }
        post.merge(p.output(), p.exactOutputAmount(), BigInteger::add);
        for (var out : p.byproducts()) post.merge(out.key(), out.exactAmount(), BigInteger::add);
        var delta = new LinkedHashMap<K, BigInteger>(post);
        pre.forEach((k, n) -> delta.merge(k, n.negate(), BigInteger::add));
        delta.values().removeIf(n -> n.signum() == 0);
        return new Summary<>(Map.copyOf(pre), Map.copyOf(delta));
    }

    public static <K> Summary<K> repeat(Summary<K> s, BigInteger n) {
        if (n.signum() <= 0) throw new IllegalArgumentException("repetitions");
        var pre = new LinkedHashMap<K, BigInteger>(s.required());
        var delta = new LinkedHashMap<K, BigInteger>();
        var previousRepetitions = n.subtract(ONE);
        s.delta()
                .forEach(
                        (k, d) -> {
                            if (d.signum() < 0)
                                pre.merge(k, d.negate().multiply(previousRepetitions), BigInteger::add);
                            delta.put(k, d.multiply(n));
                        });
        return new Summary<>(Map.copyOf(pre), Map.copyOf(delta));
    }

    public static <K> Summary<K> compose(List<Step<K>> steps) {
        return compose(steps, null);
    }

    private static <K> Summary<K> compose(
            List<Step<K>> steps, Map<CraftPattern<K>, Summary<K>> recipeArcs) {
        var pre = new LinkedHashMap<K, BigInteger>();
        var delta = new LinkedHashMap<K, BigInteger>();
        for (var step : steps) {
            PlanningCancellation.check();
            var physical = recipeArcs == null ? arcs(step.recipe())
                    : recipeArcs.computeIfAbsent(step.recipe(), BigExecutionProgram::arcs);
            var s = repeat(physical, step.copies());
            append(pre, delta, s);
        }
        return new Summary<>(Map.copyOf(pre), Map.copyOf(delta));
    }

    private static <K> void append(Map<K, BigInteger> pre, Map<K, BigInteger> delta, Summary<K> s) {
        s.required()
                .forEach(
                        (k, n) ->
                                pre.merge(
                                        k,
                                        n.subtract(delta.getOrDefault(k, ZERO)).max(ZERO),
                                        BigInteger::max));
        s.delta().forEach((k, n) -> delta.merge(k, n, BigInteger::add));
    }

    public static <K> Block<K> block(List<Step<K>> steps, BigInteger repetitions) {
        if (steps.isEmpty()) throw new IllegalArgumentException("empty block");
        var s = repeat(compose(steps), repetitions);
        return new Block<>(steps, repetitions, s.required(), s.delta());
    }

    public static <K> BigExecutionProgram<K> of(List<Block<K>> blocks) {
        var pre = new LinkedHashMap<K, BigInteger>();
        var delta = new LinkedHashMap<K, BigInteger>();
        var verified = new ArrayList<Block<K>>();
        for (var block : blocks) {
            var checked = block(block.steps(), block.repetitions());
            verified.add(checked);
            append(pre, delta, new Summary<>(checked.required(), checked.delta()));
        }
        pre.values().removeIf(n -> n.signum() == 0);
        delta.values().removeIf(n -> n.signum() == 0);
        return new BigExecutionProgram<>(verified, pre, delta);
    }

    public static <K> BigInteger supported(
            Summary<K> s, Map<K, BigInteger> stock, BigInteger limit) {
        var result = limit;
        for (var entry : s.required().entrySet()) {
            var available = stock.getOrDefault(entry.getKey(), ZERO);
            if (available.compareTo(entry.getValue()) < 0) return ZERO;
            var d = s.delta().getOrDefault(entry.getKey(), ZERO);
            if (d.signum() < 0)
                result =
                        result.min(
                                ONE.add(available.subtract(entry.getValue()).divide(d.negate())));
        }
        return result;
    }

    public static <K> void apply(
            Map<K, BigInteger> stock, Map<K, BigInteger> delta, BigInteger times) {
        BigAmounts.nonNegative(times);
        var updates = new LinkedHashMap<K, BigInteger>();
        delta.forEach(
                (k, n) ->
                        updates.put(
                                k,
                                BigAmounts.nonNegative(
                                        stock.getOrDefault(k, ZERO).add(n.multiply(times)))));
        updates.forEach(
                (k, n) -> {
                    if (n.signum() == 0) stock.remove(k);
                    else stock.put(k, n);
                });
    }

    /**
     * Orders a candidate and accelerates repeatable sweeps. Failure is not an unreachability proof.
     */
    public static <K> Optional<BigExecutionProgram<K>> certify(
            Map<CraftPattern<K>, BigInteger> counts, Map<K, BigInteger> supplied) {
        var left = new LinkedHashMap<CraftPattern<K>, BigInteger>();
        for (var entry : counts.entrySet()) {
            if (BigAmounts.nonNegative(entry.getValue()).signum() > 0)
                left.put(entry.getKey(), entry.getValue());
        }
        var stock = new LinkedHashMap<K, BigInteger>(supplied);
        var blocks = new ArrayList<Block<K>>();
        // CraftPattern is immutable. Keep physical arcs local to this certificate attempt;
        // inventory, remaining counts and execution order must never enter this cache.
        var recipeArcs = new IdentityHashMap<CraftPattern<K>, Summary<K>>(
                Math.min(left.size(), MAX_CERTIFICATE_WORK));
        left = dependencyOrder(left, recipeArcs);
        int work = 0;
        while (!left.isEmpty()) {
            PlanningCancellation.check();
            var steps = new ArrayList<Step<K>>();
            for (var pending = left.entrySet().iterator(); pending.hasNext();) {
                var e = pending.next();
                if (++work > MAX_CERTIFICATE_WORK) return Optional.empty();
                var arcs = recipeArcs.computeIfAbsent(e.getKey(), BigExecutionProgram::arcs);
                var n = supported(arcs, stock, e.getValue());
                if (n.signum() == 0) continue;
                steps.add(new Step<>(e.getKey(), n));
                apply(stock, arcs.delta(), n);
                var remaining = e.getValue().subtract(n);
                if (remaining.signum() == 0) pending.remove();
                else e.setValue(remaining);
            }
            if (steps.isEmpty()) return Optional.empty();
            var summary = compose(steps, recipeArcs);
            BigInteger repeats = null;
            for (var step : steps) {
                var n = left.getOrDefault(step.recipe(), ZERO).divide(step.copies());
                repeats = repeats == null ? n : repeats.min(n);
            }
            repeats = supported(summary, stock, repeats);
            if (repeats.signum() > 0) {
                apply(stock, summary.delta(), repeats);
                for (var step : steps) {
                    var n = left.get(step.recipe()).subtract(step.copies().multiply(repeats));
                    if (n.signum() == 0) left.remove(step.recipe());
                    else left.put(step.recipe(), n);
                }
            }
            var repetitions = repeats.add(ONE);
            var repeated = repeat(summary, repetitions);
            blocks.add(new Block<>(steps, repetitions, repeated.required(), repeated.delta()));
        }
        // Rebuild every block from its recipes at the public verification boundary. The cached
        // arcs and internally assembled summaries above are not trusted by this final proof.
        var program = of(blocks);
        for (var e : program.required().entrySet())
            if (e.getValue().compareTo(supplied.getOrDefault(e.getKey(), ZERO)) > 0)
                throw new IllegalStateException("Invalid executable prefix proof");
        return Optional.of(program);
    }

    /**
     * A reverse-listed DAG must not spend the sweep budget rediscovering each dependency.
     * This only changes the candidate order: the normal physical-stock replay and final
     * independent proof still validate every firing. Cycles retain the repeatable-sweep path.
     */
    private static <K> LinkedHashMap<CraftPattern<K>, BigInteger> dependencyOrder(
            LinkedHashMap<CraftPattern<K>, BigInteger> counts,
            Map<CraftPattern<K>, Summary<K>> recipeArcs) {
        if (counts.size() > MAX_CERTIFICATE_WORK) return counts;
        var recipes = new ArrayList<>(counts.keySet());
        var producers = new HashMap<K, List<Integer>>();
        int work = 0;
        for (int i = 0; i < recipes.size(); i++) {
            PlanningCancellation.check();
            if (++work > MAX_CERTIFICATE_WORK) return counts;
            var physical = recipeArcs.computeIfAbsent(recipes.get(i), BigExecutionProgram::arcs);
            for (var output : physical.delta().entrySet()) {
                if (++work > MAX_CERTIFICATE_WORK) return counts;
                if (output.getValue().signum() > 0)
                    producers.computeIfAbsent(output.getKey(), ignored -> new ArrayList<>()).add(i);
            }
        }
        var successors = new ArrayList<Set<Integer>>(recipes.size());
        for (int i = 0; i < recipes.size(); i++) successors.add(new LinkedHashSet<>());
        var indegree = new int[recipes.size()];
        for (int consumer = 0; consumer < recipes.size(); consumer++) {
            PlanningCancellation.check();
            for (var key : recipeArcs.get(recipes.get(consumer)).required().keySet()) {
                if (++work > MAX_CERTIFICATE_WORK) return counts;
                for (int producer : producers.getOrDefault(key, List.of())) {
                    if (++work > MAX_CERTIFICATE_WORK) return counts;
                    if (successors.get(producer).add(consumer)) indegree[consumer]++;
                }
            }
        }
        var ready = new ArrayDeque<Integer>();
        for (int i = 0; i < recipes.size(); i++) if (indegree[i] == 0) ready.add(i);
        var ordered = new LinkedHashMap<CraftPattern<K>, BigInteger>();
        while (!ready.isEmpty()) {
            PlanningCancellation.check();
            int producer = ready.remove();
            var recipe = recipes.get(producer);
            ordered.put(recipe, counts.get(recipe));
            for (int consumer : successors.get(producer))
                if (--indegree[consumer] == 0) ready.add(consumer);
        }
        return ordered.size() == counts.size() ? ordered : counts;
    }
}
