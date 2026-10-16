package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;

/** One progress-sensitive deadline shared by every stage of feasible-plan optimization. */
final class FeasibleOptimizationBudget implements PlanningCancellation.Scope {
    private final long started = System.nanoTime();
    private final long totalNanos;
    private final long stallNanos;
    private long lastGain = started;
    private PlanningCancellation.Scope scope;

    FeasibleOptimizationBudget(long totalNanos) {
        this(totalNanos, FeasibleConsumptionOptimizer.STALL_NANOS);
    }

    FeasibleOptimizationBudget(long totalNanos, long stallNanos) {
        this.totalNanos = Math.max(0L, totalNanos);
        this.stallNanos = Math.max(0L, stallNanos);
        scope = PlanningCancellation.limitOptionalWorkUntil(deadline());
    }

    long remainingNanos() {
        long now = System.nanoTime();
        long remaining = Math.max(0L, totalNanos - (now - started));
        return stallNanos == 0L ? remaining
                : Math.min(remaining, Math.max(0L, stallNanos - (now - lastGain)));
    }

    boolean stalled() {
        return stallNanos != 0L && System.nanoTime() - lastGain >= stallNanos;
    }

    private long deadline() {
        long total = started + totalNanos;
        long stall = lastGain + stallNanos;
        return stallNanos == 0L || total - stall <= 0L ? total : stall;
    }

    /** Call after nested candidate scopes have closed, and only for a verified improvement. */
    void improved(CraftPlan<?> before, CraftPlan<?> candidate) {
        PlanningCancellation.check();
        BigInteger executions = before.executionCount();
        BigInteger gain = executions.subtract(candidate.executionCount());
        PlanningCancellation.check();
        if (stallNanos == 0L || gain.signum() <= 0
                || gain.multiply(BigInteger.valueOf(100L)).compareTo(executions.multiply(
                        BigInteger.valueOf(FeasibleConsumptionOptimizer.MIN_GAIN_PERCENT))) < 0) return;
        // Release our old stall cutoff before installing the renewed one. Keeping an enclosing
        // fixed stall scope would prevent real progress from renewing it. The original parent
        // deadline and this budget's elapsed total allowance still bound the new scope.
        scope.close();
        scope = null;
        lastGain = System.nanoTime();
        scope = PlanningCancellation.limitOptionalWorkUntil(deadline());
    }

    @Override
    public void close() {
        if (scope != null) {
            scope.close();
            scope = null;
        }
    }
}
