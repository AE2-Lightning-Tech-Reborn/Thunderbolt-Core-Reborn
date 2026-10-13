package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class IntegerWitnessCacheTest {
    @Test
    void rowOrderCanReuseTheExactSolvedVectorWithoutSearchingAgain() {
        var cache = new IntegerWitnessCache();
        var lower = row(2, 3);
        var upper = row(-1, -4);
        var first = solve(cache, List.of(lower, upper), 10);
        assertTrue(first.solved());
        assertTrue(first.visitedNodes() > 0);
        var second = solve(cache, List.of(upper, lower), 10);
        assertTrue(second.solved());
        assertArrayEquals(first.values(), second.values());
        assertEquals(0, second.visitedNodes());
        var leaked = second.values();
        leaked[0] = 99;
        assertArrayEquals(first.values(), solve(cache, List.of(lower, upper), 10).values());
    }

    @Test
    void stockDemandCoefficientsAndVariableBoundsStayPartOfTheModel() {
        var cache = new IntegerWitnessCache();
        assertArrayEquals(new long[] {2}, solve(cache, List.of(row(1, 2)), 10).values());
        assertArrayEquals(new long[] {5}, solve(cache, List.of(row(1, 5)), 10).values());
        assertArrayEquals(new long[] {3}, solve(cache, List.of(row(2, 5)), 10).values());
        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE,
                solve(cache, List.of(row(1, 5)), 4).status());
        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE,
                solve(cache, List.of(row(1, 2), row(-1, -1)), 10).status());
        assertTrue(solve(cache, List.of(row(1, 5)), 10).solved());
    }

    @Test
    void cacheHitsStillChargeSharedWorkAndPropagateCancellation() {
        var cache = new IntegerWitnessCache();
        var rows = List.of(row(1, 2));
        assertTrue(solve(cache, rows, 10).solved());
        var denied = cache.solve(1, rows, 10, 16,
                BoundedIntegerLinearSolver.WorkBudget.bounded(1, 1, Long.MAX_VALUE));
        assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, denied.status());
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> solve(cache, rows, 10));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aCutoffCannotBecomeAnInfeasibilityResultForAnotherCaller() {
        var cache = new IntegerWitnessCache();
        var rows = List.of(row(1, 2));
        var cutoff = cache.solve(1, rows, 10, 16,
                BoundedIntegerLinearSolver.WorkBudget.bounded(1, 1, Long.MAX_VALUE));
        assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, cutoff.status());
        var solved = solve(cache, rows, 10);
        assertTrue(solved.solved());
        assertTrue(solved.visitedNodes() > 0);
    }

    @Test
    void distinctVariableColumnsAndMalformedRowsCannotAliasAnExistingWitness() {
        var cache = new IntegerWitnessCache();
        var first = cache.solve(2, List.of(new BoundedIntegerLinearSolver.Constraint(new long[] {1, 0}, 3)),
                10, 16, BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertTrue(first.solved());
        var swapped = cache.solve(2, List.of(new BoundedIntegerLinearSolver.Constraint(new long[] {0, 1}, 3)),
                10, 16, BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertTrue(swapped.solved());
        assertTrue(swapped.values()[1] >= 3);
        assertTrue(swapped.visitedNodes() > 0);
        var invalid = cache.solve(1, List.of(new BoundedIntegerLinearSolver.Constraint(new long[] {1, 0}, 3)),
                10, 16, BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertEquals(BoundedIntegerLinearSolver.Status.INVALID_INPUT, invalid.status());
    }

    private static BoundedIntegerLinearSolver.Constraint row(long coefficient, long minimum) {
        return new BoundedIntegerLinearSolver.Constraint(new long[] {coefficient}, minimum);
    }

    private static BoundedIntegerLinearSolver.Result solve(IntegerWitnessCache cache,
            List<BoundedIntegerLinearSolver.Constraint> rows, long maximum) {
        return cache.solve(1, rows, maximum, 16, BoundedIntegerLinearSolver.WorkBudget.unlimited());
    }
}
