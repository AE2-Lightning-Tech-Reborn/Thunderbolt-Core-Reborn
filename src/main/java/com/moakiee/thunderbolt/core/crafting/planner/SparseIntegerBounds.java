package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact integer-domain presolve. Only nonzero incidences take part in propagation. */
final class SparseIntegerBounds {
    enum Status { REDUCED, INFEASIBLE, BUDGET_EXHAUSTED }

    record Row(int[] variables, BigInteger[] coefficients, BigInteger minimum) { }
    record Result(Status status, List<Row> rows, long[] lower, long[] upper, long work) { }
    private record Incidence(int row, BigInteger coefficient) { }

    private SparseIntegerBounds() { }

    static Result reduce(int variables, List<BoundedIntegerLinearSolver.Constraint> constraints,
            long maximum, BoundedIntegerLinearSolver.WorkBudget budget) {
        long[] lower = new long[variables];
        long[] upper = new long[variables];
        Arrays.fill(upper, maximum);
        var rows = new ArrayList<Row>(constraints.size());
        var incident = new ArrayList<List<Incidence>>(variables);
        for (int variable = 0; variable < variables; variable++) incident.add(new ArrayList<>());
        long nonzeros = 0;
        for (var constraint : constraints) {
            long[] coefficients = constraint.coefficients();
            if (!budget.tryConsume(coefficients.length))
                return new Result(Status.BUDGET_EXHAUSTED, rows, lower, upper, 0);
            int count = 0;
            BigInteger gcd = BigInteger.ZERO;
            for (long coefficient : coefficients) {
                if (coefficient != 0) {
                    count++;
                    gcd = gcd.gcd(BigInteger.valueOf(coefficient));
                }
            }
            if (count == 0) {
                if (constraint.minimum() > 0)
                    return new Result(Status.INFEASIBLE, rows, lower, upper, 0);
                continue;
            }
            int[] indices = new int[count];
            BigInteger[] values = new BigInteger[count];
            int next = 0;
            for (int variable = 0; variable < variables; variable++) {
                if (coefficients[variable] == 0) continue;
                indices[next] = variable;
                values[next] = BigInteger.valueOf(coefficients[variable]).divide(gcd);
                incident.get(variable).add(new Incidence(rows.size(), values[next]));
                next++;
            }
            rows.add(new Row(indices, values, ceilDivide(BigInteger.valueOf(constraint.minimum()), gcd)));
            nonzeros += count;
        }

        BigInteger[] activity = new BigInteger[rows.size()];
        var pending = new ArrayDeque<Integer>();
        boolean[] queued = new boolean[rows.size()];
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (!budget.tryConsume(row.variables.length))
                return new Result(Status.BUDGET_EXHAUSTED, rows, lower, upper, 0);
            BigInteger value = BigInteger.ZERO;
            for (int term = 0; term < row.variables.length; term++) {
                if (row.coefficients[term].signum() > 0)
                    value = value.add(row.coefficients[term].multiply(BigInteger.valueOf(maximum)));
            }
            activity[index] = value;
            pending.addLast(index);
            queued[index] = true;
        }

        // Bounds in a cycle can improve one unit at a time. A structural work cap prevents that
        // optional presolve from becoming pseudo-polynomial; partial reductions are still valid.
        long limit = Math.max(256L, Math.min(65_536L, 16L * nonzeros));
        long work = 0;
        propagation:
        while (!pending.isEmpty()) {
            int index = pending.removeFirst();
            queued[index] = false;
            Row row = rows.get(index);
            if (!budget.tryConsume(row.variables.length))
                return new Result(Status.BUDGET_EXHAUSTED, rows, lower, upper, work);
            if ((work += row.variables.length) > limit) break;
            BigInteger slack = activity[index].subtract(row.minimum);
            if (slack.signum() < 0)
                return new Result(Status.INFEASIBLE, rows, lower, upper, work);
            for (int term = 0; term < row.variables.length; term++) {
                int variable = row.variables[term];
                BigInteger coefficient = row.coefficients[term];
                BigInteger movement = slack.divide(coefficient.abs());
                long width = upper[variable] - lower[variable];
                if (movement.compareTo(BigInteger.valueOf(width)) >= 0) continue;
                long distance = width - movement.longValueExact();
                List<Incidence> affected = incident.get(variable);
                if (work + affected.size() > limit) break propagation;
                if (!budget.tryConsume(affected.size()))
                    return new Result(Status.BUDGET_EXHAUSTED, rows, lower, upper, work);
                work += affected.size();
                boolean raiseLower = coefficient.signum() > 0;
                if (raiseLower) lower[variable] += distance;
                else upper[variable] -= distance;
                for (Incidence arc : affected) {
                    // Raising a lower bound changes a row's maximum only for a negative term;
                    // lowering an upper bound changes it only for a positive term.
                    if ((arc.coefficient.signum() < 0) != raiseLower) continue;
                    activity[arc.row] = activity[arc.row].subtract(
                            arc.coefficient.abs().multiply(BigInteger.valueOf(distance)));
                    if (!queued[arc.row]) {
                        pending.addLast(arc.row);
                        queued[arc.row] = true;
                    }
                }
            }
        }
        return new Result(Status.REDUCED, List.copyOf(rows), lower, upper, work);
    }

    private static BigInteger ceilDivide(BigInteger value, BigInteger positive) {
        BigInteger[] division = value.divideAndRemainder(positive);
        return division[1].signum() > 0 ? division[0].add(BigInteger.ONE) : division[0];
    }
}
