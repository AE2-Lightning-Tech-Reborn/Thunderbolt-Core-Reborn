package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded, exact fixed-Parikh-vector reachability for ordinary consumed-material Petri nets. */
final class PetriExecutionVerifier {
    enum Status { EXECUTABLE, UNREACHABLE, UNKNOWN }

    /** A compositional execution certificate, including the final target withdrawal. */
    record Certificate(BigInteger[] required, BigInteger[] delta) { }
    record Result(Status status, Certificate certificate) { }

    /** Shared across candidates and amount probes; an exhausted search is never a negative proof. */
    static final class Budget {
        private int nodes;
        private long work;
        Budget(int nodes, long work) { this.nodes = nodes; this.work = work; }
        boolean take(long amount) {
            PlanningCancellation.check();
            if (nodes <= 0 || amount > work) return false;
            nodes--;
            work -= amount;
            return true;
        }
        int remainingNodes() { return nodes; }
    }

    private record Block(long[] firings, BigInteger[] required, BigInteger[] delta) { }
    private static final class Frame {
        final long[] remaining;
        final BigInteger[] marking;
        final Block incoming;
        List<Block> choices;
        int next;
        Frame(long[] remaining, BigInteger[] marking, Block incoming) {
            this.remaining = remaining;
            this.marking = marking;
            this.incoming = incoming;
        }
    }
    private static final class Counts {
        final long[] values;
        Counts(long[] values) { this.values = values; }
        @Override public int hashCode() { return Arrays.hashCode(values); }
        @Override public boolean equals(Object other) {
            return other instanceof Counts counts && Arrays.equals(values, counts.values);
        }
    }

    private PetriExecutionVerifier() { }

    static Result verify(long[][] pre, long[][] post, long[] firings, BigInteger[] initial,
                         int[] cyclicItems, int target, long amount, Budget budget) {
        PlanningCancellation.check();
        for (int i = 0; i < initial.length; i++) {
            BigInteger terminal = initial[i];
            for (int r = 0; r < firings.length; r++) terminal = terminal.add(
                    BigInteger.valueOf(post[r][i]).subtract(BigInteger.valueOf(pre[r][i]))
                            .multiply(BigInteger.valueOf(firings[r])));
            if (terminal.compareTo(BigInteger.valueOf(i == target ? amount : 0L)) < 0) {
                return new Result(Status.UNREACHABLE, null);
            }
        }
        var path = new ArrayList<Frame>();
        path.add(new Frame(firings.clone(), initial.clone(), null));
        Set<Counts> visited = new HashSet<>();
        visited.add(new Counts(path.getFirst().remaining));
        while (!path.isEmpty()) {
            PlanningCancellation.check();
            Frame frame = path.getLast();
            if (frame.choices == null) {
                if (!budget.take((long) (pre.length + 32) * initial.length)
                        || path.size() > 512) return new Result(Status.UNKNOWN, null);
                if (Arrays.stream(frame.remaining).allMatch(n -> n == 0L)) {
                    if (frame.marking[target].compareTo(BigInteger.valueOf(amount)) < 0) {
                        path.removeLast();
                        continue;
                    }
                    Block proof = empty(pre.length, initial.length);
                    for (int i = 1; i < path.size(); i++) proof = compose(proof, path.get(i).incoming);
                    return new Result(Status.EXECUTABLE, finish(proof, target, amount));
                }
                frame.choices = new ArrayList<>();
                // Reuse a just-executed motif when its cyclic marking repeats. Its exact prefix
                // and net change bound all repetitions, including external losses and byproducts.
                if (cyclicItems.length > 0) {
                    for (int start = path.size() - 2; start >= Math.max(0, path.size() - 17); start--) {
                        if (!sameStates(frame.marking, path.get(start).marking, cyclicItems)) continue;
                        Block motif = empty(pre.length, initial.length);
                        for (int j = start + 1; j < path.size(); j++) motif = compose(motif, path.get(j).incoming);
                        long copies = repetitions(motif, frame.remaining, frame.marking);
                        if (copies > 1L) {
                            frame.choices.add(repeat(motif, copies));
                            break;
                        }
                    }
                }
                for (int recipe = 0; recipe < pre.length; recipe++) {
                    if (frame.remaining[recipe] == 0L) continue;
                    Block one = run(pre, post, recipe, 1L);
                    long copies = repetitions(one, frame.remaining, frame.marking);
                    if (copies == 0L) continue;
                    if (copies > 1L) frame.choices.add(repeat(one, copies));
                    // This branch is essential: maximal batching alone is not a complete search.
                    frame.choices.add(one);
                }
            }
            if (frame.next == frame.choices.size()) {
                path.removeLast();
                continue;
            }
            Block step = frame.choices.get(frame.next++);
            long[] remaining = frame.remaining.clone();
            for (int r = 0; r < remaining.length; r++) remaining[r] -= step.firings[r];
            // For a fixed initial marking, remaining counts uniquely determine the current marking.
            if (!visited.add(new Counts(remaining))) continue;
            BigInteger[] marking = frame.marking.clone();
            for (int i = 0; i < marking.length; i++) marking[i] = marking[i].add(step.delta[i]);
            path.add(new Frame(remaining, marking, step));
        }
        // Every enabled one-step branch was explored; no horizon/work cutoff reaches this return.
        return new Result(Status.UNREACHABLE, null);
    }

