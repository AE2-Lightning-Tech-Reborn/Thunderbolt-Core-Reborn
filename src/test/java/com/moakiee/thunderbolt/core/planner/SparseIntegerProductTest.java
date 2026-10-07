package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Random;

import org.junit.jupiter.api.Test;

class SparseIntegerProductTest {
    @Test
    void signedBoundaryProductsAndWideCoefficientsMatchBigInteger() {
        long[] values = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -4_294_967_296L, -3_037_000_500L,
                -3, -2, -1, 0, 1, 2, 3, 3_037_000_499L, 3_037_000_500L, 4_294_967_296L,
                Sat.SAT, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        var coefficients = new ArrayList<BigInteger>();
        for (long value : values) coefficients.add(BigInteger.valueOf(value));
        BigInteger beyondLong = BigInteger.ONE.shiftLeft(63);
        coefficients.add(beyondLong);
        coefficients.add(beyondLong.negate().subtract(BigInteger.ONE));
        coefficients.add(BigInteger.ONE.shiftLeft(160).add(BigInteger.valueOf(7)));
        coefficients.add(BigInteger.ONE.shiftLeft(160).negate().subtract(BigInteger.valueOf(7)));
        for (BigInteger coefficient : coefficients) for (long value : values) {
            assertEquals(coefficient.multiply(BigInteger.valueOf(value)),
                    SparseIntegerBounds.product(coefficient, value),
                    () -> "coefficient=" + coefficient + " value=" + value);
        }
        var random = new Random(2026100609L);
        for (int sample = 0; sample < 4096; sample++) {
            BigInteger coefficient = sample % 3 == 0 ? BigInteger.valueOf(random.nextLong())
                    : new BigInteger(1 + random.nextInt(192), random);
            if (random.nextBoolean()) coefficient = coefficient.negate();
            long value = random.nextLong();
            assertEquals(coefficient.multiply(BigInteger.valueOf(value)),
                    SparseIntegerBounds.product(coefficient, value));
        }
    }
}
