package com.moakiee.thunderbolt.core.storage.big;

import java.math.BigInteger;

/**
 * Shared exact numeric boundaries; saturation is restricted to the legacy display/transfer facade.
 */
public final class BigAmounts {
    public static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    public static final int MAX_BITS = 16384;

    private BigAmounts() {}

    public static BigInteger nonNegative(BigInteger n) {
        if (n == null || n.signum() < 0 || n.bitLength() > MAX_BITS)
            throw new IllegalArgumentException("Invalid exact quantity");
        return n;
    }

    public static long project(BigInteger n) {
        return nonNegative(n).min(LONG_MAX).longValueExact();
    }

    public static BigInteger parse(String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > 5000
                || !value.chars().allMatch(Character::isDigit))
            throw new IllegalArgumentException("Use a non-negative integer");
        return nonNegative(new BigInteger(value));
    }
}
