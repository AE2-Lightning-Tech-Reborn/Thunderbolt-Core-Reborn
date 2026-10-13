package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class RationalArithmeticTest {
    @Test
    void integerAndCoprimeFastPathsMatchExactFractionArithmetic() throws Exception {
        var api = new RationalAccess();
        var fractions = new ArrayList<BigInteger[]>();
        for (long numerator : new long[] {-12, -3, -1, 0, 1, 3, 12})
            for (long denominator : new long[] {-6, -1, 1, 2, 3, 7})
                fractions.add(new BigInteger[] {BigInteger.valueOf(numerator), BigInteger.valueOf(denominator)});
        BigInteger wide = BigInteger.ONE.shiftLeft(160);
        fractions.add(new BigInteger[] {wide.negate().subtract(BigInteger.ONE), BigInteger.ONE});
        fractions.add(new BigInteger[] {wide.add(BigInteger.ONE), wide.subtract(BigInteger.ONE)});
        for (var a : fractions) {
            Object left = api.create(a[0], a[1]);
            BigInteger[] normalized = normalize(a[0], a[1]);
            api.assertValue(left, a[0], a[1]);
            BigInteger[] qr = normalized[0].divideAndRemainder(normalized[1]);
            BigInteger floor = qr[0].subtract(qr[1].signum() < 0 ? BigInteger.ONE : BigInteger.ZERO);
            assertEquals(floor, api.floor.invoke(left));
            for (var b : fractions) {
                Object right = api.create(b[0], b[1]);
                BigInteger ad = a[0].multiply(b[1]), bc = b[0].multiply(a[1]);
                BigInteger denominator = a[1].multiply(b[1]);
                api.assertValue(api.add.invoke(left, right), ad.add(bc), denominator);
                api.assertValue(api.subtract.invoke(left, right), ad.subtract(bc), denominator);
                api.assertValue(api.multiply.invoke(left, right), a[0].multiply(b[0]), denominator);
                if (b[0].signum() != 0)
                    api.assertValue(api.divide.invoke(left, right), a[0].multiply(b[1]), a[1].multiply(b[0]));
                int expected = ad.compareTo(bc) * denominator.signum();
                assertEquals(Integer.signum(expected), Integer.signum((int) api.compare.invoke(left, right)));
                api.assertValue(left, a[0], a[1]);
                api.assertValue(right, b[0], b[1]);
            }
        }
    }

    private static BigInteger[] normalize(BigInteger numerator, BigInteger denominator) {
        BigInteger gcd = numerator.gcd(denominator);
        if (denominator.signum() < 0) gcd = gcd.negate();
        return new BigInteger[] {numerator.divide(gcd), denominator.divide(gcd)};
    }

    private static final class RationalAccess {
        final Class<?> type = Class.forName(BoundedIntegerLinearSolver.class.getName() + "$Rational");
        final Constructor<?> constructor = type.getDeclaredConstructor(BigInteger.class, BigInteger.class);
        final Field numerator = type.getDeclaredField("numerator"), denominator = type.getDeclaredField("denominator");
        final Method add = binary("add"), subtract = binary("subtract"), multiply = binary("multiply"),
                divide = binary("divide"), compare = binary("compareTo"), floor = type.getDeclaredMethod("floor");

        RationalAccess() throws Exception {
            constructor.setAccessible(true);
            numerator.setAccessible(true);
            denominator.setAccessible(true);
            floor.setAccessible(true);
        }

        private Method binary(String name) throws Exception {
            Method method = type.getDeclaredMethod(name, type);
            method.setAccessible(true);
            return method;
        }

        Object create(BigInteger n, BigInteger d) throws Exception { return constructor.newInstance(n, d); }

        void assertValue(Object value, BigInteger n, BigInteger d) throws Exception {
            BigInteger[] expected = normalize(n, d);
            assertEquals(expected[0], numerator.get(value));
            assertEquals(expected[1], denominator.get(value));
        }
    }
}
