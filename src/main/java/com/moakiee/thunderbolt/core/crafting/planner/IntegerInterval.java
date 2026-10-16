package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;

/** Exact intersection of integer bounds with linear inequalities, including signed slopes. */
final class IntegerInterval {
    BigInteger low, high;

    IntegerInterval(BigInteger low, BigInteger high) { this.low = low; this.high = high; }

    boolean nonempty() { return low.compareTo(high) <= 0; }

    /** Intersect with {@code slope * x <= right}, preserving the feasible integer endpoints. */
    boolean atMost(BigInteger slope, BigInteger right) {
        if (slope.signum() == 0) {
            if (right.signum() < 0) high = low.subtract(BigInteger.ONE);
        } else {
            var qr = right.divideAndRemainder(slope);
            if (slope.signum() > 0)
                high = high.min(qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0]);
            else
                low = low.max(qr[1].signum() < 0 ? qr[0].add(BigInteger.ONE) : qr[0]);
        }
        return nonempty();
    }
}
