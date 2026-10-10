package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class RationalArithmeticTest {
    @Test
    void rejectsZeroDenominatorsAndFractionalIntegerConversions() {
        assertThrows(ArithmeticException.class, () -> new ExactRational(BigInteger.ZERO, BigInteger.ZERO));
        assertThrows(ArithmeticException.class, () -> ExactRational.ONE.divide(ExactRational.ZERO));
        assertThrows(ArithmeticException.class, () -> ExactRational.ZERO.divide(ExactRational.ZERO));
        assertThrows(ArithmeticException.class, () ->
                new ExactRational(BigInteger.ONE, BigInteger.TWO).toBigIntegerExact());
    }

    @Test
    void integerAndCoprimeFastPathsMatchExactFractionArithmetic() {
        var fractions = new ArrayList<BigInteger[]>();
        for (long numerator : new long[] {-12, -3, -1, 0, 1, 3, 12})
            for (long denominator : new long[] {-6, -1, 1, 2, 3, 7})
                fractions.add(new BigInteger[] {BigInteger.valueOf(numerator), BigInteger.valueOf(denominator)});
        BigInteger wide = BigInteger.ONE.shiftLeft(160);
        fractions.add(new BigInteger[] {wide.negate().subtract(BigInteger.ONE), BigInteger.ONE});
        fractions.add(new BigInteger[] {wide.add(BigInteger.ONE), wide.subtract(BigInteger.ONE)});
        for (var a : fractions) {
            var left = new ExactRational(a[0], a[1]);
            BigInteger[] normalized = normalize(a[0], a[1]);
            assertValue(left, a[0], a[1]);
            BigInteger[] qr = normalized[0].divideAndRemainder(normalized[1]);
            BigInteger floor = qr[0].subtract(qr[1].signum() < 0 ? BigInteger.ONE : BigInteger.ZERO);
            assertEquals(floor, left.floor());
            for (var b : fractions) {
                var right = new ExactRational(b[0], b[1]);
                BigInteger ad = a[0].multiply(b[1]), bc = b[0].multiply(a[1]);
                BigInteger denominator = a[1].multiply(b[1]);
                assertValue(left.add(right), ad.add(bc), denominator);
                assertValue(left.subtract(right), ad.subtract(bc), denominator);
                assertValue(left.multiply(right), a[0].multiply(b[0]), denominator);
                if (b[0].signum() != 0)
                    assertValue(left.divide(right), a[0].multiply(b[1]), a[1].multiply(b[0]));
                int expected = ad.compareTo(bc) * denominator.signum();
                assertEquals(Integer.signum(expected), Integer.signum(left.compareTo(right)));
                assertValue(left, a[0], a[1]);
                assertValue(right, b[0], b[1]);
            }
        }
    }

    private static BigInteger[] normalize(BigInteger numerator, BigInteger denominator) {
        BigInteger gcd = numerator.gcd(denominator);
        if (denominator.signum() < 0) gcd = gcd.negate();
        return new BigInteger[] {numerator.divide(gcd), denominator.divide(gcd)};
    }

    private static void assertValue(ExactRational value, BigInteger n, BigInteger d) {
        BigInteger[] expected = normalize(n, d);
        assertEquals(expected[0], value.numerator());
        assertEquals(expected[1], value.denominator());
    }
}
