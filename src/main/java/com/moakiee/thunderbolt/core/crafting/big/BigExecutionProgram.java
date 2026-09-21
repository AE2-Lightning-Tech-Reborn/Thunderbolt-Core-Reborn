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
        s.delta()
                .forEach(
                        (k, d) -> {
                            if (d.signum() < 0)
                                pre.merge(k, d.negate().multiply(n.subtract(ONE)), BigInteger::add);
                            delta.put(k, d.multiply(n));
                        });
        return new Summary<>(Map.copyOf(pre), Map.copyOf(delta));
    }

    public static <K> Summary<K> compose(List<Step<K>> steps) {
        var pre = new LinkedHashMap<K, BigInteger>();
        var delta = new LinkedHashMap<K, BigInteger>();
        for (var step : steps) {
            PlanningCancellation.check();
            var s = repeat(arcs(step.recipe()), step.copies());
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
        counts.forEach(
                (p, n) -> {
                    if (BigAmounts.nonNegative(n).signum() > 0) left.put(p, n);
                });
        var stock = new LinkedHashMap<K, BigInteger>(supplied);
        var blocks = new ArrayList<Block<K>>();
        int work = 0;
        while (!left.isEmpty()) {
            PlanningCancellation.check();
            var steps = new ArrayList<Step<K>>();
            for (var e : new ArrayList<>(left.entrySet())) {
                if (++work > 200_000) return Optional.empty();
                var arcs = arcs(e.getKey());
                var n = supported(arcs, stock, e.getValue());
                if (n.signum() == 0) continue;
                steps.add(new Step<>(e.getKey(), n));
                apply(stock, arcs.delta(), n);
                var remaining = e.getValue().subtract(n);
                if (remaining.signum() == 0) left.remove(e.getKey());
                else left.put(e.getKey(), remaining);
            }
            if (steps.isEmpty()) return Optional.empty();
            var summary = compose(steps);
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
            blocks.add(block(steps, repeats.add(ONE)));
        }
        var program = of(blocks);
        for (var e : program.required().entrySet())
            if (e.getValue().compareTo(supplied.getOrDefault(e.getKey(), ZERO)) > 0)
                throw new IllegalStateException("Invalid executable prefix proof");
        return Optional.of(program);
    }
}
