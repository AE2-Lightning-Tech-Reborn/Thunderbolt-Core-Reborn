package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Factors mandatory common inputs from shallow alternatives before integral flow. */
final class CommonInputFlow {
    private CommonInputFlow() {}

    static <K> UnitMaterialFlow.Result<K> trySolve(CraftGraph<K> graph, List<K> items,
            List<CraftPattern<K>> patterns, Map<K, Long> demand, Map<K, Long> supply,
            BoundedIntegerLinearSolver.WorkBudget budget) {
        if (patterns.isEmpty()) return null;
        if (!budget.tryConsume(items.size() + (long) patterns.size())) return cutoff();
        var groups = new LinkedHashMap<K, List<CraftPattern<K>>>();
        for (var pattern : patterns) {
            PlanningCancellation.check();
            if (!pattern.byproducts().isEmpty()
                    || !pattern.exactOutputAmount().equals(BigInteger.valueOf(pattern.outputAmount()))) return null;
            groups.computeIfAbsent(pattern.output(), ignored -> new ArrayList<>()).add(pattern);
        }
        var inputs = new IdentityHashMap<CraftPattern<K>, Map<K, Long>>();
        for (var pattern : patterns) {
            var amounts = new HashMap<K, Long>();
            if (!budget.tryConsume(pattern.inputs().size())) return cutoff();
            for (var input : pattern.inputs()) {
                PlanningCancellation.check();
                // All outputs are terminal within this component. Their batch counts are fixed
                // by external demand, so common input consumption cannot change with routing.
                if (groups.containsKey(input.key()) || !graph.patternsFor(input.key()).isEmpty()
                        || input.returned() || input.remainder() != null || input.reusableStockSource() != null
                        || !input.exactAmount().equals(BigInteger.valueOf(input.amount()))) return null;
                long prior = amounts.getOrDefault(input.key(), 0L);
                if (input.amount() > Sat.SAT-prior) return null;
                amounts.put(input.key(), prior+input.amount());
            }
            inputs.put(pattern, amounts);
        }
        var projected = CraftGraph.<K>builder();
        var projectedPatterns = new ArrayList<CraftPattern<K>>();
        var originalPatterns = new IdentityHashMap<CraftPattern<K>, CraftPattern<K>>();
        var fixed = new IdentityHashMap<CraftPattern<K>, Long>();
        var required = new HashMap<K, Long>();
        var commonConsumption = new HashMap<K, BigInteger>();
        for (var group : groups.entrySet()) {
            PlanningCancellation.check();
            K output = group.getKey();
            var alternatives = group.getValue();
            long batch = alternatives.get(0).outputAmount();
            if (batch <= 0 || alternatives.stream().anyMatch(p -> p.outputAmount() != batch)) return null;
            BigInteger deficit = BigInteger.valueOf(demand.getOrDefault(output, 0L))
                    .subtract(BigInteger.valueOf(graph.stock(output)))
                    .subtract(BigInteger.valueOf(supply.getOrDefault(output, 0L)));
            if (deficit.signum() <= 0) continue;
            long count = deficit.add(BigInteger.valueOf(batch-1)).divide(BigInteger.valueOf(batch)).longValueExact();
            var common = new HashMap<>(inputs.get(alternatives.get(0)));
            for (var pattern : alternatives) {
                var amount = inputs.get(pattern);
                common.replaceAll((key, value) -> Math.min(value, amount.getOrDefault(key, 0L)));
                common.values().removeIf(value -> value == 0);
            }
            common.forEach((key, value) -> commonConsumption.merge(key,
                    BigInteger.valueOf(value).multiply(BigInteger.valueOf(count)), BigInteger::add));
            CraftPattern<K> free = null;
            var residuals = new IdentityHashMap<CraftPattern<K>, Map<K, Long>>();
            for (var pattern : alternatives) {
                var residual = new HashMap<K, Long>();
                inputs.get(pattern).forEach((key, value) -> {
                    long extra = value-common.getOrDefault(key, 0L);
                    if (extra > 0) residual.put(key, extra);
                });
                residuals.put(pattern, residual);
                if (residual.isEmpty() && free == null) free = pattern;
            }
            if (free != null) {
                // Same primary batch, no side effects, and no extra input: this route dominates
                // every alternative after the common consumption has been paid.
                fixed.put(free, count);
                continue;
            }
            required.put(output, count);
            for (var pattern : alternatives) {
                var residual = residuals.get(pattern);
                if (residual.size() != 1) return null;
                var entry = residual.entrySet().iterator().next();
                var edge = new CraftPattern<>(output, 1, List.of(CraftInput.of(entry.getKey(), entry.getValue())), null);
                projected.pattern(edge);
                projectedPatterns.add(edge);
                originalPatterns.put(edge, pattern);
            }
        }
        boolean fixedDeficit = false;
        for (K key : items) {
            PlanningCancellation.check();
            if (groups.containsKey(key)) continue;
            if (!graph.patternsFor(key).isEmpty()) return null;
            BigInteger available = BigInteger.valueOf(graph.stock(key)).add(BigInteger.valueOf(supply.getOrDefault(key, 0L)));
            BigInteger consumed = BigInteger.valueOf(demand.getOrDefault(key, 0L))
                    .add(commonConsumption.getOrDefault(key, BigInteger.ZERO));
            if (available.compareTo(BigInteger.valueOf(Sat.SAT)) > 0
                    || consumed.compareTo(BigInteger.valueOf(Sat.SAT)) > 0) return null;
            projected.stock(key, available.longValueExact());
            required.put(key, consumed.longValueExact());
            fixedDeficit |= consumed.compareTo(available) > 0;
        }
        if (projectedPatterns.isEmpty()) {
            return fixedDeficit ? new UnitMaterialFlow.Result<>(BoundedIntegerLinearSolver.Status.INFEASIBLE, null, Map.copyOf(fixed))
                    : new UnitMaterialFlow.Result<>(BoundedIntegerLinearSolver.Status.SOLVED, Map.copyOf(fixed), null);
        }
        var result = UnitMaterialFlow.trySolveWithLeafSupply(projected.build(), items, projectedPatterns,
                required, Map.of(), budget);
        if (result == null || result.status() == BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED) return result;
        var counts = new IdentityHashMap<>(fixed);
        var variable = result.firings() != null ? result.firings() : result.leafSupplyFirings();
        if (variable == null) return result;
        variable.forEach((pattern, count) -> counts.put(originalPatterns.get(pattern), count));
        return result.firings() != null ? new UnitMaterialFlow.Result<>(result.status(), Map.copyOf(counts), null)
                : new UnitMaterialFlow.Result<>(result.status(), null, Map.copyOf(counts));
    }

    private static <K> UnitMaterialFlow.Result<K> cutoff() {
        PlanningCancellation.check();
        return new UnitMaterialFlow.Result<>(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, null, null);
    }
}
