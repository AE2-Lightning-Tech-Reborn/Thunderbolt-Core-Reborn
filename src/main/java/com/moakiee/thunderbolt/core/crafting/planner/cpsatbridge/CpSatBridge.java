package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.DecisionStrategyProto;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;

/**
 * Small bootstrap-type-only boundary loaded with the downloaded OR-Tools runtime.
 *
 * <p>Do not expose Thunderbolt, OR-Tools, or protobuf types in this class's public methods: the
 * caller intentionally lives in a different class loader.</p>
 */
public final class CpSatBridge {
    public static final long SOLVED = 0L;
    public static final long INFEASIBLE = 1L;
    public static final long MODEL_INVALID = 2L;
    public static final long UNKNOWN = 3L;
    public static final long SOLVED_PARTIAL = 4L;

    private CpSatBridge() {
    }

    public static void initialize() {
        Loader.loadNativeLibraries();
    }

    /** Returns {@code [status, branches, x0, x1, ...]}. */
    public static long[] solve(
            long[][] coefficients,
            long[] minimums,
            long[] upperBounds,
            int executionVariableCount,
            long[][] stockUseNetCoefficients,
            long[] stockUseOffsets,
            long[] stockUseUpperBounds,
            int[] stockUseDistances,
            int[] directUsedVariables,
            int[] directUsedDistances,
            double maxSeconds) {
        int variableCount = upperBounds.length;
        int stockUseCount = stockUseOffsets.length;
        if (minimums.length != coefficients.length
                || executionVariableCount < 0
                || executionVariableCount > variableCount
                || stockUseNetCoefficients.length != stockUseCount
                || stockUseUpperBounds.length != stockUseCount
                || stockUseDistances.length != stockUseCount
                || directUsedVariables.length != directUsedDistances.length) {
            return new long[] {MODEL_INVALID, 0L};
        }
        var model = new CpModel();
        var variables = new IntVar[variableCount];
        for (int variable = 0; variable < variableCount; variable++) {
            if (upperBounds[variable] < 0L) return new long[] {MODEL_INVALID, 0L};
            variables[variable] = model.newIntVar(0L, upperBounds[variable], "x_" + variable);
        }
        for (int row = 0; row < coefficients.length; row++) {
            if (coefficients[row].length != variableCount) {
                return new long[] {MODEL_INVALID, 0L};
            }
            model.addGreaterOrEqual(
                    LinearExpr.weightedSum(variables, coefficients[row]), minimums[row]);
        }

        var allUsed = new java.util.ArrayList<IntVar>();
        for (int row = 0; row < stockUseCount; row++) {
            if (stockUseNetCoefficients[row].length != variableCount
                    || stockUseUpperBounds[row] < 0L
                    || stockUseDistances[row] < 0) {
                return new long[] {MODEL_INVALID, 0L};
            }
            IntVar used = model.newIntVar(
                    0L, stockUseUpperBounds[row], "used_" + row);
            IntVar[] stockUseVariables = new IntVar[variableCount + 1];
            long[] stockUseCoefficients = new long[variableCount + 1];
            stockUseVariables[0] = used;
            stockUseCoefficients[0] = 1L;
            System.arraycopy(variables, 0, stockUseVariables, 1, variableCount);
            System.arraycopy(
                    stockUseNetCoefficients[row],
                    0,
                    stockUseCoefficients,
                    1,
                    variableCount);
            model.addGreaterOrEqual(
                    LinearExpr.weightedSum(stockUseVariables, stockUseCoefficients),
                    stockUseOffsets[row]);
            if (stockUseUpperBounds[row] > 0L) {
                allUsed.add(used);
            }
        }
        for (int index = 0; index < directUsedVariables.length; index++) {
            int variable = directUsedVariables[index];
            if (variable < 0
                    || variable >= variableCount
                    || directUsedDistances[index] < 0) {
                return new long[] {MODEL_INVALID, 0L};
            }
            allUsed.add(variables[variable]);
        }

        String validation = model.validate();
        if (!validation.isEmpty()) {
            throw new IllegalArgumentException("invalid CP-SAT integer model: " + validation);
        }

        long deadlineNanos = deadlineNanos(maxSeconds);
        long branches = 0L;
        IntVar[] executions = java.util.Arrays.copyOf(variables, executionVariableCount);
        model.minimize(LinearExpr.sum(executions));
        SolveAttempt executionAttempt = solveOptimal(model, deadlineNanos);
        if (executionAttempt.status() != CpSolverStatus.OPTIMAL) {
            return new long[] {optimalStatusCode(executionAttempt.status()), branches};
        }
        long executionOptimum = 0L;
        try {
            for (IntVar execution : executions) {
                executionOptimum = Math.addExact(
                        executionOptimum, executionAttempt.solver().value(execution));
            }
        } catch (ArithmeticException overflow) {
            return new long[] {MODEL_INVALID, branches};
        }
        model.addEquality(LinearExpr.sum(executions), executionOptimum);
        model.clearObjective();

        SolveAttempt finalAttempt = executionAttempt;
        if (!allUsed.isEmpty()) {
            model.minimize(LinearExpr.sum(allUsed.toArray(IntVar[]::new)));
            finalAttempt = solveOptimal(model, deadlineNanos);
            branches = saturatedAdd(branches, finalAttempt.branches());
        }
        long statusCode = optimalStatusCode(finalAttempt.status());
        long[] result = new long[2 + (statusCode == SOLVED ? variableCount : 0)];
        result[0] = statusCode;
        result[1] = branches;
        if (statusCode == SOLVED) {
            for (int variable = 0; variable < variableCount; variable++) {
                result[2 + variable] = finalAttempt.solver().value(variables[variable]);
            }
        }
        return result;
    }

