package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;

/** Exercises real input preparation and exception boundaries with a controlled native reply. */
public class CpSatBudgetBoundaryTest {
    private static int nativeCalls;
    private static double nativeSeconds;
    private static Runnable onNativeCall;
    private static PlanningCancellation.Scope expiredScope;

    public static long[] nativeUnknown(long[][] coefficients, long[] minimums, long[] upper,
            int executionVariables, long[][] stockNet, long[] offsets, long[] stockUpper,
            int[] distances, int[] direct, int[] directDistances, double seconds) {
        nativeCalls++;
        nativeSeconds = seconds;
        onNativeCall.run();
        return new long[] {3L, 0L};
    }

    @Test
    void cancellationDuringInputFlatteningStopsBeforeNativeWork() throws Exception {
        var exit = new PlanningExitException("cancel during input preparation");
        var context = new Context(exit);
        try (var bridge = replaceNative(); var bound = PlanningCancellation.bind(context)) {
            var constraints = onFlatten(() -> context.cancelled = true);
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> CpSatIntegerLinearSolver.solve(1, constraints, 2)));
            assertEquals(0, nativeCalls, "cancelled input preparation must not start native work");
        }
    }

    @Test
    void optionalExpiryDuringInputFlatteningStopsBeforeNativeWork() throws Exception {
        try (var bridge = replaceNative()) {
            var constraints = onFlatten(CpSatBudgetBoundaryTest::expireOptionalWork);
            try {
                assertThrows(PlanningCancellation.OptionalWorkLimit.class,
                        () -> CpSatIntegerLinearSolver.solve(1, constraints, 2));
                assertEquals(0, nativeCalls, "expired input preparation must not start native work");
            } finally { closeExpiredScope(); }
        }
    }

    @Test
    void optionalExpiryAtNativeReturnIsNotConvertedToInternalError() throws Exception {
        try (var bridge = replaceNative()) {
            onNativeCall = CpSatBudgetBoundaryTest::expireOptionalWork;
            try {
                assertThrows(PlanningCancellation.OptionalWorkLimit.class,
                        () -> CpSatIntegerLinearSolver.solve(1, List.of(constraint()), 2));
                assertEquals(1, nativeCalls);
            } finally { closeExpiredScope(); }
        }
    }

    @Test
    void interruptedDenseBridgeDoesNotStartSolving() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        Thread.currentThread().interrupt();
        try {
            assertArrayEquals(new long[] {3, 0}, CpSatRuntime.solve(new long[][] {{1}},
                    new long[] {1}, new long[] {2}, 1, new long[0][], new long[0],
                    new long[0], new int[0], new int[0], new int[0], 3.0));
        } finally { Thread.interrupted(); }
    }

    @Test
    void interruptedFeedbackBridgeDoesNotStartSolving() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        Thread.currentThread().interrupt();
        try {
            assertArrayEquals(new long[] {3, 0}, CpSatRuntime.chooseFeedbackOption(
                    new long[][] {{1}}, new long[] {1}, new long[] {0}, 3.0));
        } finally { Thread.interrupted(); }
    }

    @Test
    void nativeAllowanceExcludesTimeAlreadySpentPreparingInputs() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        long[] firstAllowance = {0};
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() {
                if (firstAllowance[0] == 0) firstAllowance[0] = deadline - System.nanoTime();
                return deadline;
            }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() {
                if (System.nanoTime() - deadline >= 0) throw new PlanningExitException("test deadline");
            }
        };
        try (var bridge = replaceNative(); var bound = PlanningCancellation.bind(context)) {
            var constraints = onFlatten(() -> {
                // Force preparation to consume part of the unchanged caller budget; the assertion
                // compares allowances, with no machine-speed deadline or native timing dependency.
                long until = System.nanoTime() + 40_000_000L;
                while (System.nanoTime() - until < 0) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            });
            assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED,
                    CpSatIntegerLinearSolver.solve(1, constraints, 2).status());
            assertEquals(1, nativeCalls);
            assertTrue(nativeSeconds <= (firstAllowance[0] - 30_000_000L) / 1_000_000_000.0,
                    "native work was given time already spent on preparation");
        }
    }

    private static List<BoundedIntegerLinearSolver.Constraint> onFlatten(Runnable callback) {
        // Real solver passes: coefficient validation, safe-domain check, then native input rows.
        return new AbstractList<>() {
            private int reads;
            @Override public int size() { return 1; }
            @Override public BoundedIntegerLinearSolver.Constraint get(int index) {
                if (index != 0) throw new IndexOutOfBoundsException(index);
                if (++reads == 3) callback.run();
                return constraint();
            }
        };
    }

    private static BoundedIntegerLinearSolver.Constraint constraint() {
        return new BoundedIntegerLinearSolver.Constraint(new long[] {1}, 1);
    }

    private static void expireOptionalWork() {
        expiredScope = PlanningCancellation.limitOptionalWork(0);
    }

    private static void closeExpiredScope() {
        if (expiredScope != null) expiredScope.close();
        expiredScope = null;
    }

    private static AutoCloseable replaceNative() throws Exception {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        Field field = CpSatRuntime.class.getDeclaredField("bridge");
        field.setAccessible(true);
        Object previous = field.get(null);
        var components = previous.getClass().getRecordComponents();
        var values = new Object[components.length];
        var types = new Class<?>[components.length];
        Method replacement = CpSatBudgetBoundaryTest.class.getMethod("nativeUnknown",
                long[][].class, long[].class, long[].class, int.class, long[][].class,
                long[].class, long[].class, int[].class, int[].class, int[].class, double.class);
        for (int i = 0; i < components.length; i++) {
            var getter = components[i].getAccessor();
            getter.setAccessible(true);
            values[i] = components[i].getName().equals("solve") ? replacement : getter.invoke(previous);
            types[i] = components[i].getType();
        }
        var constructor = previous.getClass().getDeclaredConstructor(types);
        constructor.setAccessible(true);
        field.set(null, constructor.newInstance(values));
        nativeCalls = 0;
        onNativeCall = () -> {};
        return () -> { field.set(null, previous); onNativeCall = null; };
    }

    private static final class Context implements PlanningAttemptContext {
        final PlanningExitException exit;
        boolean cancelled;
        Context(PlanningExitException exit) { this.exit = exit; }
        @Override public long deadlineNanos() { return Long.MAX_VALUE; }
        @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
        @Override public void checkpoint() { if (cancelled) throw exit; }
    }
}
