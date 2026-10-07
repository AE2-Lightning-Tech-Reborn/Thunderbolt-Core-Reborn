package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge.SparseLongMatrix;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded forward witness construction. A stalled greedy schedule makes no negative claim. */
final class PetriBlockScheduler {
    private record Step(PetriExecutionTrace.Node trace, PetriExecutionTrace.Summary summary,
                        BigInteger executions) { }

    private PetriBlockScheduler() { }

    static PetriExecutionVerifier.Certificate schedule(SparseLongMatrix pre, SparseLongMatrix post,
            long[] firings, BigInteger[] initial, int target, long amount,
            List<PetriBlockCatalog.Block> blocks, PetriExecutionVerifier.Budget budget) {
        if (!budget.take((long) (pre.rows() + blocks.size()) * (pre.rows() + pre.columns()))) return null;
        var choices = new ArrayList<Step>();
        for (int r = 0; r < firings.length; r++) {
            if (firings[r] > 0) add(choices, new PetriExecutionTrace.Fire(r, 1), pre, post);
        }
        for (var block : blocks) add(choices, block.trace(), pre, post);
        long[] remaining = firings.clone();
        BigInteger[] marking = initial.clone();
        var trace = new ArrayList<PetriExecutionTrace.Node>();
        for (int stage = 0; stage < 256; stage++) {
            if (Arrays.stream(remaining).allMatch(n -> n == 0)) {
                var proof = PetriExecutionTrace.certificate(new PetriExecutionTrace.Sequence(trace),
                        pre, post, firings, target, amount);
                if (proof == null) return null;
                for (int i = 0; i < initial.length; i++)
                    if (proof.required()[i].compareTo(initial[i]) > 0) return null;
                return proof;
            }
            if (!budget.take((long) choices.size() * (firings.length + initial.length))) return null;
            Step selected = null;
            long copies = 0;
            BigInteger bestScore = BigInteger.ZERO;
            for (var step : choices) {
                PlanningCancellation.check();
                long n = Long.MAX_VALUE;
                var summary = step.summary;
                for (int r = 0; r < remaining.length; r++) if (summary.firings()[r] > 0)
                    n = Math.min(n, remaining[r] / summary.firings()[r]);
                for (int i = 0; n > 0 && i < marking.length; i++) {
                    BigInteger spare = marking[i].subtract(summary.required()[i]);
                    if (spare.signum() < 0) n = 0;
                    else if (summary.delta()[i].signum() < 0)
                        n = Math.min(n, spare.divide(summary.delta()[i].negate()).add(BigInteger.ONE)
                                .min(BigInteger.valueOf(n)).longValueExact());
                }
                BigInteger score = step.executions.multiply(BigInteger.valueOf(n));
                if (score.compareTo(bestScore) > 0) {
                    selected = step;
                    copies = n;
                    bestScore = score;
                }
            }
            if (selected == null) return null;
            var summary = selected.summary;
            for (int r = 0; r < remaining.length; r++)
                remaining[r] -= Math.multiplyExact(summary.firings()[r], copies);
            for (int i = 0; i < marking.length; i++)
                marking[i] = marking[i].add(summary.delta()[i].multiply(BigInteger.valueOf(copies)));
            trace.add(new PetriExecutionTrace.Repeat(selected.trace, copies));
        }
        return null;
    }

    private static void add(List<Step> choices, PetriExecutionTrace.Node trace,
            SparseLongMatrix pre, SparseLongMatrix post) {
        var summary = PetriExecutionTrace.summarize(trace, pre, post);
        if (summary == null) return;
        BigInteger executions = BigInteger.ZERO;
        for (long n : summary.firings()) executions = executions.add(BigInteger.valueOf(n));
        if (executions.signum() > 0) choices.add(new Step(trace, summary, executions));
    }
}
