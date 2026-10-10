package com.moakiee.thunderbolt.core.crafting.planner;

/**
 * Non-negative {@code long} arithmetic clamped to {@link #SAT} to prevent amount overflow.
 */
public final class Sat {

    /** Sentinel for "effectively infinite". Kept well below {@link Long#MAX_VALUE} so sums stay finite. */
    public static final long SAT = Long.MAX_VALUE / 4;

    private Sat() {
    }

    public static boolean isSaturated(long value) {
        return value >= SAT;
    }

    public static long add(long a, long b) {
        long r = a + b;
        return (r >= SAT || r < 0) ? SAT : r;
    }

    public static long mul(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        if (a >= SAT || b >= SAT || a > SAT / b) {
            return SAT;
        }
        long r = a * b;
        return (r >= SAT || r < 0) ? SAT : r;
    }

    /** Ceiling division for non-negative inputs; saturates. {@code div} must be {@code > 0}. */
    public static long ceilDiv(long value, long div) {
        if (value >= SAT) {
            return SAT;
        }
        if (value == 0) {
            return 0;
        }
        // Avoid adding the divisor to a near-limit amount.
        return (value - 1) / div + 1;
    }
}