    /**
     * Selects an acyclic support for an ordinary material graph.
     *
     * <p>Every recipe has one long firing variable and one activation Boolean. If active, all of its
     * input groups must have a smaller topological rank than its primary-output group. Items in one
     * proven ratio-conservative conversion SCC share a rank and may therefore use both conversion
     * directions; the caller supplies a separate executable-prefix certificate for those groups.
     * Together with exact material balances and explicit unchanged-catalyst presence rows, no time
     * horizon or per-firing expansion is present.</p>
     *
     * @param cycleRecipes recipe indices in each proven strict conversion cycle, in cycle order
     * @param cycleInputItems internal input item aligned with every cycle recipe
     * @param cycleInputAmounts internal input amount aligned with every cycle recipe
     * @param cyclePrimitiveFirings one zero-net primitive firing vector per cycle
     * @param reusableCatalystsWire sparse per-recipe unchanged-catalyst amount for each private route
     * @param reusableItems logical item represented by each private route
     * @param reusableCandidatePhysicals accepted physical-stock indices for each private route
     * @param reusablePhysicalStocks capacity of every host + actual-variant physical stock
     * @return {@code [status, branches, x0..xN, rank0..rankM, selectedCycleStart0..K,
     * missing0..missingM, reusableMissing0..G, blockRepetitions(stage-major)]}
     */
    public static long[] solveRankedPlan(
            long[][] consumedWire,
            long[][] producedWire,
            long[][] catalystsWire,
            long[][] finiteUseAmountsWire,
            long[][] finiteUseLifetimesWire,
            int[] outputItems,
            int[] primaryOutputItems,
            long[] primaryOutputAmounts,
            int[] rankGroups,
            int[][] cycleRecipes,
            int[][] cycleInputItems,
            long[][] cycleInputAmounts,
            long[][] cyclePrimitiveFirings,
            long[] stocks,
            long[][] reusableCatalystsWire,
            int[] reusableItems,
            int[][] reusableCandidatePhysicals,
            long[] reusablePhysicalStocks,
            int[] itemDistances,
            int targetItem,
            long targetAmount,
            long[] firingUpperBounds,
            long[] missingCaps,
            long[][] unreachable,
            boolean enforceStartup,
            boolean[] missingAllowed,
            int[][] missingCutProducers,
            long[][] executionBlocks,
            int blockStages,
            double maxSeconds) {
        long deadline = deadlineNanos(maxSeconds);
        var consumed = SparseLongMatrix.fromWire(consumedWire);
        var produced = SparseLongMatrix.fromWire(producedWire);
        var catalysts = SparseLongMatrix.fromWire(catalystsWire);
        var finiteUseAmounts = SparseLongMatrix.fromWire(finiteUseAmountsWire);
        var finiteUseLifetimes = SparseLongMatrix.fromWire(finiteUseLifetimesWire);
        var reusableCatalysts = SparseLongMatrix.fromWire(reusableCatalystsWire);
        int recipeCount = consumed.rows();
        int itemCount = stocks.length;
        if (recipeCount == 0
                || recipeCount != produced.rows()
                || recipeCount != catalysts.rows()
                || recipeCount != finiteUseAmounts.rows()
                || recipeCount != finiteUseLifetimes.rows()
                || recipeCount != reusableCatalysts.rows()
                || recipeCount != outputItems.length
                || recipeCount != primaryOutputItems.length
                || recipeCount != primaryOutputAmounts.length
                || recipeCount != firingUpperBounds.length
                || rankGroups.length != itemCount
                || itemDistances.length != itemCount
                || missingAllowed.length != itemCount
                || missingCutProducers.length != itemCount
                || cycleRecipes.length != cycleInputItems.length
                || cycleRecipes.length != cycleInputAmounts.length
                || cycleRecipes.length != cyclePrimitiveFirings.length
                || reusableItems.length != reusableCandidatePhysicals.length
                || targetItem < 0
                || targetItem >= itemCount
                || targetAmount <= 0L) {
            return new long[] {MODEL_INVALID, 0L};
        }

        var model = new CpModel();
        var firings = new IntVar[recipeCount];
        var active = new BoolVar[recipeCount];
        var finiteUseBatches = new java.util.HashMap<Integer, java.util.Map<Integer, IntVar>>();
        var ranks = new IntVar[itemCount];
        var used = new IntVar[itemCount];
        var missing = new IntVar[itemCount];
        long missingUpperBound = Math.max(1L, (Long.MAX_VALUE / 4L) / itemCount);
        for (int item = 0; item < itemCount; item++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            if (rankGroups[item] < 0 || itemDistances[item] < 0 || stocks[item] < 0L) {
                return new long[] {MODEL_INVALID, 0L};
            }
            ranks[item] = model.newIntVar(0L, Math.max(0, itemCount - 1L), "rank_" + item);
            used[item] = model.newIntVar(0L, stocks[item], "used_" + item);
            missing[item] = model.newIntVar(0L, missingAllowed[item] ? missingUpperBound : 0L, "missing_" + item);
        }
        var representativeByGroup = new java.util.HashMap<Integer, Integer>();
        for (int item = 0; item < itemCount; item++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            Integer representative = representativeByGroup.putIfAbsent(rankGroups[item], item);
            if (representative != null) {
                model.addEquality(ranks[item], ranks[representative]);
            }
        }
        for (int recipe = 0; recipe < recipeCount; recipe++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            if (consumed.columns() != itemCount
                    || produced.columns() != itemCount
                    || catalysts.columns() != itemCount
                    || finiteUseAmounts.columns() != itemCount
                    || finiteUseLifetimes.columns() != itemCount
                    || reusableCatalysts.columns() != reusableItems.length
                    || outputItems[recipe] < 0
                    || outputItems[recipe] >= itemCount
                    || primaryOutputItems[recipe] < 0
                    || primaryOutputItems[recipe] >= itemCount
                    || primaryOutputAmounts[recipe] <= 0L
                    || firingUpperBounds[recipe] < 0L) {
                return new long[] {MODEL_INVALID, 0L};
            }
            firings[recipe] = model.newIntVar(
                    0L, firingUpperBounds[recipe], "x_" + recipe);
            active[recipe] = model.newBoolVar("active_" + recipe);
            for (int item : SparseLongMatrix.unionRow(recipe, finiteUseAmounts, finiteUseLifetimes)) {
                long amount = finiteUseAmounts.get(recipe, item);
                long lifetime = finiteUseLifetimes.get(recipe, item);
                if ((amount == 0L) != (lifetime == 0L) || amount < 0L || lifetime < 0L) {
                    return new long[] {MODEL_INVALID, 0L};
                }
                if (amount == 0L) continue;
                long upper = Math.floorDiv(firingUpperBounds[recipe] - 1L, lifetime) + 1L;
                IntVar batches = model.newIntVar(
                        0L, upper, "finite_" + recipe + "_" + item);
                finiteUseBatches.computeIfAbsent(recipe, ignored -> new java.util.HashMap<>()).put(item, batches);
                model.addGreaterOrEqual(
                        LinearExpr.weightedSum(
                                new IntVar[] {batches, firings[recipe]},
                                new long[] {lifetime, -1L}),
                        0L);
                model.addLessOrEqual(
                        LinearExpr.weightedSum(
                                new IntVar[] {batches, firings[recipe]},
                                new long[] {lifetime, -1L}),
                        lifetime - 1L);
            }
        }

        var cycleStarts = new BoolVar[cycleRecipes.length][];
        for (int cycle = 0; cycle < cycleRecipes.length; cycle++) {
            int size = cycleRecipes[cycle].length;
            if (size == 0
                    || cycleInputItems[cycle].length != size
                    || cycleInputAmounts[cycle].length != size
                    || cyclePrimitiveFirings[cycle].length != size) {
                return new long[] {MODEL_INVALID, 0L};
            }
            boolean[] memberRecipe = new boolean[recipeCount];
            for (int offset = 0; offset < size; offset++) {
                int recipe = cycleRecipes[cycle][offset];
                int input = cycleInputItems[cycle][offset];
                if (recipe < 0 || recipe >= recipeCount
                        || input < 0 || input >= itemCount
                        || memberRecipe[recipe]
                        || cycleInputAmounts[cycle][offset] <= 0L
                        || cyclePrimitiveFirings[cycle][offset] <= 0L
                        || rankGroups[input] != rankGroups[outputItems[recipe]]) {
                    return new long[] {MODEL_INVALID, 0L};
                }
                memberRecipe[recipe] = true;
            }

            cycleStarts[cycle] = new BoolVar[size];
            for (int start = 0; start < size; start++) {
                BoolVar selected = model.newBoolVar("cycle_" + cycle + "_start_" + start);
                cycleStarts[cycle][start] = selected;
            }
            model.addExactlyOne(cycleStarts[cycle]);

            // A weighted conversion cycle may need to interleave the same transition several times
            // within one primitive residue (for example A->B, B->A, A->B). Treating each recipe's
            // complete firing count as one contiguous block rejects such executable markings. The
            // caller therefore reconstructs an exact bounded Petri prefix for the chosen firing
            // vector and charges its concrete startup state during independent replay. The one-hot
            // value remains in the wire format for compatibility; it is no longer a block-order
            // certificate.

            // A complete primitive round has zero internal delta and only consumes non-negative
            // external inputs. Subtracting it preserves every material lower bound while strictly
            // reducing the objective, so retain only cycle-reduced representatives in the model.
            BoolVar[] belowPrimitive = new BoolVar[size];
            for (int offset = 0; offset < size; offset++) {
                int recipe = cycleRecipes[cycle][offset];
                belowPrimitive[offset] = model.newBoolVar(
                        "cycle_" + cycle + "_below_" + offset);
                model.addLessThan(
                                firings[recipe], cyclePrimitiveFirings[cycle][offset])
                        .onlyEnforceIf(belowPrimitive[offset]);
            }
            model.addGreaterOrEqual(LinearExpr.sum(belowPrimitive), 1L);
        }
        // Create the complete variable vector before building any weighted sum: a catalyst may be
        // supplied by a producer that appears later in the caller's stable recipe order.
        for (int i = 0; i < itemCount; i++) for (int producer : missingCutProducers[i]) {
            if (producer < 0 || producer >= recipeCount) return new long[]{MODEL_INVALID, 0};
            // A non-contracted cycle can use this point as an external leaf only after cutting
            // its incoming cyclic producers. Retained, proven SCC seed ports have no such rows.
            model.addEquality(missing[i], 0).onlyEnforceIf(active[producer]);
        }
        for (int recipe = 0; recipe < recipeCount; recipe++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            model.addGreaterThan(firings[recipe], 0L).onlyEnforceIf(active[recipe]);
            model.addEquality(firings[recipe], 0L).onlyEnforceIf(active[recipe].not());
            int output = outputItems[recipe];
            for (int input : SparseLongMatrix.unionRow(recipe, consumed, catalysts, finiteUseAmounts)) {
                if ((consumed.get(recipe, input) > 0L
                                || catalysts.get(recipe, input) > 0L
                                || finiteUseAmounts.get(recipe, input) > 0L)
                        && rankGroups[input] != rankGroups[output]) {
                    model.addLessThan(ranks[input], ranks[output])
                            .onlyEnforceIf(active[recipe]);
                }
                if (catalysts.get(recipe, input) > 0L) {
                    long shortage = catalysts.get(recipe, input) - stocks[input];
                    var presence = LinearExpr.newBuilder();
                    for (int producer : produced.columnKeys(input))
                        presence.addTerm(firings[producer], produced.get(producer, input));
                    presence.addTerm(missing[input], 1);
                    if (shortage > 0L) model.addGreaterOrEqual(presence, shortage).onlyEnforceIf(active[recipe]);
                    presence.addTerm(used[input], 1);
                    presence.addTerm(active[recipe], -catalysts.get(recipe, input));
                    model.addGreaterOrEqual(presence, 0);

                }
            }
            for (int group : reusableCatalysts.rowKeys(recipe)) {
                long amount = reusableCatalysts.get(recipe, group);
                int input = reusableItems[group];
                if (amount < 0L || input < 0 || input >= itemCount) {
                    return new long[] {MODEL_INVALID, 0L};
                }
                if (amount > 0L && rankGroups[input] != rankGroups[output]) {
                    model.addLessThan(ranks[input], ranks[output])
                            .onlyEnforceIf(active[recipe]);
                }
            }
            // A recipe is replayed at its anchor output rank. Every other material output must
            // become visible later unless it belongs to the same contracted SCC; otherwise a
            // byproduct consumer could be ranked before the recipe that creates it.
            for (int producedItem : produced.rowKeys(recipe)) {
                if (produced.get(recipe, producedItem) > 0L
                        && rankGroups[producedItem] != rankGroups[output]) {
                    model.addLessThan(ranks[output], ranks[producedItem])
                            .onlyEnforceIf(active[recipe]);
                }
            }
        }

        // Exact gross presence demand for unchanged catalysts. A shared catalyst is counted once at
        // the largest active requirement, not once per recipe, so it cannot justify unrelated extra
        // primary production.
        var catalystNeeds = new IntVar[itemCount];
        for (int item = 0; item < itemCount; item++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            long maximum = 0L;
            var arguments = new java.util.ArrayList<com.google.ortools.sat.LinearArgument>();
            for (int recipe : catalysts.columnKeys(item)) {
                long amount = catalysts.get(recipe, item);
                if (amount <= 0L) continue;
                maximum = Math.max(maximum, amount);
                arguments.add(LinearExpr.affine(active[recipe], amount, 0L));
            }
            if (arguments.isEmpty()) continue;
            arguments.add(LinearExpr.constant(0L));
            IntVar need = model.newIntVar(0L, maximum, "catalyst_need_" + item);
            model.addMaxEquality(
                    need,
                    arguments.toArray(com.google.ortools.sat.LinearArgument[]::new));
            catalystNeeds[item] = need;
        }

        // Host-private catalysts are not ordinary logical stock. Each route gets its exact active
        // seed requirement (the maximum across recipes sharing that route), then assigns the
        // non-missing part over only its accepted physical variants. Physical capacity rows couple
        // all overlapping fuzzy routes, preventing one host stack from being counted twice.
        int reusableCount = reusableItems.length;
        var reusableMissing = new IntVar[reusableCount];
        var assignmentsByPhysical = new java.util.ArrayList<java.util.ArrayList<IntVar>>(
                reusablePhysicalStocks.length);
        for (int physical = 0; physical < reusablePhysicalStocks.length; physical++) {
            if (reusablePhysicalStocks[physical] < 0L) {
                return new long[] {MODEL_INVALID, 0L};
            }
            assignmentsByPhysical.add(new java.util.ArrayList<>());
        }
        var reusableMissingByItem = new java.util.ArrayList<java.util.ArrayList<IntVar>>(itemCount);
        for (int item = 0; item < itemCount; item++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            reusableMissingByItem.add(new java.util.ArrayList<>());
        }
        var reusableUsed = new java.util.ArrayList<IntVar>();
        for (int group = 0; group < reusableCount; group++) {
            int item = reusableItems[group];
            if (item < 0 || item >= itemCount) return new long[] {MODEL_INVALID, 0L};
            long maximum = 0L;
            var needArguments = new java.util.ArrayList<com.google.ortools.sat.LinearArgument>();
            for (int recipe : reusableCatalysts.columnKeys(group)) {
                long amount = reusableCatalysts.get(recipe, group);
                if (amount < 0L) return new long[] {MODEL_INVALID, 0L};
                if (amount > 0L) {
                    maximum = Math.max(maximum, amount);
                    needArguments.add(LinearExpr.affine(active[recipe], amount, 0L));
                }
            }
            if (needArguments.isEmpty()) return new long[] {MODEL_INVALID, 0L};
            needArguments.add(LinearExpr.constant(0L));
            IntVar need = model.newIntVar(0L, maximum, "reusable_need_" + group);
            model.addMaxEquality(
                    need,
                    needArguments.toArray(com.google.ortools.sat.LinearArgument[]::new));

            IntVar shortage = model.newIntVar(0L, maximum, "reusable_missing_" + group);
            reusableMissing[group] = shortage;
            reusableMissingByItem.get(item).add(shortage);

            int[] candidates = reusableCandidatePhysicals[group];
            var routeAssignments = new java.util.ArrayList<IntVar>(candidates.length);
            var seenPhysicals = new java.util.HashSet<Integer>();
            for (int candidate = 0; candidate < candidates.length; candidate++) {
                int physical = candidates[candidate];
                if (physical < 0
                        || physical >= reusablePhysicalStocks.length
                        || !seenPhysicals.add(physical)) {
                    return new long[] {MODEL_INVALID, 0L};
                }
                long upper = Math.min(maximum, reusablePhysicalStocks[physical]);
                IntVar assignment = model.newIntVar(
                        0L, upper, "reusable_assign_" + group + "_" + candidate);
                routeAssignments.add(assignment);
                assignmentsByPhysical.get(physical).add(assignment);
                reusableUsed.add(assignment);
            }
            var fulfilled = new java.util.ArrayList<IntVar>(routeAssignments);
            fulfilled.add(shortage);
            model.addEquality(LinearExpr.sum(fulfilled.toArray(IntVar[]::new)), need);
        }
        for (int physical = 0; physical < reusablePhysicalStocks.length; physical++) {
            var assignments = assignmentsByPhysical.get(physical);
            if (!assignments.isEmpty()) {
                model.addLessOrEqual(
                        LinearExpr.sum(assignments.toArray(IntVar[]::new)),
                        reusablePhysicalStocks[physical]);
            }
        }
        for (int item = 0; item < itemCount; item++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            var shortages = reusableMissingByItem.get(item);
            if (!shortages.isEmpty()) {
                model.addEquality(missing[item], LinearExpr.sum(shortages.toArray(IntVar[]::new)));
            }
        }

        // Every active recipe must allocate all but at most one batch remainder of its primary
        // output to real gross demand. The minimum served amount is
        // amount*firings-(amount-1)*active. Express it inline rather than with another long-domain
        // variable: besides being exact, this keeps OR-Tools' sum-of-domains overflow guard
        // independent of recipe ratios.
        int[] demandRows = new int[itemCount];
        java.util.Arrays.fill(demandRows, -1);
        for (int item = 0; item < itemCount; item++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            var demandVariables = new java.util.ArrayList<IntVar>();
            var demandCoefficients = new java.util.ArrayList<Long>();
            boolean hasPrimaryProducer = false;
            for (int recipe : SparseLongMatrix.unionColumn(item, produced, consumed, finiteUseAmounts)) {
                if (primaryOutputItems[recipe] == item) {
                    demandVariables.add(firings[recipe]);
                    demandCoefficients.add(primaryOutputAmounts[recipe]);
                    if (primaryOutputAmounts[recipe] > 1L) {
                        demandVariables.add(active[recipe]);
                        demandCoefficients.add(1L - primaryOutputAmounts[recipe]);
                    }
                    hasPrimaryProducer = true;
                }
                if (consumed.get(recipe, item) > 0L) {
                    demandVariables.add(firings[recipe]);
                    demandCoefficients.add(-consumed.get(recipe, item));
                }
                if (finiteUseBatches.getOrDefault(recipe, java.util.Map.of()).get(item) != null) {
                    demandVariables.add(finiteUseBatches.getOrDefault(recipe, java.util.Map.of()).get(item));
                    demandCoefficients.add(-finiteUseAmounts.get(recipe, item));
                }
            }
            if (!hasPrimaryProducer) continue;
            if (catalystNeeds[item] != null) {
                demandVariables.add(catalystNeeds[item]);
                demandCoefficients.add(-1L);
            }
            long directDemand = item == targetItem ? targetAmount : 0L;
            demandRows[item] = model.getBuilder().getConstraintsCount();
            model.addLessOrEqual(
                    LinearExpr.weightedSum(
                            demandVariables.toArray(IntVar[]::new),
                            demandCoefficients.stream().mapToLong(Long::longValue).toArray()),
                    directDemand);
        }

        int[] balanceRows = new int[itemCount];
        for (int item = 0; item < itemCount; item++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            var balance = LinearExpr.newBuilder();
            for (int recipe : SparseLongMatrix.unionColumn(item, consumed, produced, finiteUseAmounts)) {
                long coefficient = produced.get(recipe, item)-consumed.get(recipe, item);
                if (coefficient != 0) balance.addTerm(firings[recipe], coefficient);
                var finite = finiteUseBatches.getOrDefault(recipe, java.util.Map.of()).get(item);
                if (finite != null) balance.addTerm(finite, -finiteUseAmounts.get(recipe, item));
            }
            long demand = item == targetItem ? targetAmount : 0L;
            balance.addTerm(missing[item], 1);
            balanceRows[item] = model.getBuilder().getConstraintsCount();
            model.addGreaterOrEqual(balance, demand-stocks[item]);
            balance.addTerm(used[item], 1);
            model.addGreaterOrEqual(balance, demand);
        }
        addGroupBalances(model, firings, finiteUseBatches, missing, consumed, produced,
                finiteUseAmounts, rankGroups, stocks, firingUpperBounds, missingUpperBound,
                targetItem, targetAmount);
        CpSatBalanceCuts.add(model, firings, rankGroups, demandRows, balanceRows);
        addEmptySiphonSeeds(model, active, missing, consumed, produced, rankGroups, stocks);
        if (!addPetriRefinement(model, firings, active, missing, consumed, stocks,
                missingCaps, unreachable, enforceStartup)) {
            return new long[] {MODEL_INVALID, 0L};
        }
        IntVar[] blockRepetitions = CpSatExecutionBlocks.add(model, firings, used, missing,
                produced, outputItems, rankGroups, firingUpperBounds, executionBlocks, blockStages);
        model.addDecisionStrategy(
                firings,
                DecisionStrategyProto.VariableSelectionStrategy.CHOOSE_FIRST,
                DecisionStrategyProto.DomainReductionStrategy.SELECT_MIN_VALUE);
        String validation = model.validate();
        if (!validation.isEmpty()) {
            throw new IllegalArgumentException("invalid CP-SAT ranked model: " + validation);
        }
        var allUsed = new java.util.ArrayList<IntVar>();
        for (int item = 0; item < itemCount; item++) if (stocks[item] > 0L) allUsed.add(used[item]);
        allUsed.addAll(reusableUsed);
        var variableMissing = new java.util.ArrayList<IntVar>();
        var variableMissingDistances = new java.util.ArrayList<Integer>();
        // Constant-zero tiers cannot improve the objective; omit their repeated native solves.
        for (int item = 0; item < itemCount; item++) if (missingAllowed[item]) {
            variableMissing.add(missing[item]);
            variableMissingDistances.add(itemDistances[item]);
        }
        RankedOptimum optimum = optimizeRanked(model, firings, variableMissing.toArray(IntVar[]::new),
                variableMissingDistances.stream().mapToInt(Integer::intValue).toArray(),
                allUsed, representativeByGroup.size() < itemCount,
                Math.max(0L, deadline-System.nanoTime()) / 1_000_000_000.0);
        CpSolver solver = optimum.attempt.solver();
        long statusCode = optimum.status;
        long branches = optimum.branches;
        boolean hasWitness = statusCode == SOLVED || statusCode == SOLVED_PARTIAL;
        int valueCount = recipeCount + itemCount + cycleStarts.length + itemCount
                + reusableCount + blockRepetitions.length;
        long[] result = new long[2 + (hasWitness ? valueCount : 0)];
        result[0] = statusCode;
        result[1] = branches;
        // Optimization may exhaust its budget while retaining a valid incumbent. Always
        // serialize that witness; the caller still performs its normal execution verification.
        if (hasWitness) {
            for (int recipe = 0; recipe < recipeCount; recipe++) {
                if (Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
                result[2 + recipe] = solver.value(firings[recipe]);
            }
            for (int item = 0; item < itemCount; item++) {
                if (Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
                result[2 + recipeCount + item] = solver.value(ranks[item]);
            }
            int cycleOffset = 2 + recipeCount + itemCount;
            for (int cycle = 0; cycle < cycleStarts.length; cycle++) {
                long selectedStart = -1L;
                for (int start = 0; start < cycleStarts[cycle].length; start++) {
                    if (solver.booleanValue(cycleStarts[cycle][start])) {
                        selectedStart = start;
                        break;
                    }
                }
                if (selectedStart < 0L) return new long[] {MODEL_INVALID, 0L};
                result[cycleOffset + cycle] = selectedStart;
            }
            int missingOffset = cycleOffset + cycleStarts.length;
            for (int item = 0; item < itemCount; item++) {
                if (Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
                result[missingOffset + item] = solver.value(missing[item]);
            }
            int reusableMissingOffset = missingOffset + itemCount;
            for (int group = 0; group < reusableCount; group++) {
                result[reusableMissingOffset + group] = solver.value(reusableMissing[group]);
            }
            int blockOffset = reusableMissingOffset + reusableCount;
            for (int b = 0; b < blockRepetitions.length; b++)
                result[blockOffset + b] = solver.value(blockRepetitions[b]);
        }
        return result;
    }

    private record RankedOptimum(SolveAttempt attempt, long status, long branches) { }

    /** Sparse ordinary DAG model; rows contain only incident recipes, never recipe-by-item grids. */
    public static long[] solveSparseDag(int[][] variables, long[][] coefficients, int[][] producers,
            long[] batches, long[] upper, long[] stocks, int[] distances, long amount, double maxSeconds) {
        long deadline = deadlineNanos(maxSeconds);
        int items = stocks.length, recipes = upper.length;
        if (items == 0 || recipes == 0 || variables.length != items || coefficients.length != items
                || producers.length != items || distances.length != items || batches.length != recipes
                || amount <= 0) return new long[] {MODEL_INVALID, 0};
        var model = new CpModel();
        var firings = new IntVar[recipes];
        var active = new BoolVar[recipes];
        for (int r = 0; r < recipes; r++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            if (upper[r] < 0 || batches[r] <= 0) return new long[] {MODEL_INVALID, 0};
            firings[r] = model.newIntVar(0, upper[r], "x_"+r);
            if (batches[r] > 1) {
                active[r] = model.newBoolVar("active_"+r);
                model.addGreaterThan(firings[r], 0).onlyEnforceIf(active[r]);
                model.addEquality(firings[r], 0).onlyEnforceIf(active[r].not());
            }
        }
        var missing = new IntVar[items];
        var leafMissing = new java.util.ArrayList<IntVar>();
        var leafDistances = new java.util.ArrayList<Integer>();
        var allUsed = new java.util.ArrayList<IntVar>();
        long missingCap = (Long.MAX_VALUE / 4) / items;
        for (int i = 0; i < items; i++) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return new long[] {UNKNOWN, 0};
            if (variables[i].length != coefficients[i].length || stocks[i] < 0 || distances[i] < 0)
                return new long[] {MODEL_INVALID, 0};
            boolean leaf = i != 0 && producers[i].length == 0;
            missing[i] = model.newIntVar(0, leaf ? missingCap : 0, "missing_"+i);
            if (leaf) { leafMissing.add(missing[i]); leafDistances.add(distances[i]); }
            var balance = LinearExpr.newBuilder();
            var demand = LinearExpr.newBuilder();
            for (int j = 0; j < variables[i].length; j++) {
                int r = variables[i][j]; long coefficient = coefficients[i][j];
                if (r < 0 || r >= recipes) return new long[] {MODEL_INVALID, 0};
                balance.addTerm(firings[r], coefficient);
                demand.addTerm(firings[r], coefficient);
            }
            long requested = i == 0 ? amount : 0;
            if (producers[i].length > 0) {
                for (int r : producers[i]) {
                    if (r < 0 || r >= recipes) return new long[] {MODEL_INVALID, 0};
                    if (batches[r] > 1) demand.addTerm(active[r], 1-batches[r]);
                }
                model.addLessOrEqual(demand, requested);
            }
            balance.addTerm(missing[i], 1);
            model.addGreaterOrEqual(balance, requested-stocks[i]);
            if (stocks[i] > 0) {
                var used = model.newIntVar(0, stocks[i], "used_"+i);
                allUsed.add(used);
                balance.addTerm(used, 1);
                model.addGreaterOrEqual(balance, requested);
            }
        }
        if (System.nanoTime() >= deadline) return new long[] {UNKNOWN, 0};
        String validation = model.validate();
        if (!validation.isEmpty()) throw new IllegalArgumentException("invalid sparse DAG model: " + validation);
        double seconds = Math.max(0, deadline-System.nanoTime()) / 1_000_000_000.0;
        // Fixed-zero missing variables have no objective effect. Omitting them avoids a native
        // re-solve per intermediate depth when only the final raw leaf can require replenishment.
        var optimum = optimizeRanked(model, firings, leafMissing.toArray(IntVar[]::new),
                leafDistances.stream().mapToInt(Integer::intValue).toArray(), allUsed, false, seconds);
        if (optimum.status != SOLVED && optimum.status != SOLVED_PARTIAL)
            return new long[] {optimum.status, optimum.branches};
        long[] result = new long[2+recipes+items];
        result[0] = optimum.status; result[1] = optimum.branches;
        for (int r = 0; r < recipes; r++) result[2+r] = optimum.attempt.solver.value(firings[r]);
        for (int i = 0; i < items; i++) result[2+recipes+i] = optimum.attempt.solver.value(missing[i]);
        return result;
    }

    /** An initially empty set with no transition entering it without consuming it stays empty. */
    private static void addEmptySiphonSeeds(CpModel model, BoolVar[] active, IntVar[] missing,
            SparseLongMatrix pre, SparseLongMatrix post, int[] groups, long[] stocks) {
        var members = new java.util.LinkedHashMap<Integer, java.util.List<Integer>>();
        for (int i = 0; i < groups.length; i++) members.computeIfAbsent(
                groups[i], ignored -> new java.util.ArrayList<>()).add(i);
        for (var places : members.values()) {
            if (places.stream().anyMatch(i -> stocks[i] > 0)) continue;
            boolean siphon = true, internal = false;
            var consumers = new java.util.ArrayList<Integer>();
            var related = new java.util.TreeSet<Integer>();
            for (int i : places) for (int r : SparseLongMatrix.unionColumn(i, pre, post)) related.add(r);
            for (int r : related) {
                boolean consumes = false, produces = false;
                for (int i : places) { consumes |= pre.get(r, i) > 0; produces |= post.get(r, i) > 0; }
                if (produces && !consumes) { siphon = false; break; }
                internal |= produces && consumes;
                if (consumes) consumers.add(r);
            }
            if (!siphon || !internal) continue;
            IntVar[] seeds = places.stream().map(i -> missing[i]).toArray(IntVar[]::new);
            for (int r : consumers) model.addGreaterOrEqual(LinearExpr.sum(seeds), 1).onlyEnforceIf(active[r]);
        }
    }

    private static RankedOptimum keepWitness(SolveAttempt latest, SolveAttempt incumbent, long branches) {
        if (latest.status == CpSolverStatus.MODEL_INVALID) return new RankedOptimum(latest, MODEL_INVALID, branches);
        if (latest.status == CpSolverStatus.FEASIBLE || latest.status == CpSolverStatus.OPTIMAL) {
            return new RankedOptimum(latest, SOLVED_PARTIAL, branches);
        }
        if (incumbent != null) return new RankedOptimum(incumbent, SOLVED_PARTIAL, branches);
        return new RankedOptimum(latest, optimalStatusCode(latest.status), branches);
    }

    /** Optimal objectives are fixed only with proof; an unfinished objective retains its witness. */
    private static RankedOptimum optimizeRanked(CpModel model, IntVar[] firings, IntVar[] missing,
            int[] distances, java.util.List<IntVar> allUsed, boolean cyclic, double maxSeconds) {
        long deadline = deadlineNanos(maxSeconds);
        CpModel zero = model.getClone();
        zero.addEquality(LinearExpr.sum(missing), 0L);
        zero.minimize(LinearExpr.sum(firings));
        // Zero-missing is a shortcut, not a prerequisite for returning a replenishment plan.
        long zeroDeadline = cyclic ? Math.min(deadline,
                deadlineNanos(Math.min(0.05D, maxSeconds / 4.0D))) : deadline;
        SolveAttempt attempt = solveOptimal(zero, zeroDeadline, cyclic);
        long branches = attempt.branches;
        if (attempt.status == CpSolverStatus.FEASIBLE || attempt.status == CpSolverStatus.MODEL_INVALID) {
            return keepWitness(attempt, null, branches);
        }
        SolveAttempt incumbent = null;
        if (attempt.status == CpSolverStatus.OPTIMAL) {
            model.addEquality(LinearExpr.sum(missing), 0L);
            incumbent = attempt;
        } else {
            var tiers = new java.util.TreeMap<Integer, java.util.List<IntVar>>();
            for (int i = 0; i < missing.length; i++) tiers.computeIfAbsent(
                    distances[i], ignored -> new java.util.ArrayList<>()).add(missing[i]);
            for (var tier : tiers.values()) {
                IntVar[] variables = tier.toArray(IntVar[]::new);
                model.minimize(LinearExpr.sum(variables));
                attempt = solveOptimal(model, deadline, cyclic);
                branches = saturatedAdd(branches, attempt.branches);
                if (attempt.status != CpSolverStatus.OPTIMAL) return keepWitness(attempt, incumbent, branches);
                long value = 0L;
                for (IntVar variable : variables) value = Math.addExact(value, attempt.solver.value(variable));
                model.addEquality(LinearExpr.sum(variables), value);
                model.clearObjective();
                incumbent = attempt;
            }
            model.minimize(LinearExpr.sum(firings));
            attempt = solveOptimal(model, deadline, cyclic);
            branches = saturatedAdd(branches, attempt.branches);
            if (attempt.status != CpSolverStatus.OPTIMAL) return keepWitness(attempt, incumbent, branches);
            incumbent = attempt;
        }
        long executionOptimum = 0L;
        for (IntVar firing : firings) executionOptimum = Math.addExact(executionOptimum, attempt.solver.value(firing));
        model.addEquality(LinearExpr.sum(firings), executionOptimum);
        model.clearObjective();
        if (!allUsed.isEmpty()) {
            model.minimize(LinearExpr.sum(allUsed.toArray(IntVar[]::new)));
            attempt = solveOptimal(model, deadline, cyclic);
            branches = saturatedAdd(branches, attempt.branches);
            if (attempt.status != CpSolverStatus.OPTIMAL) return keepWitness(attempt, incumbent, branches);
        }
        return new RankedOptimum(attempt, SOLVED, branches);
    }

    /**
     * Redundant sums of the existing balance rows. Cancelling internal circulation explicitly
     * avoids enormous one-unit-at-a-time domain propagation around a deficient conservative SCC.
     * No bound on the number of valid firings is invented; unsafe int64 rows are simply omitted.
     */
    private static void addGroupBalances(
            CpModel model, IntVar[] firings, java.util.Map<Integer, java.util.Map<Integer, IntVar>> finiteUseBatches, IntVar[] missing,
            SparseLongMatrix consumed, SparseLongMatrix produced, SparseLongMatrix finiteUseAmounts, int[] groups,
            long[] stocks, long[] firingUpperBounds, long missingUpperBound,
            int targetItem, long targetAmount) {
        var members = new java.util.LinkedHashMap<Integer, java.util.List<Integer>>();
        for (int i = 0; i < groups.length; i++) members.computeIfAbsent(
                groups[i], ignored -> new java.util.ArrayList<>()).add(i);
        var safe = java.math.BigInteger.valueOf(Long.MAX_VALUE / 2L);
        for (var group : members.values()) {
            if (group.size() < 2) continue;
            var variables = new java.util.ArrayList<IntVar>();
            var coefficients = new java.util.ArrayList<Long>();
            var magnitude = java.math.BigInteger.valueOf(missingUpperBound)
                    .multiply(java.math.BigInteger.valueOf(group.size()));
            var rhs = java.math.BigInteger.ZERO;
            for (int i : group) {
                variables.add(missing[i]); coefficients.add(1L);
                rhs = rhs.add(java.math.BigInteger.valueOf(i == targetItem ? targetAmount : 0L))
                        .subtract(java.math.BigInteger.valueOf(stocks[i]));
            }
            boolean valid = rhs.abs().compareTo(safe) <= 0;
            for (int r = 0; r < firings.length && valid; r++) {
                var net = java.math.BigInteger.ZERO;
                for (int i : group) {
                    net = net.add(java.math.BigInteger.valueOf(produced.get(r, i)))
                            .subtract(java.math.BigInteger.valueOf(consumed.get(r, i)));
                    if (finiteUseBatches.getOrDefault(r, java.util.Map.of()).get(i) != null) {
                        variables.add(finiteUseBatches.getOrDefault(r, java.util.Map.of()).get(i));
                        coefficients.add(-finiteUseAmounts.get(r, i));
                        magnitude = magnitude.add(java.math.BigInteger.valueOf(finiteUseAmounts.get(r, i))
                                .multiply(java.math.BigInteger.valueOf(firingUpperBounds[r])));
                    }
                }
                magnitude = magnitude.add(net.abs().multiply(java.math.BigInteger.valueOf(firingUpperBounds[r])));
                valid = magnitude.compareTo(safe) <= 0;
                if (valid && net.signum() != 0) {
                    variables.add(firings[r]); coefficients.add(net.longValueExact());
                }
            }
            if (valid) model.addGreaterOrEqual(LinearExpr.weightedSum(variables.toArray(IntVar[]::new),
                    coefficients.stream().mapToLong(Long::longValue).toArray()), rhs.longValueExact());
        }
    }

    /** Necessary startup and proof-derived cuts, plus a componentwise improvement query. */
    private static boolean addPetriRefinement(
            CpModel model, IntVar[] firings, BoolVar[] active, IntVar[] missing,
            SparseLongMatrix consumed, long[] stocks, long[] caps, long[][] unreachable,
            boolean enforceStartup) {
        if (caps.length != 0 && caps.length != missing.length) return false;
        if (caps.length > 0) {
            var smaller = new java.util.ArrayList<com.google.ortools.sat.Literal>();
            for (int i = 0; i < caps.length; i++) {
                if (caps[i] < 0L) return false;
                model.addLessOrEqual(missing[i], caps[i]);
                if (caps[i] > 0L) {
                    BoolVar reduced = model.newBoolVar("reduced_" + i);
                    model.addLessThan(missing[i], caps[i]).onlyEnforceIf(reduced);
                    smaller.add(reduced);
                }
            }
            model.addBoolOr(smaller);
        }
        for (int cut = 0; cut < unreachable.length; cut++) {
            long[] row = unreachable[cut];
            if (row.length != firings.length + missing.length) return false;
            var escape = new java.util.ArrayList<com.google.ortools.sat.Literal>();
            for (int r = 0; r < firings.length; r++) {
                if (row[r] < 0L) return false;
                BoolVar different = model.newBoolVar("cut_" + cut + "_x_" + r);
                model.addDifferent(firings[r], row[r]).onlyEnforceIf(different);
                escape.add(different);
            }
            for (int i = 0; i < missing.length; i++) {
                long testedSupply = row[firings.length + i];
                if (testedSupply < 0L || testedSupply == Long.MAX_VALUE) return false;
                BoolVar more = model.newBoolVar("cut_" + cut + "_supply_" + i);
                model.addGreaterThan(missing[i], testedSupply).onlyEnforceIf(more);
                escape.add(more);
            }
            // An exactly disproved firing vector remains unreachable with any smaller supply.
            // No greedy failure, timeout or bounded-depth failure is allowed to create this row.
            model.addBoolOr(escape);
        }
        if (enforceStartup) {
            var starts = new java.util.ArrayList<com.google.ortools.sat.Literal>();
            BoolVar idle = model.newBoolVar("no_firing");
            model.addEquality(LinearExpr.sum(firings), 0L).onlyEnforceIf(idle);
            starts.add(idle);
            for (int r = 0; r < firings.length; r++) {
                BoolVar first = model.newBoolVar("first_" + r);
                model.addEquality(active[r], 1L).onlyEnforceIf(first);
                for (int i : consumed.rowKeys(r)) {
                    if (consumed.get(r, i) > stocks[i]) {
                        model.addGreaterOrEqual(missing[i], consumed.get(r, i) - stocks[i]).onlyEnforceIf(first);
                    }
                }
                starts.add(first);
            }
            model.addBoolOr(starts);
        }
        return true;
    }

    /**
     * Selects one already-proven feedback prefix for a fixed firing vector.
     *
     * <p>The caller supplies executable {@code required} markings generated by its Petri-net
     * certificate and an overflow-free lexicographic rank. CP-SAT materializes the selected
     * requirement and its shortage with one-hot Booleans; no firing sequence is time-expanded.</p>
     *
     * @return {@code [status, branches, selectedOption]}
     */
    public static long[] chooseFeedbackOption(
            long[][] requirements,
            long[] stocks,
            long[] scoreRanks,
            double maxSeconds) {
        int optionCount = requirements.length;
        if (optionCount == 0 || optionCount != scoreRanks.length) {
            return new long[] {MODEL_INVALID, 0L};
        }
        int itemCount = stocks.length;
        var model = new CpModel();
        var selected = new BoolVar[optionCount];
        for (int option = 0; option < optionCount; option++) {
            if (requirements[option].length != itemCount || scoreRanks[option] < 0L) {
                return new long[] {MODEL_INVALID, 0L};
            }
            selected[option] = model.newBoolVar("feedback_" + option);
        }
        model.addExactlyOne(selected);

        for (int item = 0; item < itemCount; item++) {
            long maximum = 0L;
            long[] coefficients = new long[optionCount];
            for (int option = 0; option < optionCount; option++) {
                long requirement = requirements[option][item];
                if (requirement < 0L) return new long[] {MODEL_INVALID, 0L};
                coefficients[option] = requirement;
                maximum = Math.max(maximum, requirement);
            }
            IntVar chosenRequirement = model.newIntVar(
                    0L, maximum, "feedback_required_" + item);
            model.addEquality(
                    chosenRequirement, LinearExpr.weightedSum(selected, coefficients));
            long maximumShortage = Math.max(0L, maximum - stocks[item]);
            IntVar shortage = model.newIntVar(
                    0L, maximumShortage, "feedback_shortage_" + item);
            model.addMaxEquality(
                    shortage,
                    new com.google.ortools.sat.LinearArgument[] {
                            LinearExpr.affine(chosenRequirement, 1L, -stocks[item]),
                            LinearExpr.constant(0L)
                    });
        }
        model.minimize(LinearExpr.weightedSum(selected, scoreRanks));

        String validation = model.validate();
        if (!validation.isEmpty()) {
            throw new IllegalArgumentException("invalid CP-SAT feedback model: " + validation);
        }
        var solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(maxSeconds);
        solver.getParameters().setNumWorkers(1);
        solver.getParameters().setRandomSeed(0);
        CpSolverStatus status = solver.solve(model);
        long statusCode = optimalStatusCode(status);
        if (statusCode != SOLVED) {
            return new long[] {statusCode, Math.max(0L, solver.numBranches())};
        }
        for (int option = 0; option < optionCount; option++) {
            if (solver.booleanValue(selected[option])) {
                return new long[] {SOLVED, Math.max(0L, solver.numBranches()), option};
            }
        }
        return new long[] {MODEL_INVALID, Math.max(0L, solver.numBranches())};
    }

    /** Objectives are certificates only after optimality, not merely after finding a feasible row. */
    private static long optimalStatusCode(CpSolverStatus status) {
        return switch (status) {
            case OPTIMAL -> SOLVED;
            case INFEASIBLE -> INFEASIBLE;
            case MODEL_INVALID -> MODEL_INVALID;
            default -> UNKNOWN;
        };
    }

    private static SolveAttempt solveOptimal(CpModel model, long deadlineNanos) {
        return solveOptimal(model, deadlineNanos, false);
    }

    private static SolveAttempt solveOptimal(CpModel model, long deadlineNanos, boolean cyclic) {
        long remaining = deadlineNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : deadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            return new SolveAttempt(null, CpSolverStatus.UNKNOWN, 0L);
        }
        var solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(remaining / 1_000_000_000.0D);
        if (cyclic) {
            // OR-Tools 9.15's default propagation can remain inside a large-domain cyclic
            // fixpoint past its wall-clock limit. This combination yields on deficient SCCs.
            solver.getParameters().setNewLinearPropagation(false);
            solver.getParameters().setCpModelProbingLevel(0);
        }
        solver.getParameters().setNumWorkers(1);
        solver.getParameters().setRandomSeed(0);
        CpSolverStatus status = solver.solve(model);
        return new SolveAttempt(solver, status, Math.max(0L, solver.numBranches()));
    }

    private static long deadlineNanos(double maxSeconds) {
        return deadlineNanos(maxSeconds, System.nanoTime());
    }

    static long deadlineNanos(double maxSeconds, long now) {
        if (!(maxSeconds > 0.0D) || Double.isNaN(maxSeconds)) return now;
        double requestedNanos = maxSeconds * 1_000_000_000.0D;
        if (!Double.isFinite(requestedNanos) || requestedNanos >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long budget = Math.max(1L, (long) requestedNanos);
        // nanoTime has an arbitrary origin and may be negative. MAX_VALUE - now would
        // overflow in that case and silently turn a short native deadline into infinity.
        return now >= Long.MAX_VALUE - budget ? Long.MAX_VALUE : now + budget;
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private record SolveAttempt(CpSolver solver, CpSolverStatus status, long branches) {
    }

}
