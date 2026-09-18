package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge.SparseLongMatrix;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/** Immutable, compressed execution evidence. Quantities never determine the traversal length. */
final class PetriExecutionTrace {
    sealed interface Node permits Fire, Sequence, Repeat { }
    record Fire(int recipe, long copies) implements Node {
        Fire { if (recipe < 0 || copies < 0) throw new IllegalArgumentException(); }
    }
    record Sequence(List<Node> steps) implements Node {
        Sequence { steps = List.copyOf(steps); }
    }
    record Repeat(Node body, long copies) implements Node {
        Repeat { if (body == null || copies < 0) throw new IllegalArgumentException(); }
    }
    record Summary(long[] firings, BigInteger[] required, BigInteger[] delta) { }
    private record Visit(Node node, boolean childrenDone) { }

    private PetriExecutionTrace() { }

    /** Rebuilds prefixes from the compiled recipe arcs, without trusting any solver summary. */
    static Summary summarize(Node root, SparseLongMatrix pre, SparseLongMatrix post) {
        int recipes = pre.rows(), items = pre.columns();
        var values = new IdentityHashMap<Node, Summary>();
        var stack = new ArrayDeque<Visit>();
        stack.push(new Visit(root, false));
        long work = 0;
        while (!stack.isEmpty()) {
            PlanningCancellation.check();
            Visit visit = stack.pop();
            Node node = visit.node;
            if (values.containsKey(node)) continue;
            if (!visit.childrenDone && !(node instanceof Fire)) {
                stack.push(new Visit(node, true));
                if (node instanceof Sequence sequence) {
                    for (int i = sequence.steps.size() - 1; i >= 0; i--)
                        stack.push(new Visit(sequence.steps.get(i), false));
                } else if (node instanceof Repeat repeat) stack.push(new Visit(repeat.body, false));
                continue;
            }
            if ((work += recipes + items) > 4_194_304L) return null;
            Summary result;
            if (node instanceof Fire fire) {
                if (fire.recipe >= recipes) return null;
                result = empty(recipes, items);
                result.firings[fire.recipe] = 1L;
                for (int i = 0; i < items; i++) {
                    result.required[i] = BigInteger.valueOf(pre.get(fire.recipe, i));
                    result.delta[i] = BigInteger.valueOf(post.get(fire.recipe, i)).subtract(result.required[i]);
                }
                result = repeat(result, fire.copies);
            } else if (node instanceof Repeat repeat) {
                result = repeat(values.get(repeat.body), repeat.copies);
            } else {
                result = empty(recipes, items);
                for (Node step : ((Sequence) node).steps) {
                    result = compose(result, values.get(step));
                    if (result == null) return null;
                }
            }
            if (result == null) return null;
            values.put(node, result);
        }
        return values.get(root);
    }

    static PetriExecutionVerifier.Certificate certificate(Node trace, SparseLongMatrix pre, SparseLongMatrix post,
                                                          long[] expected, int target, long amount) {
        Summary summary = summarize(trace, pre, post);
        if (summary == null || !Arrays.equals(summary.firings, expected)) return null;
        summary.required[target] = summary.required[target]
                .max(BigInteger.valueOf(amount).subtract(summary.delta[target]));
        return new PetriExecutionVerifier.Certificate(summary.required, summary.delta, trace);
    }

    private static Summary empty(int recipes, int items) {
        var required = new BigInteger[items];
        var delta = new BigInteger[items];
        Arrays.fill(required, BigInteger.ZERO);
        Arrays.fill(delta, BigInteger.ZERO);
        return new Summary(new long[recipes], required, delta);
    }

    private static Summary compose(Summary left, Summary right) {
        var result = empty(left.firings.length, left.required.length);
        try {
            for (int r = 0; r < result.firings.length; r++)
                result.firings[r] = Math.addExact(left.firings[r], right.firings[r]);
        } catch (ArithmeticException overflow) { return null; }
        for (int i = 0; i < result.required.length; i++) {
            result.required[i] = left.required[i].max(right.required[i].subtract(left.delta[i]));
            result.delta[i] = left.delta[i].add(right.delta[i]);
        }
        return result;
    }

    private static Summary repeat(Summary body, long copies) {
        if (body == null) return null;
        var result = empty(body.firings.length, body.required.length);
        if (copies == 0) return result;
        try {
            for (int r = 0; r < result.firings.length; r++)
                result.firings[r] = Math.multiplyExact(body.firings[r], copies);
        } catch (ArithmeticException overflow) { return null; }
        var n = BigInteger.valueOf(copies);
        for (int i = 0; i < result.required.length; i++) {
            result.required[i] = body.required[i].add(body.delta[i].negate().max(BigInteger.ZERO)
                    .multiply(n.subtract(BigInteger.ONE)));
            result.delta[i] = body.delta[i].multiply(n);
        }
        return result;
    }
    static Summary summarize(Node root, long[][] pre, long[][] post) {
        return summarize(root, SparseLongMatrix.fromDense(pre), SparseLongMatrix.fromDense(post));
    }

    static PetriExecutionVerifier.Certificate certificate(Node trace, long[][] pre, long[][] post,
                                                          long[] expected, int target, long amount) {
        return certificate(trace, SparseLongMatrix.fromDense(pre), SparseLongMatrix.fromDense(post), expected, target, amount);
    }

}
