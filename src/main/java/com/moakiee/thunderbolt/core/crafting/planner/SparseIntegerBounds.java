package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact integer-domain presolve. Only nonzero incidences take part in propagation. */
final class SparseIntegerBounds {
    private static final BigInteger NEGATIVE_ONE = BigInteger.ONE.negate();
    enum Status { REDUCED, INFEASIBLE, BUDGET_EXHAUSTED }

    record Row(int[] variables, BigInteger[] coefficients, BigInteger minimum) { }
    record Result(Status status, List<Row> rows, long[] lower, long[] upper, long work) { }

    private SparseIntegerBounds() { }

    static Result reduce(int variables, List<BoundedIntegerLinearSolver.Constraint> constraints,
            long maximum, BoundedIntegerLinearSolver.WorkBudget budget) {
        long[] lower = new long[variables];
        long[] upper = new long[variables];
        Arrays.fill(upper, maximum);
        var rows = new ArrayList<Row>(constraints.size());
        int[] incidentOffsets = new int[variables + 1];
        long nonzeros = 0;
        for (var constraint : constraints) {
            int coefficientCount = constraint.coefficientCount();
            if (!budget.tryConsume(coefficientCount))
                return new Result(Status.BUDGET_EXHAUSTED, rows, lower, upper, 0);
            int count = 0;
            BigInteger gcd = BigInteger.ZERO;
            for (int variable = 0; variable < coefficientCount; variable++) {
                long coefficient = constraint.coefficientAt(variable);
                if (coefficient != 0) {
                    count++;
                    // Once the gcd is one, later terms cannot change the normalization.
                    if (!gcd.equals(BigInteger.ONE)) gcd = gcd.gcd(BigInteger.valueOf(coefficient));
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
                long term = constraint.coefficientAt(variable);
                if (term == 0) continue;
                indices[next] = variable;
                BigInteger coefficient = BigInteger.valueOf(term);
                values[next] = gcd.equals(BigInteger.ONE) ? coefficient : coefficient.divide(gcd);
                incidentOffsets[variable + 1]++;
                next++;
            }
            rows.add(new Row(indices, values, ceilDivide(BigInteger.valueOf(constraint.minimum()), gcd)));
            nonzeros += count;
        }

        // Store variable-to-row incidences contiguously, in the same row/term insertion order.
        // Each packed entry identifies an existing normalized coefficient; no per-term object
        // or duplicate BigInteger is needed. All propagation and work debits remain unchanged.
        for (int variable = 0; variable < variables; variable++) {
            if ((variable & 255) == 0) PlanningCancellation.check();
            incidentOffsets[variable + 1] = Math.addExact(
                    incidentOffsets[variable + 1], incidentOffsets[variable]);
        }
        int[] incidentRows = new int[Math.toIntExact(nonzeros)];
        BigInteger[] incidentCoefficients = new BigInteger[Math.toIntExact(nonzeros)];
        int[] cursors = Arrays.copyOf(incidentOffsets, variables);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            PlanningCancellation.check();
            Row row = rows.get(rowIndex);
            for (int term = 0; term < row.variables.length; term++) {
                if ((term & 255) == 0) PlanningCancellation.check();
                int slot = cursors[row.variables[term]]++;
                incidentRows[slot] = rowIndex;
                incidentCoefficients[slot] = row.coefficients[term];
            }
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
                    value = value.add(product(row.coefficients[term], maximum));
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
                BigInteger magnitude = coefficient.abs();
                BigInteger movement = magnitude.equals(BigInteger.ONE) ? slack : slack.divide(magnitude);
                long width = upper[variable] - lower[variable];
                if (movement.compareTo(BigInteger.valueOf(width)) >= 0) continue;
                long distance = width - movement.longValueExact();
                int affectedStart = incidentOffsets[variable];
                int affectedEnd = incidentOffsets[variable + 1];
                int affectedCount = affectedEnd - affectedStart;
                if (work + affectedCount > limit) break propagation;
                if (!budget.tryConsume(affectedCount))
                    return new Result(Status.BUDGET_EXHAUSTED, rows, lower, upper, work);
                work += affectedCount;
                boolean raiseLower = coefficient.signum() > 0;
                if (raiseLower) lower[variable] += distance;
                else upper[variable] -= distance;
                for (int arcIndex = affectedStart; arcIndex < affectedEnd; arcIndex++) {
                    int affectedRow = incidentRows[arcIndex];
                    BigInteger affectedCoefficient = incidentCoefficients[arcIndex];
                    // Raising a lower bound changes a row's maximum only for a negative term;
                    // lowering an upper bound changes it only for a positive term.
                    if ((affectedCoefficient.signum() < 0) != raiseLower) continue;
                    activity[affectedRow] = activity[affectedRow].subtract(
                            product(affectedCoefficient.abs(), distance));
                    if (!queued[affectedRow]) {
                        pending.addLast(affectedRow);
                        queued[affectedRow] = true;
                    }
                }
            }
        }
        return new Result(Status.REDUCED, List.copyOf(rows), lower, upper, work);
    }

    private static BigInteger ceilDivide(BigInteger value, BigInteger positive) {
        if (positive.equals(BigInteger.ONE)) return value;
        BigInteger[] division = value.divideAndRemainder(positive);
        return division[1].signum() > 0 ? division[0].add(BigInteger.ONE) : division[0];
    }

    /** Use a primitive product only when its high half proves there was no signed overflow. */
    static BigInteger product(BigInteger coefficient, long value) {
        if (value == 0 || coefficient.signum() == 0) return BigInteger.ZERO;
        if (value == 1) return coefficient;
        if (coefficient.equals(BigInteger.ONE)) return BigInteger.valueOf(value);
        if (coefficient.equals(NEGATIVE_ONE)) return BigInteger.valueOf(value).negate();
        if (coefficient.bitLength() <= 63) {
            long factor = coefficient.longValue();
            long result = factor * value;
            if (Math.multiplyHigh(factor, value) == (result >> 63)) return BigInteger.valueOf(result);
        }
        return coefficient.multiply(BigInteger.valueOf(value));
    }
}
