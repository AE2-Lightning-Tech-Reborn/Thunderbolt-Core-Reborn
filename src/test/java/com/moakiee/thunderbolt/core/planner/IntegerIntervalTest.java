package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class IntegerIntervalTest {
    @Test
    void signedBoundsAndTheirIntersectionsMatchIndependentIntegerEnumeration() {
        for (int a = -7; a <= 7; a++) for (int b = -7; b <= 7; b++)
            for (int c = -7; c <= 7; c++) for (int d = -7; d <= 7; d++) {
                var interval = new IntegerInterval(BigInteger.valueOf(-9), BigInteger.valueOf(9));
                interval.atMost(BigInteger.valueOf(a), BigInteger.valueOf(b));
                boolean remains = interval.atMost(BigInteger.valueOf(c), BigInteger.valueOf(d));
                int first = 10, last = -10;
                for (int x = -9; x <= 9; x++) if (a * x <= b && c * x <= d) {
                    first = Math.min(first, x);
                    last = x;
                }
                assertEquals(first <= last, remains);
                if (remains) {
                    assertEquals(BigInteger.valueOf(first), interval.low);
                    assertEquals(BigInteger.valueOf(last), interval.high);
                }
            }
    }

    @Test
    void coefficientsBeyondLongRangeStillRoundToExactEndpoints() {
        var huge = BigInteger.ONE.shiftLeft(160);
        var interval = new IntegerInterval(huge.negate(), huge);
        assertTrue(interval.atMost(huge, huge.multiply(BigInteger.valueOf(17)).add(BigInteger.ONE)));
        assertTrue(interval.atMost(huge.negate(), huge.multiply(BigInteger.valueOf(-13)).subtract(BigInteger.ONE)));
        assertEquals(BigInteger.valueOf(14), interval.low);
        assertEquals(BigInteger.valueOf(17), interval.high);
        assertFalse(interval.atMost(BigInteger.ZERO, BigInteger.ONE.negate()));
        assertFalse(interval.atMost(BigInteger.ZERO, huge), "an empty intersection cannot become nonempty");
    }
}
