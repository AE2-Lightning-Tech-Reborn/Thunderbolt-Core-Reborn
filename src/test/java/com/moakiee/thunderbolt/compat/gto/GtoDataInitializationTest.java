package com.moakiee.thunderbolt.compat.gto;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class GtoDataInitializationTest {
    private static final Duration LIMIT = Duration.ofSeconds(5);

    @Test
    void noAsyncTaskDoesNotWaitOrLoadGtoClasses() {
        var gate = new GtoDataInitialization.Gate();
        assertFalse(gate.started());
        gate.await(Duration.ZERO);
        GtoDataInitialization.awaitReady();
    }

    @Test
    void datapackReaderWaitsForAllRegistrationsAndSeesTheirWrites() throws Exception {
        var gate = new GtoDataInitialization.Gate();
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var readerStarted = new CountDownLatch(1);
        var consumed = new AtomicBoolean();
        var loot = new HashMap<String, String>();
        var task = gate.track(() -> {
            loot.put("first", "recipe");
            writing.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            loot.put("last", "reward-bag");
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(task);
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            var reader = executor.submit(() -> {
                readerStarted.countDown();
                gate.await(LIMIT);
                consumed.set(true);
                assertEquals(2, loot.size());
                assertEquals("reward-bag", loot.get("last"));
            });
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
                assertFalse(consumed.get());
            } finally { release.countDown(); }
            writer.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS);
            gate.await(Duration.ZERO);
        } finally { release.countDown(); }
    }

    @Test
    void failedRegistrationWakesReadersAndPreservesOriginalError() {
        var gate = new GtoDataInitialization.Gate();
        var cause = new AssertionError("recipe registration failed");
        var task = gate.track(() -> { throw cause; });
        assertSame(cause, assertThrows(AssertionError.class, task::run));
        assertSame(cause, assertThrows(IllegalStateException.class, () -> gate.await(LIMIT)).getCause());
    }

    @Test
    void repeatedInitializationDoesNotReplacePendingCompletion() {
        var gate = new GtoDataInitialization.Gate();
        var task = gate.track(() -> {});
        assertThrows(IllegalStateException.class, () -> gate.track(() -> fail("must not run twice")));
        task.run();
        gate.await(Duration.ZERO);
    }

    @Test
    void missingCompletionTimesOutWithoutClaimingReady() {
        var gate = new GtoDataInitialization.Gate();
        var task = gate.track(() -> {});
        assertInstanceOf(TimeoutException.class,
                assertThrows(IllegalStateException.class, () -> gate.await(Duration.ZERO)).getCause());
        task.run();
        gate.await(Duration.ZERO);
    }

    @Test
    void interruptedWaitKeepsInterruptFlag() throws Exception {
        var gate = new GtoDataInitialization.Gate();
        gate.track(() -> {});
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try {
                Thread.currentThread().interrupt();
                assertInstanceOf(InterruptedException.class,
                        assertThrows(IllegalStateException.class, () -> gate.await(LIMIT)).getCause());
                assertTrue(Thread.currentThread().isInterrupted());
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
    }
}
