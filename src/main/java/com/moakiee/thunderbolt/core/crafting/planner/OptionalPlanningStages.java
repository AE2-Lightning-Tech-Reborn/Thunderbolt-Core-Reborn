package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.function.IntPredicate;

import com.moakiee.thunderbolt.core.crafting.planner.CraftPlannerV2.PlanningSession;
import static com.moakiee.thunderbolt.core.crafting.planner.CraftPlannerV2.planCore;

/** Bounded recovery and feasible-plan improvement around the core planner's certified result. */
final class OptionalPlanningStages {
    private OptionalPlanningStages() {}

    static <K> PlanningResult<K> planDetailed(
            CraftGraph<K> graph,
            K target,
            long amount,
            int visitCap,
            int searchWorkBudget,
            int reachableWork,
            PlanningSession<K> session) {
        long started = System.nanoTime();
        PlanningResult<K> initial = planCore(graph, target, amount, visitCap, searchWorkBudget, reachableWork, session);
        boolean terminalRecovery = TerminalBatchRecovery.hasBoundedFootprint(graph, target, initial.diagnostics());
        if (session.refineMissing && !initial.plan().feasible()
                && session.searchWorkBudget.remaining > 0
                && (reachableWork <= SmallConservativeSearch.MAX_WORK || terminalRecovery)) {
            long recoveryLimit = terminalRecovery
                    ? TerminalBatchRecovery.MAX_NANOS : SmallConservativeSearch.MAX_NANOS;
            long allowance = Math.min(recoveryLimit - session.conservativeSearchNanos,
                    PlanningCancellation.remainingNanos(Long.MAX_VALUE) / 8L);
            if (allowance > 0) {
                long recoveryStarted = System.nanoTime();
                int workBefore = session.searchWorkBudget.remaining;
                boolean[] recoveryWorkRejected = {false};
                CraftPlan<K> recovered = null;
                try (var ignored = PlanningCancellation.limitOptionalWork(allowance)) {
                    IntPredicate recoveryWork = work -> {
                        if (session.searchWorkBudget.tryConsume(work)) return true;
                        recoveryWorkRejected[0] = true;
                        return false;
                    };
                    if (reachableWork <= SmallConservativeSearch.MAX_WORK)
                        recovered = SmallConservativeSearch.tryPlan(graph, target, amount,
                                SmallConservativeSearch.MAX_STATES, () -> recoveryWork.test(1));
                    if (recovered == null && terminalRecovery && !recoveryWorkRejected[0]
                            && session.searchWorkBudget.remaining > 0)
                        recovered = TerminalBatchRecovery.tryPlan(graph, target, amount,
                                SmallConservativeSearch.MAX_STATES, recoveryWork);
                } catch (PlanningCancellation.OptionalWorkLimit exhausted) {
                    // Optional recovery never invalidates the already-verified missing plan.
                } finally {
                    session.conservativeSearchNanos += Math.max(0L, System.nanoTime() - recoveryStarted);
                }
                CraftPlan<K> selected = recovered == null ? initial.plan() : recovered;
                if (recovered == null && recoveryWorkRejected[0])
                    selected = CraftPlannerV2.markBudgetExhausted(selected);
                initial = new PlanningResult<>(selected,
                        initial.diagnostics().withAdditionalSearchWork(
                                workBefore - session.searchWorkBudget.remaining, System.nanoTime() - started,
                                recoveryWorkRejected[0]));
            }
        }
        int probeLimit = FeasibleConsumptionOptimizer.MAX_PROBES;
        if (!session.optimizeFeasible || !initial.plan().feasible() || amount <= 0
                || amount >= Sat.SAT
                || session.consumptionOptimizationProbes >= probeLimit)
            return initial;
        // Fixed ordinary chains need no optional scan or second planning pass.
        if (initial.diagnostics().contendedOutputs() == 0) return initial;
        long remaining = PlanningCancellation.remainingNanos(Long.MAX_VALUE);
        long reserve = Math.max(FeasibleConsumptionOptimizer.EXPORT_RESERVE_NANOS, remaining / 10);
        long allowance = Math.min(FeasibleConsumptionOptimizer.MAX_NANOS - session.consumptionOptimizationNanos,
                remaining - reserve);
        if (allowance <= 0 || session.searchWorkBudget.remaining <= 0) return initial;
        long optimizationStarted = System.nanoTime();
        FeasibleConsumptionOptimizer.Result<K> optimized;
        CraftPlan<K> seed = initial.plan();
        int mixedProbes = 0, mixedImprovements = 0;
        try (var ignored = PlanningCancellation.limitOptionalWork(allowance)) {
            // Apply the established lower-bound certificate before allocating a mixed-batch search.
            // Keep this extra check restricted to the small portfolios accepted by those helpers.
            int targetRoutes = graph.patternsFor(target).size();
            if (targetRoutes >= 2 && targetRoutes <= 6
                    && FeasibleConsumptionOptimizer.targetOnlyBound(graph, target, amount, initial.plan()))
                return initial;
            var mixed = TwoRouteBatchOptimizer.solve(graph, target, amount, seed,
                    session.searchWorkBudget::tryConsume);
            if (mixed.status() == BatchOptimizationResult.Status.UNSUPPORTED) {
                mixed = MultiRouteBatchOptimizer.solve(graph, target, amount, seed,
                        session.searchWorkBudget::tryConsume);
            }
            if (mixed.plan() != seed) {
                seed = mixed.plan();
                mixedProbes = 1;
                mixedImprovements = 1;
            }
            // Extra inventory may reduce executions outside the local stock domain. It
            // cannot do so once the raw-target witness reaches the absolute yield bound.
            if (canSkipGeneralOptimization(graph, target, amount, initial.plan(), mixed)) {
                optimized = new FeasibleConsumptionOptimizer.Result<>(seed, 0, 0, 0);
            } else {
                optimized = FeasibleConsumptionOptimizer.optimize(graph, target, amount, seed,
                        probeLimit - session.consumptionOptimizationProbes - mixedProbes,
                        candidateGraph -> {
                            // Charge the reachable local region, not the whole integration pack.
                            var probe = new PlanningSession<K>();
                            int localWork = probe.reachableWork(candidateGraph, target);
                            if (!session.searchWorkBudget.tryConsume(localWork)) return null;
                            probe.optimizeFeasible = false;
                            probe.refineMissing = false;
                            probe.lowWidthWorkBudget = session.lowWidthWorkBudget;
                            probe.searchWorkBudget = session.searchWorkBudget;
                            probe.resolutionWorkBudget = session.resolutionWorkBudget;
                            probe.fallbackWorkBudget = session.fallbackWorkBudget;
                            // Stock-dependent capacities are rebuilt; sharing PreparedGraph would reuse
                            // the incumbent's larger stock. Patterns and all work limits remain shared.
                            return planCore(candidateGraph, target, amount, visitCap, searchWorkBudget,
                                    localWork, probe).plan();
                        }, session.consumptionIndex, session.searchWorkBudget::tryConsume);
            }
        } catch (PlanningCancellation.OptionalWorkLimit exhausted) {
            // The bounded mixed-batch stage may expire before the general optimizer starts.
            // Retain its last completed witness, but never swallow external cancellation.
            optimized = new FeasibleConsumptionOptimizer.Result<>(seed, 0, 0, 0);
        } finally {
            session.consumptionOptimizationNanos += Math.max(0L, System.nanoTime() - optimizationStarted);
        }
        int probes = optimized.probes() + mixedProbes;
        int improvements = optimized.improvements() + mixedImprovements;
        session.consumptionOptimizationProbes += probes;
        return new PlanningResult<>(optimized.plan(), initial.diagnostics().withConsumptionOptimization(
                probes, improvements, System.nanoTime() - optimizationStarted,
                System.nanoTime() - started));
    }

