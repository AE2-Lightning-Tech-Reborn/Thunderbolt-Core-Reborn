package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class BoundedIntegerLinearSolverTest {

    @Test
    void aSharedResourceRowReplacesItsRedundantIndividualBounds() {
        long[] demand = new long[12], resource = new long[12];
        java.util.Arrays.fill(demand, 1);
        java.util.Arrays.fill(resource, -1);
        var constraints = List.of(row(6, demand), row(-6, resource));
        // With twelve duplicate bound rows the root tableau needs 392 cells. Keeping the
        // original shared resource row alone is equivalent and fits this unchanged small cap.
        var result = BoundedIntegerLinearSolver.solve(12, constraints, 100, 16,
                BoundedIntegerLinearSolver.WorkBudget.bounded(100, 100_000, Long.MAX_VALUE));
        assertTrue(result.solved(), result.status().toString());
        assertTrue(feasible(result.values(), constraints));
        assertEquals(6, java.util.Arrays.stream(result.values()).sum());
        var limited = new java.util.ArrayList<>(constraints);
        long[] first = new long[12];
        first[0] = -1;
        limited.add(row(-5, first));
        result = BoundedIntegerLinearSolver.solve(12, limited, 100, 16,
                BoundedIntegerLinearSolver.WorkBudget.bounded(100, 100_000, Long.MAX_VALUE));
        assertTrue(result.solved());
        assertTrue(feasible(result.values(), limited));
        assertTrue(result.values()[0] <= 5);
    }

    @Test
    void strongerDomainsAndResourceRowsDiscardedByBoxPresolveRemainEnforced() {
        var domain = List.of(row(3, 1, 1), row(-10, -1, -1));
        var result = BoundedIntegerLinearSolver.solve(2, domain, 2, 16);
        assertTrue(result.solved());
        assertTrue(feasible(result.values(), domain));
        for (long value : result.values()) assertTrue(value >= 0 && value <= 2);

        var resource = List.of(row(7, 1, 1), row(-5, -1, 0), row(-5, 0, -1));
        result = BoundedIntegerLinearSolver.solve(2, resource, 100, 16);
        assertTrue(result.solved());
        assertTrue(feasible(result.values(), resource));
        for (long value : result.values()) assertTrue(value <= 5);

        var shifted = List.of(row(3, 1, 0), row(2, 0, 1), row(8, 1, 1), row(-8, -1, -1));
        result = BoundedIntegerLinearSolver.solve(2, shifted, 100, 16);
        assertTrue(result.solved());
        assertTrue(feasible(result.values(), shifted));
    }

    @Test
    void compactResourceModelsMatchExhaustiveFiniteDomains() {
        var random = new Random(2026092314L);
        int decided = 0;
        for (int sample = 0; sample < 512; sample++) {
            int variables = 5, max = 4;
            var constraints = new java.util.ArrayList<BoundedIntegerLinearSolver.Constraint>();
            long[] resource = new long[variables];
            for (int i = 0; i < variables; i++) resource[i] = -1-random.nextInt(3);
            constraints.add(row(-1-random.nextInt(12), resource));
            for (int r = 0; r < 3; r++) {
                long[] coefficients = new long[variables];
                for (int i = 0; i < variables; i++) coefficients[i] = random.nextInt(5)-2;
                constraints.add(row(random.nextInt(17)-8, coefficients));
            }
            var witness = bruteForce(variables, max, constraints);
            var result = BoundedIntegerLinearSolver.solve(variables, constraints, max, 64,
                    BoundedIntegerLinearSolver.WorkBudget.bounded(120, 1_000_000, Long.MAX_VALUE));
            if (result.status() == BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED) continue;
            decided++;
            assertEquals(witness != null, result.solved(), "sample="+sample+" status="+result.status());
            if (result.solved()) {
                assertTrue(feasible(result.values(), constraints));
                for (long value : result.values()) assertTrue(value >= 0 && value <= max);
            } else assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status());
        }
        assertTrue(decided > 400, "finite-domain oracle must exercise decided results");
    }

    @Test
    void solvesFixedLongScaleWithoutEnumeratingQuantity() {
        long requested = 1_000_000_000_000L;
        var result = BoundedIntegerLinearSolver.solve(2, List.of(
                row(requested, 1, 1),
                row(-requested, -1, -1),
                row(requested / 3, 1, 0)), Sat.SAT, 16);

        assertTrue(result.solved(), () -> "status=" + result.status());
        assertTrue(result.values()[0] >= requested / 3);
        assertEquals(requested, result.values()[0] + result.values()[1]);
        assertEquals(1, result.visitedNodes());
    }

    @Test
    void branchesOnFractionalRelaxationAndProvesParityInfeasible() {
        var result = BoundedIntegerLinearSolver.solve(1, List.of(
                row(3, 2),
                row(-3, -2)), Sat.SAT, 16);

        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status());
        assertTrue(result.visitedNodes() <= 3);
    }

    @Test
    void reportsBudgetInsteadOfEnumeratingIntegerDomain() {
        // No row has a gcd contradiction. The LP has x=y=z=1/2, but an integer proof
        // requires branching after bound propagation has reduced every domain to [0,1].
        var result = BoundedIntegerLinearSolver.solve(3, List.of(
                row(1, 1, 1, 0), row(-1, -1, -1, 0),
                row(1, 1, 0, 1), row(-1, -1, 0, -1),
                row(1, 0, 1, 1), row(-1, 0, -1, -1)), Sat.SAT, 1);

        assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, result.status());
        assertEquals(1, result.visitedNodes());
    }

    @Test
    void rejectsDenseRelaxationBeforeAllocatingOrSearchingIt() {
        var budget = BoundedIntegerLinearSolver.WorkBudget.bounded(
                32,
                1_024,
                1_000_000_000L);
        var result = BoundedIntegerLinearSolver.solve(
                4,
                List.of(
                        row(1, 1, 1, 0, 0),
                        row(-1, -1, -1, 0, 0),
                        row(1, 0, 0, 1, 1),
                        row(-1, 0, 0, -1, -1)),
                Sat.SAT,
                64,
                budget);

        assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, result.status());
        assertEquals(0, result.visitedNodes(),
                "tableau-shape admission must run before branch-and-bound");
    }

    @Test
    void randomSmallSignedSystemsMatchBruteForce() {
        Random random = new Random(0x5EEDB0A7L);
        for (int sample = 0; sample < 512; sample++) {
            int sampleIndex = sample;
            int variables = 1 + random.nextInt(3);
            int rows = 1 + random.nextInt(5);
            int max = 5;
            var constraints = new java.util.ArrayList<BoundedIntegerLinearSolver.Constraint>();
            for (int row = 0; row < rows; row++) {
                long[] coefficients = new long[variables];
                boolean nonZero = false;
                for (int variable = 0; variable < variables; variable++) {
                    coefficients[variable] = random.nextInt(7) - 3;
                    nonZero |= coefficients[variable] != 0;
                }
                if (!nonZero) {
                    coefficients[random.nextInt(variables)] = 1;
                }
                constraints.add(new BoundedIntegerLinearSolver.Constraint(
                        coefficients, random.nextInt(16) - 5));
            }

            long[] brute = bruteForce(variables, max, constraints);
            var solved = BoundedIntegerLinearSolver.solve(
                    variables, constraints, max, 512);
            assertEquals(brute != null, solved.solved(),
                    () -> "sample=" + sampleIndex + " status=" + solved.status());
            if (solved.solved()) {
                assertTrue(feasible(solved.values(), constraints));
            } else {
                assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, solved.status());
            }
        }
    }

    @Test
    void fixedLongChainIsSolvedBeforeDenseTableauAdmission() {
        int variables = 160;
        long amount = 1_000_000_000_000L;
        var constraints = new java.util.ArrayList<BoundedIntegerLinearSolver.Constraint>();
        long[] root = new long[variables]; root[0] = 1;
        constraints.add(new BoundedIntegerLinearSolver.Constraint(root, amount));
        long[] leaf = new long[variables]; leaf[variables - 1] = -1;
        constraints.add(new BoundedIntegerLinearSolver.Constraint(leaf, -amount));
        for (int i = 1; i < variables; i++) {
            long[] flow = new long[variables]; flow[i] = 1; flow[i - 1] = -1;
            constraints.add(new BoundedIntegerLinearSolver.Constraint(flow, 0));
        }
        var result = BoundedIntegerLinearSolver.solve(variables, constraints, Sat.SAT, 1,
                BoundedIntegerLinearSolver.WorkBudget.bounded(32, 100_000, 1_000_000_000L));
        assertTrue(result.solved(), () -> "status=" + result.status());
        long[] expected = new long[variables]; java.util.Arrays.fill(expected, amount);
        assertArrayEquals(expected, result.values());
    }

    @Test
    void exactArithmeticHandlesProductsBeyondLongAndNegativeDivision() {
        var result = BoundedIntegerLinearSolver.solve(2, List.of(
                row(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                row(-Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)), Long.MAX_VALUE, 32);
        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status());
        var negativeRounding = BoundedIntegerLinearSolver.solve(1,
                List.of(row(-3, -2), row(1, 1)), Sat.SAT, 4);
        assertTrue(negativeRounding.solved());
        assertArrayEquals(new long[]{1}, negativeRounding.values());
    }

    @Test
    void propagationWorkIsBoundedEvenWhenACycleTightensOneUnitAtATime() {
        var constraints = List.of(row(1, 1, -1), row(0, -1, 1));
        var small = SparseIntegerBounds.reduce(2, constraints, 1_000_000L,
                BoundedIntegerLinearSolver.WorkBudget.unlimited());
        var large = SparseIntegerBounds.reduce(2, constraints, 1_000_000_000_000L,
                BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertEquals(SparseIntegerBounds.Status.REDUCED, small.status());
        assertEquals(small.work(), large.work());
        assertTrue(large.work() <= 260);
        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE,
                BoundedIntegerLinearSolver.solve(2, constraints, Sat.SAT, 16).status());
    }

    @Test
    void repeatedCallsCannotResetASharedPresolveBudget() {
        var shared = BoundedIntegerLinearSolver.WorkBudget.bounded(32, 32, 1_000_000_000L);
        var constraints = List.of(row(1, 1));
        assertTrue(BoundedIntegerLinearSolver.solve(1, constraints, Sat.SAT, 1, shared).solved());
        boolean exhausted = false;
        for (int i = 0; i < 20; i++) {
            var result = BoundedIntegerLinearSolver.solve(1, constraints, Sat.SAT, 1, shared);
            exhausted |= result.status() == BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED;
            if (exhausted) assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, result.status());
        }
        assertTrue(exhausted);
        assertTrue(BoundedIntegerLinearSolver.solve(1, constraints, Sat.SAT, 1).solved());
    }

    private static long[] bruteForce(
            int variables,
            int max,
            List<BoundedIntegerLinearSolver.Constraint> constraints) {
        long[] values = new long[variables];
        while (true) {
            if (feasible(values, constraints)) {
                return values.clone();
            }
            int variable = 0;
            while (variable < variables && values[variable] == max) {
                values[variable] = 0;
                variable++;
            }
            if (variable == variables) {
                return null;
            }
            values[variable]++;
        }
    }

    private static boolean feasible(
            long[] values, List<BoundedIntegerLinearSolver.Constraint> constraints) {
        for (var constraint : constraints) {
            long total = 0;
            long[] coefficients = constraint.coefficients();
            for (int i = 0; i < values.length; i++) {
                total += coefficients[i] * values[i];
            }
            if (total < constraint.minimum()) {
                return false;
            }
        }
        return true;
    }

    private static BoundedIntegerLinearSolver.Constraint row(long minimum, long... coefficients) {
        return new BoundedIntegerLinearSolver.Constraint(coefficients, minimum);
    }
}
