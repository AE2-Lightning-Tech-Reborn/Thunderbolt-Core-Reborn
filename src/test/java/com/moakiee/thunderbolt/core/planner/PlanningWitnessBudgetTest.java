package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;

class PlanningWitnessBudgetTest {
    @Test
    void unboundWitnessStillStopsWhenItsCpuAllowanceIsSpent() {
        var threads = ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            try (var ignored = PlanningCancellation.limitWitnessWork(20_000_000L)) {
                assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> {
                    while (true) PlanningCancellation.check();
                });
            }
            assertDoesNotThrow(PlanningCancellation::check);
        });
    }

    @Test
    void unboundWitnessDoesNotChargeASchedulingPause() throws Exception {
        var threads = ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled());
        PlanningCancellation.witnessClockNanos();
        try (var ignored = PlanningCancellation.limitWitnessWork(100_000_000L)) {
            Thread.sleep(200);
            assertDoesNotThrow(PlanningCancellation::check);
            assertTrue(PlanningCancellation.remainingNanos(Long.MAX_VALUE) > 0L);
        }
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void witnessStillHonorsTheEnclosingWallDeadline() throws Exception {
        try (var parent = PlanningCancellation.limitOptionalWork(20_000_000L);
             var witness = PlanningCancellation.limitWitnessWork(1_000_000_000L)) {
            Thread.sleep(40);
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, PlanningCancellation::check);
        }
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void aBoundCallerKeepsItsWallDeadlineAndCancellation() throws Exception {
        var cancelled = new CancellationException("caller cancelled");
        boolean[] cancel = {false};
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() { if (cancel[0]) throw cancelled; }
        };
        try (var bound = PlanningCancellation.bind(context);
             var witness = PlanningCancellation.limitWitnessWork(20_000_000L)) {
            Thread.sleep(40);
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, PlanningCancellation::check);
            cancel[0] = true;
            assertSame(cancelled, assertThrows(CancellationException.class, PlanningCancellation::check));
        }
        assertDoesNotThrow(PlanningCancellation::check);
    }
}
