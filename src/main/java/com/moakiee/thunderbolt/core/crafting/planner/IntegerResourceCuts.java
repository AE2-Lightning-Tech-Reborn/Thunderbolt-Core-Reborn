package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Redundant integer resource bounds obtained only by nonnegative combinations of existing rows. */
final class IntegerResourceCuts {
    private IntegerResourceCuts() { }

    static BoundedIntegerLinearSolver.Constraint weightedSum(
            List<BoundedIntegerLinearSolver.Constraint> rows, BigInteger[] weights) {
        if (rows.isEmpty() || rows.size() != weights.length) return null;
        int variables = rows.get(0).coefficients().length;
        BigInteger[] sum = new BigInteger[variables];
        Arrays.fill(sum, BigInteger.ZERO);
        BigInteger minimum = BigInteger.ZERO;
        for (int row = 0; row < rows.size(); row++) {
            PlanningCancellation.check();
            if (weights[row] == null || weights[row].signum() < 0) return null;
            if (weights[row].signum() == 0) continue;
            long[] coefficients = rows.get(row).coefficients();
            if (coefficients.length != variables) return null;
            for (int variable = 0; variable < variables; variable++) {
                if (coefficients[variable] != 0) sum[variable] = sum[variable].add(
                        weights[row].multiply(BigInteger.valueOf(coefficients[variable])));
            }
            minimum = minimum.add(weights[row].multiply(BigInteger.valueOf(rows.get(row).minimum())));
        }
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : sum) gcd = gcd.gcd(coefficient);
        long[] coefficients = new long[variables];
        if (gcd.signum() == 0) {
            // A constant contradiction is represented exactly; a tautology adds nothing.
            return minimum.signum() > 0 ? new BoundedIntegerLinearSolver.Constraint(coefficients, 1) : null;
        }
        try {
            for (int variable = 0; variable < variables; variable++)
                coefficients[variable] = sum[variable].divide(gcd).longValueExact();
            BigInteger[] division = minimum.divideAndRemainder(gcd);
            BigInteger rounded = division[1].signum() > 0 ? division[0].add(BigInteger.ONE) : division[0];
            return new BoundedIntegerLinearSolver.Constraint(coefficients, rounded.longValueExact());
        } catch (ArithmeticException overflow) {
            return null; // Omitting an unrepresentable redundant row never removes a solution.
        }
    }
}
