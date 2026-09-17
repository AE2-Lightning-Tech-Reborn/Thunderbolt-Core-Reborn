package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpModelProto;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatBalanceCutsTest {
    @BeforeAll static void loadNative() { CpSatBridge.initialize(); }

    @Test void positiveWeightedEliminationRoundsIntegerBoundsWithoutExcludingAnyAssignment() {
        var random = new Random(20260917L);
        int checked = 0, generated = 0;
        for (int trial = 0; trial < 100; trial++) {
            var model = new CpModel();
            IntVar[] variables = new IntVar[5];
            for (int i = 0; i < variables.length; i++) variables[i] = model.newIntVar(0, 3, "v" + i);
            // Unequal 2:3 circulation coefficients exercise positive multipliers and gcd rounding.
            // Auxiliary variables represent shortages, batch counts and activation/reserve terms.
            model.addGreaterOrEqual(LinearExpr.weightedSum(variables,
                    new long[] {2, -2, 1 + random.nextInt(3), random.nextInt(3), 0}), random.nextInt(13) - 6);
            model.addLessOrEqual(LinearExpr.weightedSum(variables,
                    new long[] {3, -3, 0, random.nextInt(3) - 2, -1 - random.nextInt(3)}), random.nextInt(13) - 6);
            CpModelProto before = model.model();
            generated += CpSatBalanceCuts.add(model, Arrays.copyOf(variables, 2),
                    new int[] {0, 0}, new int[] {-1, 1}, new int[] {0, -1});
            CpModelProto after = model.model();
            assertEquals(before.getVariablesList(), after.getVariablesList(), "no domain may change");
            assertEquals("", model.validate());
            for (int bits = 0; bits < 1 << (2 * variables.length); bits++) {
                long[] values = new long[variables.length];
                for (int i = 0; i < values.length; i++) values[i] = (bits >> (2 * i)) & 3;
                if (satisfies(before, values)) {
                    checked++;
                    assertTrue(satisfies(after, values), () -> "removed " + Arrays.toString(values));
                }
            }
        }
        assertTrue(checked > 1_000);
        assertTrue(generated > 50, "the soundness test must actually exercise added constraints");
    }

    @Test void unsafeAggregateIsOmittedAndOriginalDomainsStayIntact() {
        var model = new CpModel();
        var x = model.newIntVar(0, 1, "x");
        var a = model.newIntVar(0, 1, "a");
        var b = model.newIntVar(0, 1, "b");
        long large = Long.MAX_VALUE / 3;
        model.addGreaterOrEqual(LinearExpr.weightedSum(new IntVar[] {x, a}, new long[] {1, large}), 0);
        model.addGreaterOrEqual(LinearExpr.weightedSum(new IntVar[] {x, b}, new long[] {-1, large - 1}), 0);
        CpModelProto before = model.model();
        assertEquals(0, CpSatBalanceCuts.add(model, new IntVar[] {x}, new int[] {0, 0},
                new int[] {-1, -1}, new int[] {0, 1}));
        assertEquals(before, model.model());
        assertEquals("", model.validate());
    }

    @Test void acyclicGroupsAndConditionalConstraintsCannotCreateUnconditionalCuts() {
        var model = new CpModel();
        var x = model.newIntVar(0, 10, "x");
        var y = model.newIntVar(0, 10, "y");
        var enabled = model.newBoolVar("enabled");
        model.addGreaterOrEqual(LinearExpr.weightedSum(new IntVar[] {x, y}, new long[] {1, -1}), 1)
                .onlyEnforceIf(enabled);
        model.addGreaterOrEqual(LinearExpr.weightedSum(new IntVar[] {x, y}, new long[] {-1, 1}), 0);
        CpModelProto before = model.model();
        assertEquals(0, CpSatBalanceCuts.add(model, new IntVar[] {x, y}, new int[] {0, 0},
                new int[] {-1, -1}, new int[] {0, 1}));
        assertEquals(before, model.model());
        assertEquals(0, CpSatBalanceCuts.add(model, new IntVar[] {x, y}, new int[] {0, 1},
                new int[] {-1, -1}, new int[] {0, 1}));
        assertEquals(before, model.model());
    }

    @Test void largeCyclicGroupsHaveABoundedNumberOfAdditionalRows() {
        var model = new CpModel();
        var x = model.newIntVar(0, 1_000_000_000_000L, "x");
        int size = 256;
        int[] rows = new int[size], demands = new int[size];
        Arrays.fill(demands, -1);
        for (int i = 0; i < size; i++) {
            var shortage = model.newIntVar(0, 100, "missing" + i);
            rows[i] = model.getBuilder().getConstraintsCount();
            model.addGreaterOrEqual(LinearExpr.weightedSum(new IntVar[] {x, shortage},
                    new long[] {i % 2 == 0 ? 1 : -1, 1}), 0);
        }
        var before = model.model();
        int added = CpSatBalanceCuts.add(model, new IntVar[] {x}, new int[size], demands, rows);
        assertEquals(CpSatBalanceCuts.MAX_CUTS, added);
        assertEquals(size + added, model.model().getConstraintsCount());
        assertEquals(before.getVariablesList(), model.model().getVariablesList());
        assertEquals("", model.validate());
    }

    private static boolean satisfies(CpModelProto model, long[] values) {
        for (var constraint : model.getConstraintsList()) {
            var linear = constraint.getLinear();
            BigInteger sum = BigInteger.ZERO;
            for (int i = 0; i < linear.getVarsCount(); i++) sum = sum.add(
                    BigInteger.valueOf(values[linear.getVars(i)]).multiply(BigInteger.valueOf(linear.getCoeffs(i))));
            if (sum.compareTo(BigInteger.valueOf(linear.getDomain(0))) < 0
                    || sum.compareTo(BigInteger.valueOf(linear.getDomain(1))) > 0) return false;
        }
        return true;
    }
}
