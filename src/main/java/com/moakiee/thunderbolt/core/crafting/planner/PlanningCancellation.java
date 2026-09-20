package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.concurrent.CancellationException;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;

/** Cooperative cancellation checkpoint shared by adapter and pure planner hot loops. */
public final class PlanningCancellation {
    private static final ThreadLocal<PlanningAttemptContext> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Long> OPTIONAL_DEADLINE = new ThreadLocal<>();

    private PlanningCancellation() {
    }

    public static void check() {
        var context = CURRENT.get();
        if (context != null) {
            context.checkpoint();
        } else if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("crafting calculation interrupted");
        }
        Long optional = OPTIONAL_DEADLINE.get();
        if (optional != null && System.nanoTime() - optional >= 0L) throw new OptionalWorkLimit();
    }

    /** Runs the bound planning checkpoint and reports whether this is candidate work. */
    public static boolean checkpointIfBound() {
        var context = CURRENT.get();
        if (context == null) {
            return false;
        }
        context.checkpoint();
        return true;
    }

    public static void report(PlanningDiagnosticSnapshot snapshot) {
        var context = CURRENT.get();
        if (context != null) {
            context.report(snapshot);
        }
    }

    /** Remaining candidate time, capped for a bounded native solver call. */
    static long remainingNanos(long capNanos) {
        check();
        long boundedCap = Math.max(0L, capNanos);
        Long optional = OPTIONAL_DEADLINE.get();
        if (optional != null) boundedCap = Math.min(boundedCap, Math.max(0L, optional - System.nanoTime()));
        var context = CURRENT.get();
        if (context == null) {
            return boundedCap;
        }
        long remaining = context.deadlineNanos() - System.nanoTime();
        return Math.max(0L, Math.min(boundedCap, remaining));
    }

    /** A nested optional stage may stop itself without swallowing user cancellation or router exits. */
    static Scope limitOptionalWork(long nanos) {
        Long previous = OPTIONAL_DEADLINE.get();
        long duration = remainingNanos(nanos);
        OPTIONAL_DEADLINE.set(System.nanoTime() + duration);
        return () -> {
            if (previous == null) OPTIONAL_DEADLINE.remove();
            else OPTIONAL_DEADLINE.set(previous);
        };
    }

    static final class OptionalWorkLimit extends RuntimeException {
        private OptionalWorkLimit() { super(null, null, false, false); }
    }

    public static Scope bind(PlanningAttemptContext context) {
        var previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
