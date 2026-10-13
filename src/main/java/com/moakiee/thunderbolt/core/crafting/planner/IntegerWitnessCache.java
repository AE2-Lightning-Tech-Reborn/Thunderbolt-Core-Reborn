package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dispatch-local reuse of solved, identical integer models; never caches cutoffs or diagnoses. */
final class IntegerWitnessCache {
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_CELLS = 512;
    private final Map<Model, long[]> witnesses = new HashMap<>();

    BoundedIntegerLinearSolver.Result solve(int variables,
            List<BoundedIntegerLinearSolver.Constraint> constraints, long maximum, int nodes,
            BoundedIntegerLinearSolver.WorkBudget budget) {
        PlanningCancellation.check();
        if (constraints == null)
            return BoundedIntegerLinearSolver.solve(variables, null, maximum, nodes, budget);
        long cells = (long) constraints.size() * (variables + 1L);
        if (variables <= 0 || maximum < 0 || nodes <= 0 || cells > MAX_CELLS
                || constraints.stream().anyMatch(row -> row == null || row.coefficients().length != variables))
            return BoundedIntegerLinearSolver.solve(variables, constraints, maximum, nodes, budget);
        if (!budget.tryConsume(cells)) return new BoundedIntegerLinearSolver.Result(
                BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, null, 0);
        var model = new Model(variables, constraints, maximum, nodes);
        var witness = witnesses.get(model);
        if (witness != null) {
            // Equality includes every coefficient, stock/external-demand RHS and variable bound.
            // Only row order is irrelevant. The caller still certifies the combined real inventory.
            PlanningCancellation.check();
            return new BoundedIntegerLinearSolver.Result(BoundedIntegerLinearSolver.Status.SOLVED, witness, 0);
        }
        var result = BoundedIntegerLinearSolver.solve(variables, constraints, maximum, nodes, budget);
        if (result.solved() && witnesses.size() < MAX_ENTRIES) witnesses.put(model, result.values());
        return result;
    }

    private static final class Model {
        private final int variables, nodes;
        private final long maximum;
        private final long[][] rows;
        private final int hash;

        Model(int variables, List<BoundedIntegerLinearSolver.Constraint> constraints, long maximum, int nodes) {
            this.variables = variables;
            this.maximum = maximum;
            this.nodes = nodes;
            rows = new long[constraints.size()][];
            for (int i = 0; i < rows.length; i++) {
                PlanningCancellation.check();
                var constraint = constraints.get(i);
                rows[i] = Arrays.copyOf(constraint.coefficients(), variables + 1);
                rows[i][variables] = constraint.minimum();
            }
            Arrays.sort(rows, Arrays::compare);
            hash = 31 * (31 * (31 * variables + nodes) + Long.hashCode(maximum)) + Arrays.deepHashCode(rows);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            return other instanceof Model model && variables == model.variables && nodes == model.nodes
                    && maximum == model.maximum && Arrays.deepEquals(rows, model.rows);
        }
    }
}