    static <K> boolean canSkipGeneralOptimization(CraftGraph<K> graph, K target, long amount,
            CraftPlan<K> initial, BatchOptimizationResult<K> mixed) {
        return mixed.status() == BatchOptimizationResult.Status.COMPLETE
                && mixed.plan().usedStock().getOrDefault(target, 0L) == 0L
                && (graph.hasSameOrdinaryStock(initial.usedStock())
                    || (mixed.plan() != initial
                        && rawTargetExecutionBound(graph, target, amount, mixed.plan())));
    }

    /**
     * Use only a returned optimal replacement from COMPLETE, rather than inferring
     * a new certificate from a retained incumbent. At the absolute firing lower
     * bound no inventory expansion can save executions, while every
     * equal-count componentwise improvement is already inside those local limits.
     */
    private static <K> boolean rawTargetExecutionBound(CraftGraph<K> graph, K target,
            long amount, CraftPlan<K> plan) {
        if (amount <= 0 || graph.stock(target) != 0 || graph.hasByproducts()
                || graph.hasTagConversions()) return false;
        BigInteger largest = BigInteger.ZERO;
        for (var route : graph.patternsFor(target)) {
            PlanningCancellation.check();
            if (route.executionCost() != 1 || !route.byproducts().isEmpty()) return false;
            for (var input : route.inputs()) {
                if (input.returned() || input.remainder() != null || input.reusableStockSource() != null
                        || input.key().equals(target) || !graph.patternsFor(input.key()).isEmpty()) return false;
            }
            largest = largest.max(route.exactOutputAmount());
        }
        return largest.signum() > 0 && plan.executionCount().equals(
                BigInteger.valueOf(amount).subtract(BigInteger.ONE)
                        .divide(largest).add(BigInteger.ONE));
    }
}
