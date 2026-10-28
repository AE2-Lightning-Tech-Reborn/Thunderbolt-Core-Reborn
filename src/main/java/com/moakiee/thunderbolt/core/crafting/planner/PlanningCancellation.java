package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.concurrent.CancellationException;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;

/** Cooperative cancellation checkpoint shared by adapter and pure planner hot loops. */
public final class PlanningCancellation {
    private static final ThreadLocal<PlanningAttemptContext> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Long> OPTIONAL_DEADLINE = new ThreadLocal<>();
    private static final ThreadLocal<Long> WITNESS_CPU_DEADLINE = new ThreadLocal<>();

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
        Long witness = WITNESS_CPU_DEADLINE.get();
        if (witness != null && WitnessClock.cpuNanos() - witness >= 0L) throw new OptionalWorkLimit();
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
        Long witness = WITNESS_CPU_DEADLINE.get();
        if (witness != null) boundedCap = Math.min(boundedCap, Math.max(0L, witness - WitnessClock.cpuNanos()));
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
        long deadline = System.nanoTime() + duration;
        // Preserve the parent's absolute cutoff; time spent computing a remaining slice must
        // not push a nested proof beyond the scope it inherited.
        if (previous != null && deadline - previous > 0L) deadline = previous;
        OPTIONAL_DEADLINE.set(deadline);
        return () -> {
            if (previous == null) OPTIONAL_DEADLINE.remove();
            else OPTIONAL_DEADLINE.set(previous);
        };
    }

    /** Install an absolute stage cutoff without adding elapsed installation time to it. */
    static Scope limitOptionalWorkUntil(long deadline) {
        Scope scope = limitOptionalWork(Math.max(0L, deadline - System.nanoTime()));
        Long installed = OPTIONAL_DEADLINE.get();
        if (installed != null && installed - deadline > 0L) OPTIONAL_DEADLINE.set(deadline);
        return scope;
    }

    /** Pure Java witnesses use CPU time when no caller imposes a latency deadline. */
    static Scope limitWitnessWork(long nanos) {
        if (CURRENT.get() != null || WitnessClock.cpuNanos() < 0L) return limitOptionalWork(nanos);
        Long previous = WITNESS_CPU_DEADLINE.get();
        long duration = remainingNanos(nanos);
        WITNESS_CPU_DEADLINE.set(WitnessClock.cpuNanos() + duration);
        return () -> {
            if (previous == null) WITNESS_CPU_DEADLINE.remove();
            else WITNESS_CPU_DEADLINE.set(previous);
        };
    }

    static long witnessClockNanos() {
        long cpu = CURRENT.get() == null ? WitnessClock.cpuNanos() : -1L;
        return cpu < 0L ? System.nanoTime() : cpu;
    }

    private static final class WitnessClock {
        private static final java.lang.management.ThreadMXBean THREADS =
                java.lang.management.ManagementFactory.getThreadMXBean();

        static long cpuNanos() {
            return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1L;
        }
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
