package com.moakiee.thunderbolt.compat.gto;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.LoggerFactory;

/** Keeps initial datapack loading behind GTO's asynchronous recipe/loot registration. */
public final class GtoDataInitialization {
    private static final Gate GATE = new Gate();

    private GtoDataInitialization() {
    }

    /** Called only at Data.init's client async submission; the original GTO executor is retained. */
    public static Runnable track(Runnable task) {
        return GATE.track(task);
    }

    /** Forge's queued load-complete work runs before the first client datapack reload. */
    public static void awaitReady() {
        if (!GATE.started()) return; // Dedicated servers initialize synchronously.
        var logger = LoggerFactory.getLogger("thunderbolt");
        logger.info("Waiting for GTO recipe, loot and EMI data initialization");
        GATE.await(Duration.ofMinutes(5));
        try {
            // GTO clientInit catches registration failures itself. Keep that original cause visible
            // and stop startup instead of loading a world with incomplete recipes or loot caches.
            var failure = (Throwable) Class.forName("com.gtocore.data.Data")
                    .getMethod("getThrowable").invoke(null);
            if (failure != null) throw new IllegalStateException("GTO data initialization failed", failure);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot verify GTO data initialization", error);
        }
        logger.info("GTO data initialization complete; initial datapack loading can proceed");
    }

    static final class Gate {
        private CompletableFuture<Void> completion;

        synchronized boolean started() {
            return completion != null;
        }

        synchronized Runnable track(Runnable task) {
            Objects.requireNonNull(task, "task");
            if (completion != null) throw new IllegalStateException("GTO data initialization scheduled twice");
            var result = new CompletableFuture<Void>();
            completion = result;
            return () -> {
                try {
                    task.run();
                    result.complete(null);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                    throw error;
                }
            };
        }

        void await(Duration timeout) {
            CompletableFuture<Void> result;
            synchronized (this) { result = completion; }
            if (result == null) return;
            try {
                result.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for GTO data initialization", error);
            } catch (ExecutionException error) {
                throw new IllegalStateException("GTO data initialization failed", error.getCause());
            } catch (TimeoutException error) {
                throw new IllegalStateException("Timed out waiting for GTO data initialization", error);
            }
        }
    }
}
