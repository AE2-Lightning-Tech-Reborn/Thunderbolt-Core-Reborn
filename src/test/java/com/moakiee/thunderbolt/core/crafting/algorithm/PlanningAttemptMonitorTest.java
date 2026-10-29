package com.moakiee.thunderbolt.core.crafting.algorithm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.api.crafting.PlanningExitException;

class PlanningAttemptMonitorTest {
    @Test
    void unmanagedInterruptStillReportsNativeCancellation() throws Exception {
        var outcome = new CompletableFuture<Throwable>();
        var worker = new Thread(() -> {
            try (var monitor = PlanningAttemptMonitor.startForTest(
                    new ResourceLocation("thunderbolt_test", "native_cancel"),
                    "native cancellation test", 0, 5_000, 1_000, 5_000)) {
                Thread.currentThread().interrupt();
                try {
                    monitor.checkpoint();
                    outcome.complete(null);
                } catch (Throwable failure) {
                    outcome.complete(failure);
                }
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            }
        });
        worker.start();

        var failure = outcome.get(2, TimeUnit.SECONDS);
        worker.join(2_000L);
        assertInstanceOf(CancellationException.class, failure);
        assertFalse(worker.isAlive());
    }

    @Test
    void cancellationPublishedAtInterruptObservationIsStillACooperativeExit() throws Exception {
        var monitorReady = new AtomicReference<PlanningAttemptMonitor>();
        var interruptObservation = new AtomicBoolean();
        var interruptedAtExit = new AtomicBoolean();
        var outcome = new CompletableFuture<Throwable>();
        var worker = new Thread(() -> {
            try (var monitor = PlanningAttemptMonitor.startForTest(
                    new ResourceLocation("thunderbolt_test", "cancel_at_interrupt_read"),
                    "cancellation publication race test", 0, 5_000, 1_000, 5_000)) {
                monitorReady.set(monitor);
                interruptObservation.set(true);
                try {
                    monitor.checkpoint();
                    outcome.complete(null);
                } catch (Throwable failure) {
                    interruptedAtExit.set(Thread.currentThread().isInterrupted());
                    outcome.complete(failure);
                }
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            }
        }) {
            @Override
            public boolean isInterrupted() {
                // Place cancellation exactly at the interrupt read, after any earlier state reads.
                if (interruptObservation.compareAndSet(true, false)) {
                    monitorReady.get().requestExitForExternalCancellation();
                }
                return super.isInterrupted();
            }
        };
        worker.start();

        var failure = outcome.get(2, TimeUnit.SECONDS);
        worker.join(2_000L);
        assertInstanceOf(PlanningExitException.class, failure);
        assertTrue(interruptedAtExit.get());
        assertFalse(worker.isAlive());
    }

    @Test
    void budgetExpiryInterruptAndIsolationAreDistinctStages() throws Exception {
        var engineId = new ResourceLocation("thunderbolt_test", "slow");
        var monitorReady = new CompletableFuture<PlanningAttemptMonitor>();
        var interruptObserved = new CountDownLatch(1);
        var outcome = new CompletableFuture<Outcome>();
        var worker = new Thread(() -> {
            var monitor = PlanningAttemptMonitor.startForTest(
                    engineId,
                    "monitor test",
                    0,
                    30,
                    250,
                    550);
            monitorReady.complete(monitor);
            boolean timeoutThrown = false;
            try {
                while (!monitor.isolated()) {
                    if (Thread.interrupted()) {
                        interruptObserved.countDown();
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                try {
                    monitor.checkpoint();
                } catch (PlanningExitException expected) {
                    timeoutThrown = true;
                }
                outcome.complete(new Outcome(
                        monitor.timedOut(), timeoutThrown, monitor.interruptSent(),
                        monitor.isolated()));
            } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
            } finally {
                monitor.close();
            }
        });
        worker.start();

        var monitor = monitorReady.get(1, TimeUnit.SECONDS);
        waitUntil(monitor::timedOut);
        assertFalse(worker.isInterrupted(), "budget expiry must not interrupt the candidate");
        assertTrue(interruptObserved.await(1, TimeUnit.SECONDS));
        assertTrue(monitor.interruptSent());
        assertFalse(monitor.isolated(),
                "sending interrupt must not quarantine at the same boundary");
        waitUntil(monitor::isolated);
        var beforeClose = outcome.get(2, TimeUnit.SECONDS);
        worker.join(2_000);

        assertTrue(beforeClose.timedOut());
        assertTrue(beforeClose.timeoutThrown());
        assertTrue(beforeClose.interruptSent());
        assertTrue(beforeClose.isolated());
        assertFalse(worker.isAlive());
        assertFalse(worker.isInterrupted());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L) {
            Thread.sleep(1L);
        }
        assertTrue(condition.getAsBoolean());
    }

    private record Outcome(
            boolean timedOut,
            boolean timeoutThrown,
            boolean interruptSent,
            boolean isolated) {
    }
}