    /** Always gives a valid sufficient initial marking, without claiming it is minimal. */
    static Certificate orderedCertificate(long[][] pre, long[][] post, long[] firings,
                                          int target, long amount) {
        Block proof = empty(pre.length, pre[0].length);
        for (int r = 0; r < firings.length; r++) {
            PlanningCancellation.check();
            if (firings[r] > 0) proof = compose(proof, run(pre, post, r, firings[r]));
        }
        return finish(proof, target, amount);
    }

    private static Certificate finish(Block proof, int target, long amount) {
        BigInteger[] required = proof.required.clone();
        required[target] = required[target].max(BigInteger.valueOf(amount).subtract(proof.delta[target]));
        return new Certificate(required, proof.delta.clone());
    }

    private static boolean sameStates(BigInteger[] left, BigInteger[] right, int[] items) {
        for (int item : items) if (!left[item].equals(right[item])) return false;
        return true;
    }

    private static Block empty(int recipes, int items) {
        BigInteger[] zero = new BigInteger[items];
        Arrays.fill(zero, BigInteger.ZERO);
        return new Block(new long[recipes], zero, zero);
    }

    private static Block run(long[][] pre, long[][] post, int recipe, long copies) {
        long[] firings = new long[pre.length];
        firings[recipe] = 1L;
        BigInteger[] required = new BigInteger[pre[recipe].length];
        BigInteger[] delta = new BigInteger[required.length];
        for (int i = 0; i < required.length; i++) {
            required[i] = BigInteger.valueOf(pre[recipe][i]);
            delta[i] = BigInteger.valueOf(post[recipe][i]).subtract(required[i]);
        }
        Block one = new Block(firings, required, delta);
        return copies == 1L ? one : repeat(one, copies);
    }

    private static Block compose(Block left, Block right) {
        long[] counts = left.firings.clone();
        for (int r = 0; r < counts.length; r++) counts[r] = Math.addExact(counts[r], right.firings[r]);
        BigInteger[] required = left.required.clone();
        BigInteger[] delta = left.delta.clone();
        for (int i = 0; i < required.length; i++) {
            required[i] = required[i].max(right.required[i].subtract(delta[i]));
            delta[i] = delta[i].add(right.delta[i]);
        }
        return new Block(counts, required, delta);
    }

    private static Block repeat(Block block, long copies) {
        long[] counts = block.firings.clone();
        for (int r = 0; r < counts.length; r++) counts[r] = Math.multiplyExact(counts[r], copies);
        BigInteger n = BigInteger.valueOf(copies);
        BigInteger[] required = block.required.clone();
        BigInteger[] delta = block.delta.clone();
        for (int i = 0; i < required.length; i++) {
            if (delta[i].signum() < 0) required[i] = required[i].subtract(delta[i].multiply(n.subtract(BigInteger.ONE)));
            delta[i] = delta[i].multiply(n);
        }
        return new Block(counts, required, delta);
    }

    private static long repetitions(Block block, long[] remaining, BigInteger[] marking) {
        long maximum = Long.MAX_VALUE;
        for (int r = 0; r < remaining.length; r++) {
            if (block.firings[r] > 0) maximum = Math.min(maximum, remaining[r] / block.firings[r]);
        }
        for (int i = 0; i < marking.length; i++) {
            BigInteger slack = marking[i].subtract(block.required[i]);
            if (slack.signum() < 0) return 0L;
            if (block.delta[i].signum() < 0) {
                BigInteger bound = slack.divide(block.delta[i].negate()).add(BigInteger.ONE);
                maximum = bound.min(BigInteger.valueOf(maximum)).longValueExact();
            }
        }
        return maximum;
    }
}
