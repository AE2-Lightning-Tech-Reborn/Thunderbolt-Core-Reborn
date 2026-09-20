package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sparse, whole-model CP-SAT compilation for ordinary acyclic primary dependencies. */
final class CpSatSparseDag {
    private CpSatSparseDag() {}

    // The threshold chooses a representation, not an admission limit. Small models retain their
    // established search order; cycles and stateful semantics still use the ranked implementation.
    static <K> CpSatRankedFlowSolver.Result<K> trySolve(CraftGraph<K> graph, K target, long amount,
            long minimumCells) {
        long start = System.nanoTime();
        long nanos = PlanningCancellation.remainingNanos(
                PlanningCancellation.checkpointIfBound() ? Long.MAX_VALUE : 3_000_000_000L);
        var keys = new ArrayList<K>();
        var ids = new LinkedHashMap<K, Integer>();
        var distances = new ArrayList<Integer>();
        var patterns = new ArrayList<CraftPattern<K>>();
        var seenPatterns = java.util.Collections.newSetFromMap(new IdentityHashMap<CraftPattern<K>, Boolean>());
        var inputs = new ArrayList<Map<Integer, Long>>();
        var outputs = new ArrayList<Integer>();
        keys.add(target); ids.put(target, 0); distances.add(0);
        for (int cursor = 0; cursor < keys.size(); cursor++) {
            PlanningCancellation.check();
            for (var p : graph.patternsFor(keys.get(cursor))) {
                PlanningCancellation.check();
                if (System.nanoTime()-start >= nanos) return status(CpSatRankedFlowSolver.Status.UNKNOWN);
                if (!seenPatterns.add(p)) continue;
                if (!p.byproducts().isEmpty() || !p.exactOutputAmount().equals(BigInteger.valueOf(p.outputAmount()))
                        || Sat.isSaturated(p.outputAmount())) return null;
                var pre = new LinkedHashMap<Integer, Long>();
                for (var input : p.inputs()) {
                    PlanningCancellation.check();
                    if (input.returned() || input.remainder() != null || input.reusableStockSource() != null
                            || !input.exactAmount().equals(BigInteger.valueOf(input.amount()))) return null;
                    Integer id = ids.get(input.key());
                    if (id == null) {
                        id = keys.size(); ids.put(input.key(), id); keys.add(input.key());
                        distances.add(distances.get(cursor)+1);
                    }
                    long total = Sat.add(pre.getOrDefault(id, 0L), input.amount());
                    if (Sat.isSaturated(total)) return null;
                    pre.put(id, total);
                }
                patterns.add(p); outputs.add(cursor); inputs.add(pre);
            }
        }
        int itemCount = keys.size(), recipeCount = patterns.size();
        if (recipeCount == 0 || (long) itemCount * recipeCount < minimumCells) return null;
        var consumers = new ArrayList<List<Integer>>(itemCount);
        var producers = new ArrayList<List<Integer>>(itemCount);
        var rows = new ArrayList<Map<Integer, Long>>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            PlanningCancellation.check();
            consumers.add(new ArrayList<>()); producers.add(new ArrayList<>()); rows.add(new LinkedHashMap<>());
        }
        int[] indegree = new int[itemCount];
        long[] batches = new long[recipeCount], upper = new long[recipeCount];
        for (int r = 0; r < recipeCount; r++) {
            PlanningCancellation.check();
            int output = outputs.get(r);
            long batch = patterns.get(r).outputAmount(), max = batch;
            batches[r] = batch;
            producers.get(output).add(r); rows.get(output).put(r, batch);
            for (var input : inputs.get(r).entrySet()) {
                int item = input.getKey();
                if (item == output) return null;
                consumers.get(item).add(output); indegree[output]++;
                rows.get(item).put(r, -input.getValue()); max = Math.max(max, input.getValue());
            }
            // Match the existing ranked model's signed-long activity safety domain.
            upper[r] = Math.max(1, Sat.SAT / max / recipeCount);
        }
        var ready = new ArrayDeque<Integer>();
        for (int i = 0; i < itemCount; i++) if (indegree[i] == 0) ready.add(i);
        int visited = 0;
        while (!ready.isEmpty()) {
            PlanningCancellation.check();
            int item = ready.removeFirst(); visited++;
            for (int output : consumers.get(item)) if (--indegree[output] == 0) ready.add(output);
        }
        if (visited != itemCount) return null;
        int[][] variables = new int[itemCount][], producerIds = new int[itemCount][];
        long[][] coefficients = new long[itemCount][];
        long[] stocks = new long[itemCount];
        int[] distance = new int[itemCount];
        for (int i = 0; i < itemCount; i++) {
            PlanningCancellation.check();
            variables[i] = rows.get(i).keySet().stream().mapToInt(Integer::intValue).toArray();
            coefficients[i] = rows.get(i).values().stream().mapToLong(Long::longValue).toArray();
            producerIds[i] = producers.get(i).stream().mapToInt(Integer::intValue).toArray();
            stocks[i] = graph.stock(keys.get(i)); distance[i] = distances.get(i);
        }
        long remaining = Math.min(nanos - (System.nanoTime()-start), PlanningCancellation.remainingNanos(nanos));
        remaining -= remaining / 4;
        if (remaining <= 0) return status(CpSatRankedFlowSolver.Status.UNKNOWN);
        final long[] raw;
        try {
            raw = CpSatRuntime.solveSparseDag(variables, coefficients, producerIds, batches, upper,
                    stocks, distance, amount, remaining / 1_000_000_000.0);
        } catch (RuntimeException | LinkageError invalid) {
            // Keep router cancellation visible, including cancellation concurrent with native work.
            PlanningCancellation.check();
            return status(CpSatRankedFlowSolver.Status.INVALID);
        }
        PlanningCancellation.check();
        if (raw.length < 2) return status(CpSatRankedFlowSolver.Status.INVALID);
        if (raw[0] == 1) return status(CpSatRankedFlowSolver.Status.UNKNOWN); // bounded numeric domain
        if (raw[0] == 2) return status(CpSatRankedFlowSolver.Status.INVALID);
        if (raw[0] != 0 && raw[0] != 4) return status(CpSatRankedFlowSolver.Status.UNKNOWN);
        if (raw.length != 2+recipeCount+itemCount) return status(CpSatRankedFlowSolver.Status.INVALID);
        var counts = new IdentityHashMap<CraftPattern<K>, Long>();
        for (int r = 0; r < recipeCount; r++) {
            long n = raw[2+r];
            if (n < 0 || n > upper[r]) return status(CpSatRankedFlowSolver.Status.INVALID);
            if (n > 0) counts.put(patterns.get(r), n);
        }
        // Independent BigInteger balance and all-enabled-orders DAG certificate. Missing material
        // is recomputed from original stocks, never trusted from the native solver's output.
        var plan = MaterialDagReplay.tryLeafMissingPlan(graph, counts, target, amount);
        if (plan == null) return status(CpSatRankedFlowSolver.Status.INVALID);
        for (int i = 0; i < itemCount; i++) {
            long missing = raw[2+recipeCount+i];
            if (missing < 0 || Sat.isSaturated(missing)
                    || plan.missing().getOrDefault(keys.get(i), 0L) > missing)
                return status(CpSatRankedFlowSolver.Status.INVALID);
        }
        if (raw[0] == 4) plan = new CraftPlan<>(plan.supported(), plan.feasible(), plan.firings(), plan.usedStock(),
                plan.usedReusableStock(), plan.missing(), plan.grossDemand(), plan.itemsProcessed(), true);
        return new CpSatRankedFlowSolver.Result<>(CpSatRankedFlowSolver.Status.SOLVED, plan, raw[1]);
    }

    private static <K> CpSatRankedFlowSolver.Result<K> status(CpSatRankedFlowSolver.Status status) {
        return CpSatRankedFlowSolver.Result.status(status);
    }
}
