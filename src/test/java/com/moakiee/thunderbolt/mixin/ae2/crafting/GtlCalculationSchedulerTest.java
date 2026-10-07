package com.moakiee.thunderbolt.mixin.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import com.moakiee.thunderbolt.compat.gtl.GtlCompat;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exercises the production wait callback without a server thread driving simulateFor(). */
class GtlCalculationSchedulerTest {
    private final String previousMode = System.getProperty(GtlCompat.MODE_PROPERTY);

    @AfterEach
    void restoreEnvironment() throws Exception {
        if (previousMode == null) {
            System.clearProperty(GtlCompat.MODE_PROPERTY);
        } else {
            System.setProperty(GtlCompat.MODE_PROPERTY, previousMode);
        }
        setGtlPresence(null);
    }

    @Test
    void neverModeWithGtlReturnsWithoutSimulateForWakeup() throws Exception {
        setGtlPresence(true);
        System.setProperty(GtlCompat.MODE_PROPERTY, "never");
        var calculation = new Calculation();
        setShadow(calculation, "monitor", new Object());
        setShadow(calculation, "watch", Stopwatch.createUnstarted());
        setShadow(calculation, "running", true);
        var executor = Executors.newSingleThreadExecutor();
        var waiting = executor.submit(() -> {
            pause(calculation);
            return null;
        });
        try {
            // No caller will set running=true or notify monitor: GTL's simulateFor is a no-op.
            waiting.get(2, TimeUnit.SECONDS);
        } finally {
            waiting.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void gtlWaitStillRespondsToCancellationWithHandoverDisabled() throws Exception {
        setGtlPresence(true);
        System.setProperty(GtlCompat.MODE_PROPERTY, "never");
        Thread.currentThread().interrupt();
        try {
            var failure = assertThrows(InvocationTargetException.class, () -> pause(new Calculation()));
            assertInstanceOf(InterruptedException.class, failure.getCause());
        } finally {
            Thread.interrupted();
        }
    }

    private static void pause(Calculation calculation) throws Exception {
        var callback = CraftingCalculationMixin.class.getDeclaredMethod("thunderbolt$pauseUntilNextTick");
        callback.setAccessible(true);
        callback.invoke(calculation);
    }

    private static void setGtlPresence(Boolean present) throws Exception {
        var field = GtlCompat.class.getDeclaredField("gtlPresent");
        field.setAccessible(true);
        field.set(null, present);
    }

    private static void setShadow(Calculation calculation, String name, Object value) throws Exception {
        var field = CraftingCalculationMixin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(calculation, value);
    }

    private static final class Calculation extends CraftingCalculationMixin {
        @Override
        public Level getLevel() {
            return null;
        }
    }
}
