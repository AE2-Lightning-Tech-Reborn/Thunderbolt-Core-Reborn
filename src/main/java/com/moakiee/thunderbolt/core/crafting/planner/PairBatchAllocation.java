package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.function.BooleanSupplier;

/** Bounded two-recipe integer allocation with one independent capacity per consumed item. */
final class PairBatchAllocation {
    private static final int MAX_RESIDUES = 1024;

    record Allocation(long first, long second, BigInteger consumed) {
        long executions() { return first + second; }
    }

    private PairBatchAllocation() {}

    /**
     * Minimize executions, then consumed units, without increasing any resource draw. For
     * x = r + t * b/gcd(a,b), the smallest y covering demand is y0 - t * a/gcd(a,b).
     * Every resource and the execution limit therefore give a linear interval for t. The
     * objective is linear too, so one endpoint suffices for each residue. This is exact when
     * the smaller output divided by the gcd fits the residue cap; otherwise it still returns
     * only checked candidates. Neither the requested amount nor the firing count is iterated.
     */
    static Allocation solve(long demand, long a, long b, long[] useA, long[] useB,
            long[] capacity, long executionLimit, BooleanSupplier spendWork) {
        if (demand <= 0 || a <= 0 || b <= 0 || executionLimit <= 0) return null;
        if (a < b) {
            var reversed = solve(demand, b, a, useB, useA, capacity, executionLimit, spendWork);
            return reversed == null ? null : new Allocation(reversed.second, reversed.first, reversed.consumed);
        }
        PlanningCancellation.check();
        Allocation best = checked(Sat.ceilDiv(demand, a), 0, useA, useB, capacity, executionLimit);
        best = better(best, checked(0, Sat.ceilDiv(demand, b), useA, useB, capacity, executionLimit));
        long gcd = gcd(a, b), stepX = b / gcd, stepY = a / gcd;
        long residues = Math.min(Math.min(stepX, MAX_RESIDUES), Sat.ceilDiv(demand, a));
        var bigA = BigInteger.valueOf(a);
        var bigB = BigInteger.valueOf(b);
        var dx = BigInteger.valueOf(stepX);
        var dy = BigInteger.valueOf(stepY);
        var executionSlope = dx.subtract(dy);
        var resourceSlope = new BigInteger[capacity.length];
        BigInteger consumptionSlope = BigInteger.ZERO;
        for (int i = 0; i < capacity.length; i++) {
            resourceSlope[i] = BigInteger.valueOf(useA[i]).multiply(dx)
                    .subtract(BigInteger.valueOf(useB[i]).multiply(dy));
            consumptionSlope = consumptionSlope.add(resourceSlope[i]);
        }
        for (long r = 0; r < residues; r++) {
            PlanningCancellation.check();
            if (!spendWork.getAsBoolean()) break;
            var x0 = BigInteger.valueOf(r);
            var y0 = ceilDivide(BigInteger.valueOf(demand).subtract(bigA.multiply(x0)), bigB);
            // The all-first-recipe endpoint (y=0) was already checked above.
            var interval = new Interval(BigInteger.ZERO, y0.subtract(BigInteger.ONE).divide(dy));
            interval.constrain(x0.add(y0), executionSlope, BigInteger.valueOf(executionLimit));
            for (int i = 0; i < capacity.length && interval.nonempty(); i++) {
                var used = BigInteger.valueOf(useA[i]).multiply(x0)
                        .add(BigInteger.valueOf(useB[i]).multiply(y0));
                interval.constrain(used, resourceSlope[i], BigInteger.valueOf(capacity[i]));
            }
            if (!interval.nonempty()) continue;
            var t = executionSlope.signum() < 0 || executionSlope.signum() == 0 && consumptionSlope.signum() < 0
                    ? interval.high : interval.low;
            long first = x0.add(dx.multiply(t)).longValueExact();
            long second = y0.subtract(dy.multiply(t)).longValueExact();
            best = better(best, checked(first, second, useA, useB, capacity, executionLimit));
        }
        return best;
    }

    private static Allocation checked(long first, long second, long[] useA, long[] useB,
            long[] capacity, long executionLimit) {
        if (first < 0 || second < 0 || first > executionLimit || second > executionLimit - first) return null;
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < capacity.length; i++) {
            var consumed = BigInteger.valueOf(useA[i]).multiply(BigInteger.valueOf(first))
                    .add(BigInteger.valueOf(useB[i]).multiply(BigInteger.valueOf(second)));
            if (consumed.compareTo(BigInteger.valueOf(capacity[i])) > 0) return null;
            total = total.add(consumed);
        }
        return new Allocation(first, second, total);
    }

    private static Allocation better(Allocation best, Allocation candidate) {
        return candidate != null && (best == null || candidate.executions() < best.executions()
                || candidate.executions() == best.executions() && candidate.consumed.compareTo(best.consumed) < 0)
                ? candidate : best;
    }

    private static long gcd(long a, long b) {
        while (b != 0) { long next = a % b; a = b; b = next; }
        return a;
    }

    private static BigInteger ceilDivide(BigInteger value, BigInteger positiveDivisor) {
        var qr = value.divideAndRemainder(positiveDivisor);
        return qr[1].signum() > 0 ? qr[0].add(BigInteger.ONE) : qr[0];
    }

    private static final class Interval {
        BigInteger low, high;

        Interval(BigInteger low, BigInteger high) { this.low = low; this.high = high; }
        boolean nonempty() { return low.compareTo(high) <= 0; }

        void constrain(BigInteger intercept, BigInteger slope, BigInteger maximum) {
            var slack = maximum.subtract(intercept);
            if (slope.signum() > 0) {
                var qr = slack.divideAndRemainder(slope);
                var upper = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
                high = high.min(upper);
            } else if (slope.signum() < 0) {
                low = low.max(ceilDivide(slack.negate(), slope.negate()));
            } else if (slack.signum() < 0) {
                high = BigInteger.valueOf(-1);
            }
        }
    }
}
