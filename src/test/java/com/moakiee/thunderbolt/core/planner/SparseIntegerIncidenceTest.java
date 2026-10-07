package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Random;

import org.junit.jupiter.api.Test;

class SparseIntegerIncidenceTest {
    @Test
    void sparseColumnsAndDiscardedConstantRowsPreserveEveryFiniteDomainWitness() {
        var random = new Random(2026100607L);
        int[] active = {0, 2, 6, 7};
        for (int sample = 0; sample < 128; sample++) {
            var constraints = new ArrayList<BoundedIntegerLinearSolver.Constraint>();
            constraints.add(new BoundedIntegerLinearSolver.Constraint(new long[8], -3));
            for (int row = 0; row < 5; row++) {
                long[] coefficients = new long[8];
                for (int column : active) coefficients[column] = random.nextInt(7) - 3;
                constraints.add(new BoundedIntegerLinearSolver.Constraint(coefficients, random.nextInt(17) - 8));
                if (row == 2) constraints.add(new BoundedIntegerLinearSolver.Constraint(new long[8], 0));
            }
            var reduced = SparseIntegerBounds.reduce(8, constraints, 4,
                    BoundedIntegerLinearSolver.WorkBudget.unlimited());
            for (int column : new int[] {1, 3, 4, 5}) {
                assertEquals(0, reduced.lower()[column]);
                assertEquals(4, reduced.upper()[column]);
            }
            int witnesses = 0;
            for (int encoding = 0; encoding < 625; encoding++) {
                long[] values = new long[8];
                int remaining = encoding;
                for (int column : active) {
                    values[column] = remaining % 5;
                    remaining /= 5;
                }
                boolean feasible = true;
                for (var constraint : constraints) {
                    long[] coefficients = constraint.coefficients();
                    BigInteger activity = BigInteger.ZERO;
                    for (int column : active) activity = activity.add(BigInteger.valueOf(coefficients[column])
                            .multiply(BigInteger.valueOf(values[column])));
                    if (activity.compareTo(BigInteger.valueOf(constraint.minimum())) < 0) {
                        feasible = false;
                        break;
                    }
                }
                if (!feasible) continue;
                witnesses++;
                assertEquals(SparseIntegerBounds.Status.REDUCED, reduced.status(), "sample=" + sample);
                for (int column : active) assertTrue(values[column] >= reduced.lower()[column]
                        && values[column] <= reduced.upper()[column], "sample=" + sample + " column=" + column);
            }
            if (reduced.status() == SparseIntegerBounds.Status.INFEASIBLE) assertEquals(0, witnesses);
        }
    }
}
