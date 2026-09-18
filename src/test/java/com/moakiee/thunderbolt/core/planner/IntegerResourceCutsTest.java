package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IntegerResourceCutsTest {
    @Test
    void everyEnumeratedOriginalIntegerSolutionSatisfiesTheWeightedCut() {
        Random random = new Random(0xC07554188L);
        for (int sample = 0; sample < 256; sample++) {
            int variables = 1 + random.nextInt(4), count = 1 + random.nextInt(5);
            var rows = new ArrayList<BoundedIntegerLinearSolver.Constraint>();
            BigInteger[] weights = new BigInteger[count];
            for (int r = 0; r < count; r++) {
                long[] coefficients = new long[variables];
                for (int v = 0; v < variables; v++) coefficients[v] = random.nextInt(11) - 5;
                rows.add(new BoundedIntegerLinearSolver.Constraint(coefficients, random.nextInt(31) - 15));
                weights[r] = BigInteger.valueOf(random.nextInt(10));
            }
            var cut = IntegerResourceCuts.weightedSum(rows, weights);
            if (cut == null) continue;
            int assignments = 1 << (2 * variables);
            for (int assignment = 0; assignment < assignments; assignment++) {
                long[] values = new long[variables];
                for (int v = 0; v < variables; v++) values[v] = (assignment >> (2 * v)) & 3;
                if (rows.stream().allMatch(row -> satisfied(row, values)))
                    assertTrue(satisfied(cut, values), "sample=" + sample + " assignment=" + assignment);
            }
        }
    }

    @Test
    void cancellationAndGcdRoundingAreExactBeyondLongIntermediateProducts() {
        var rows = List.of(row(1, Long.MAX_VALUE, 2), row(1, -Long.MAX_VALUE, 2));
        var big = BigInteger.valueOf(Long.MAX_VALUE);
        var cut = IntegerResourceCuts.weightedSum(rows, new BigInteger[]{big, big});
        assertNotNull(cut);
        assertArrayEquals(new long[]{0, 1}, cut.coefficients());
        assertEquals(1, cut.minimum());
    }

    @Test
    void unsafeOrUnhelpfulCombinationsAreOnlyOmitted() {
        assertNull(IntegerResourceCuts.weightedSum(List.of(row(0, 1)),
                new BigInteger[]{BigInteger.ONE.negate()}));
        assertNull(IntegerResourceCuts.weightedSum(List.of(row(0, Long.MAX_VALUE, 1), row(0, 1, 0)),
                new BigInteger[]{BigInteger.TWO, BigInteger.ONE}));
        assertNull(IntegerResourceCuts.weightedSum(List.of(row(0, 1), row(0, -1)),
                new BigInteger[]{BigInteger.ONE, BigInteger.ONE}));
        var impossible = IntegerResourceCuts.weightedSum(List.of(row(1, 1), row(0, -1)),
                new BigInteger[]{BigInteger.ONE, BigInteger.ONE});
        assertNotNull(impossible);
        assertArrayEquals(new long[]{0}, impossible.coefficients());
        assertEquals(1, impossible.minimum());
    }

    private static boolean satisfied(BoundedIntegerLinearSolver.Constraint row, long[] values) {
        long sum = 0;
        long[] coefficients = row.coefficients();
        for (int i = 0; i < values.length; i++) sum += coefficients[i] * values[i];
        return sum >= row.minimum();
    }
    private static BoundedIntegerLinearSolver.Constraint row(long minimum, long... coefficients) {
        return new BoundedIntegerLinearSolver.Constraint(coefficients, minimum);
    }
}
