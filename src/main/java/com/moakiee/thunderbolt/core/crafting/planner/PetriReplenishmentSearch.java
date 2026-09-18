package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/** Backward prefix search for an admitted firing vector. Only equal remaining quotas may dominate. */
final class PetriReplenishmentSearch {
    record Result(PetriExecutionVerifier.Certificate proof, long[] missing) { }
    private record Counts(long[] values) {
        @Override public boolean equals(Object other) {
            return other instanceof Counts counts && Arrays.equals(values, counts.values);
        }
        @Override public int hashCode() { return Arrays.hashCode(values); }
    }
    private record State(long[] remaining, BigInteger[] required, PetriExecutionTrace.Node suffix,
                         int depth, BigInteger todo, BigInteger priority, long order) { }
    private record Step(long[] firings, BigInteger[] required, BigInteger[] delta, PetriExecutionTrace.Node trace) { }

    private PetriReplenishmentSearch() { }

    static Result search(long[][] pre, long[][] post, long[] firings, long[] stock,
                         boolean[] allowed, int[] distances, int target, long amount,
                         List<PetriBlockCatalog.Block> blocks, PetriExecutionVerifier.Budget budget,
                         int nodeLimit) {
        var steps = new ArrayList<Step>();
        for (int r = 0; r < pre.length; r++) {
            if (firings[r] == 0) continue;
            var trace = new PetriExecutionTrace.Fire(r, 1);
            var summary = PetriExecutionTrace.summarize(trace, pre, post);
            steps.add(new Step(summary.firings(), summary.required(), summary.delta(), trace));
        }
        for (var block : blocks) {
            var summary = PetriExecutionTrace.summarize(block.trace(), pre, post);
            if (summary != null) steps.add(new Step(summary.firings(), summary.required(), summary.delta(), block.trace()));
        }
        var queue = new PriorityQueue<State>(java.util.Comparator.comparing(State::todo)
                .thenComparing(State::priority).thenComparingLong(State::order));
        var basis = new HashMap<Counts, List<State>>();
        var goal = new BigInteger[stock.length];
        Arrays.fill(goal, BigInteger.ZERO); goal[target] = BigInteger.valueOf(amount);
        State start = new State(firings.clone(), goal, new PetriExecutionTrace.Sequence(List.of()),
                0, total(firings), BigInteger.ZERO, 0);
        queue.add(start); basis.put(new Counts(start.remaining), new ArrayList<>(List.of(start)));
        Result best = null;
        long order = 1;
        while (!queue.isEmpty() && nodeLimit-- > 0) {
            PlanningCancellation.check();
            if (!budget.take((long) (steps.size() + 32) * stock.length)) break;
            State state = queue.remove();
            if (!basis.get(new Counts(state.remaining)).contains(state)) continue;
            if (Arrays.stream(state.remaining).allMatch(n -> n == 0)) {
                long[] missing = shortage(state.required, stock, allowed);
                if (missing == null || (best != null && !better(missing, best.missing, distances))) continue;
                var proof = PetriExecutionTrace.certificate(state.suffix, pre, post, firings, target, amount);
                if (proof == null || !Arrays.equals(proof.required(), state.required)) continue;
                best = new Result(proof, missing);
                if (Arrays.stream(missing).allMatch(n -> n == 0)) return best;
                continue;
            }
            if (state.depth >= 512) continue; // Incomplete exploration never yields a negative proof.
            for (Step step : steps) {
                long maximum = Long.MAX_VALUE;
                for (int r = 0; r < firings.length; r++) if (step.firings[r] > 0)
                    maximum = Math.min(maximum, state.remaining[r] / step.firings[r]);
                if (maximum == 0 || maximum == Long.MAX_VALUE) continue;
                for (long n : maximum == 1 ? new long[]{1} : new long[]{maximum, 1}) {
                    long[] remaining = state.remaining.clone();
                    for (int r = 0; r < remaining.length; r++) remaining[r] -= n * step.firings[r];
                    var required = new BigInteger[stock.length];
                    var copies = BigInteger.valueOf(n);
                    var priority = BigInteger.ZERO;
                    for (int i = 0; i < stock.length; i++) {
                        var hurdle = step.required[i].add(step.delta[i].negate().max(BigInteger.ZERO)
                                .multiply(copies.subtract(BigInteger.ONE)));
                        required[i] = hurdle.max(state.required[i].subtract(step.delta[i].multiply(copies)));
                        priority = priority.add(required[i].subtract(BigInteger.valueOf(stock[i])).max(BigInteger.ZERO));
                    }
                    var peers = basis.computeIfAbsent(new Counts(remaining), ignored -> new ArrayList<>());
                    // Compare full markings, never stock-subtracted Missing or different suffix quotas.
                    if (peers.stream().anyMatch(peer -> covers(required, peer.required))) continue;
                    peers.removeIf(peer -> covers(peer.required, required));
                    var trace = new PetriExecutionTrace.Sequence(List.of(new PetriExecutionTrace.Repeat(step.trace, n), state.suffix));
                    State next = new State(remaining, required, trace, state.depth + 1, total(remaining), priority, order++);
                    peers.add(next); queue.add(next);
                }
            }
        }
        return best;
    }

    private static boolean covers(BigInteger[] a, BigInteger[] b) {
        for (int i = 0; i < a.length; i++) if (a[i].compareTo(b[i]) < 0) return false;
        return true;
    }

    private static BigInteger total(long[] values) {
        BigInteger result = BigInteger.ZERO;
        for (long n : values) result = result.add(BigInteger.valueOf(n));
        return result;
    }

    private static long[] shortage(BigInteger[] required, long[] stock, boolean[] allowed) {
        long[] result = new long[stock.length];
        for (int i = 0; i < result.length; i++) {
            BigInteger n = required[i].subtract(BigInteger.valueOf(stock[i])).max(BigInteger.ZERO);
            if ((!allowed[i] && n.signum() > 0) || n.compareTo(BigInteger.valueOf(Sat.SAT)) >= 0) return null;
            result[i] = n.longValueExact();
        }
        return result;
    }

    private static boolean better(long[] candidate, long[] incumbent, int[] distances) {
        var tiers = new java.util.TreeMap<Integer, BigInteger>();
        for (int i = 0; i < candidate.length; i++) tiers.merge(distances[i],
                BigInteger.valueOf(candidate[i]).subtract(BigInteger.valueOf(incumbent[i])), BigInteger::add);
        for (var value : tiers.values()) if (value.signum() != 0) return value.signum() < 0;
        return false;
    }
}
