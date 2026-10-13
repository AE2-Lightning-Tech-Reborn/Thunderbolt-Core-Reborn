package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Bounded three-recipe proposals, each certified against every original resource constraint. */
final class TripleBatchAllocation {
    static final int MAX_PAIR_CALLS = 36;
    static final int MAX_RESIDUE_STEPS = 8_192;
    private static final int SMALL_COUNTS = 8;

    record Allocation(long first, long second, long third, BigInteger consumed) {
        long executions() { return first + second + third; }
    }

    private TripleBatchAllocation() {}

    /**
     * Fix one recipe at a bounded set of counts, then allocate the remaining demand with the
     * two-recipe solver. Samples include zero, small counts and capacity endpoints; their number
     * never depends on the requested quantity. This is not a complete three-variable optimizer:
     * null means that this bounded search found no certified candidate, not infeasibility.
     * The caller retains its incumbent and separately decides whether a proposal improves it.
     *
     * <p>All candidates share the pair-call and residue-step limits and the supplied work allowance.
     * A refused work debit stops every later sample, including samples of another pivot recipe.
     */
    static Allocation solve(long demand, long a, long b, long c,
            long[] useA, long[] useB, long[] useC, long[] capacity,
            long executionLimit, BooleanSupplier spendWork) {
        PlanningCancellation.check();
        if (demand <= 0 || demand >= Sat.SAT || executionLimit <= 0 || executionLimit >= Sat.SAT
                || a <= 0 || a >= Sat.SAT || b <= 0 || b >= Sat.SAT || c <= 0 || c >= Sat.SAT
                || capacity == null || useA == null || useB == null || useC == null
                || useA.length != capacity.length || useB.length != capacity.length
                || useC.length != capacity.length) return null;
        for (int resource = 0; resource < capacity.length; resource++) {
            if ((resource & 1023) == 0) PlanningCancellation.check();
            if (capacity[resource] < 0 || useA[resource] < 0 || useB[resource] < 0 || useC[resource] < 0)
                return null;
        }
        long[] output = {a, b, c};
        long[][] use = {useA, useB, useC};
        long[][] samples = new long[3][];
        int sampleCount = 0;
        for (int pivot = 0; pivot < 3; pivot++) {
            long maximum = Math.min(executionLimit, (demand - 1) / output[pivot] + 1);
            for (int resource = 0; resource < capacity.length; resource++) {
                if ((resource & 1023) == 0) PlanningCancellation.check();
                if (use[pivot][resource] > 0)
                    maximum = Math.min(maximum, capacity[resource] / use[pivot][resource]);
            }
            samples[pivot] = samples(maximum);
            sampleCount = Math.max(sampleCount, samples[pivot].length);
        }
        var budget = new Budget(spendWork);
        Allocation best = null;
        // Interleave pivots so each gets its zero/small-count proposals before expensive endpoints.
        for (int sample = 0; sample < sampleCount; sample++) for (int pivot = 0; pivot < 3; pivot++) {
            PlanningCancellation.check();
            if (sample >= samples[pivot].length) continue;
            if (!budget.startCandidate()) return best;
            long fixed = samples[pivot][sample];
            long[] counts = new long[3];
            counts[pivot] = fixed;
            BigInteger remainingDemand = BigInteger.valueOf(demand)
                    .subtract(BigInteger.valueOf(output[pivot]).multiply(BigInteger.valueOf(fixed)));
            if (remainingDemand.signum() > 0) {
                long remainingExecutions = executionLimit - fixed;
                if (remainingExecutions <= 0) continue;
                long[] remainingCapacity = new long[capacity.length];
                for (int resource = 0; resource < capacity.length; resource++) {
                    if ((resource & 1023) == 0) PlanningCancellation.check();
                    // The sampled count is bounded by capacity/use, so this product cannot overflow.
                    remainingCapacity[resource] = capacity[resource] - use[pivot][resource] * fixed;
                }
                int first = (pivot + 1) % 3, second = (pivot + 2) % 3;
                var pair = PairBatchAllocation.solve(remainingDemand.longValueExact(), output[first], output[second],
                        use[first], use[second], remainingCapacity, remainingExecutions, budget::residueStep);
                if (budget.stopped) return best;
                if (pair == null) continue;
                counts[first] = pair.first();
                counts[second] = pair.second();
            }
            best = better(best, checked(demand, output, use, capacity, executionLimit, counts));
        }
        return best;
    }

    private static long[] samples(long maximum) {
        long[] result = new long[SMALL_COUNTS + 4];
        int size = 0;
        for (long value = 0; value <= Math.min(SMALL_COUNTS, maximum); value++) result[size++] = value;
        for (long value : new long[] {maximum, maximum - 1, maximum / 2}) {
            if (value < 0) continue;
            boolean present = false;
            for (int at = 0; at < size; at++) present |= result[at] == value;
            if (!present) result[size++] = value;
        }
        return Arrays.copyOf(result, size);
    }

    /** Do not rely on a sampled residual problem to certify the original three-recipe problem. */
    private static Allocation checked(long demand, long[] output, long[][] use, long[] capacity,
            long executionLimit, long[] counts) {
        long remainingExecutions = executionLimit;
        BigInteger produced = BigInteger.ZERO;
        for (int recipe = 0; recipe < 3; recipe++) {
            if (counts[recipe] < 0 || counts[recipe] > remainingExecutions) return null;
            remainingExecutions -= counts[recipe];
            produced = produced.add(BigInteger.valueOf(output[recipe]).multiply(BigInteger.valueOf(counts[recipe])));
        }
        if (produced.compareTo(BigInteger.valueOf(demand)) < 0) return null;
        BigInteger total = BigInteger.ZERO;
        for (int resource = 0; resource < capacity.length; resource++) {
            if ((resource & 1023) == 0) PlanningCancellation.check();
            BigInteger used = BigInteger.ZERO;
            for (int recipe = 0; recipe < 3; recipe++)
                used = used.add(BigInteger.valueOf(use[recipe][resource]).multiply(BigInteger.valueOf(counts[recipe])));
            if (used.compareTo(BigInteger.valueOf(capacity[resource])) > 0) return null;
            total = total.add(used);
        }
        return new Allocation(counts[0], counts[1], counts[2], total);
    }

    private static Allocation better(Allocation best, Allocation candidate) {
        return candidate != null && (best == null || candidate.executions() < best.executions()
                || candidate.executions() == best.executions() && candidate.consumed.compareTo(best.consumed) < 0)
                ? candidate : best;
    }

    private static final class Budget {
        private final BooleanSupplier spendWork;
        private int candidates, residues;
        private boolean stopped;

        Budget(BooleanSupplier spendWork) { this.spendWork = spendWork; }

        boolean startCandidate() {
            if (stopped || candidates >= MAX_PAIR_CALLS) { stopped = true; return false; }
            candidates++;
            if (!spendWork.getAsBoolean()) { stopped = true; return false; }
            return true;
        }

        boolean residueStep() {
            if (stopped || residues >= MAX_RESIDUE_STEPS) { stopped = true; return false; }
            residues++;
            if (!spendWork.getAsBoolean()) { stopped = true; return false; }
            return true;
        }
    }
}
