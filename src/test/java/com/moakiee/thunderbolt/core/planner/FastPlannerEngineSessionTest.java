package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;

class FastPlannerEngineSessionTest {
    private static final AEKey SEED = new TestKey();
    private static final ReusableStockUsageKey<AEKey> STOCK =
            new ReusableStockUsageKey<>("host", "pool", SEED);

    @Test
    void selectedProbeUsesPlanIdentityAndPublishesOnlyAtFinish() {
        var first = plan(false);
        // Equal records must still retain independent metadata from their respective probes.
        var second = new CraftingPlan(
                first.finalOutput(), first.bytes(), first.simulation(), first.multiplePaths(),
                first.usedItems(), first.emittedItems(), first.missingItems(), first.patternTimes());
        assertEquals(first, second);
        var context = new Context();
        try (var session = new FastPlannerEngineSession((amount, simulate) -> {
            assertFalse(simulate);
            return FastCraftingPlanner.FastAttempt.handled(
                    amount == 64 ? first : second, Map.of(STOCK, amount));
        })) {
            assertSame(first, session.attempt(64, false, context).plan());
            assertSame(second, session.attempt(32, false, context).plan());
            assertTrue(PlanningMetadataStore.take(first).isEmpty());
            assertTrue(PlanningMetadataStore.take(second).isEmpty());

            assertSame(first, session.finish(first, context));
            assertEquals(Map.of(STOCK, 64L), PlanningMetadataStore.take(first));
            assertTrue(PlanningMetadataStore.take(second).isEmpty());
            assertEquals("finishing", context.lastReport.phase());
        }
        assertEquals(2, context.checkpoints);
    }

    @Test
    void simulationFallbackRetainsMetadataUntilTheSelectedPlanIsFinished() {
        var fallback = plan(true);
        var context = new Context();
        try (var session = new FastPlannerEngineSession((amount, simulate) -> {
            assertEquals(128, amount);
            assertTrue(simulate);
            return FastCraftingPlanner.FastAttempt.infeasible(fallback, Map.of(STOCK, 2L));
        })) {
            var attempt = session.attempt(128, true, context);
            assertEquals(PlanningAttempt.Status.HANDLED, attempt.status());
            assertNull(attempt.plan());
            assertSame(fallback, attempt.simulationFallback());
            session.finish(fallback, context);
        }
        // Closing the session must leave the selected plan's handoff available to the finalizer.
        assertEquals(Map.of(STOCK, 2L), PlanningMetadataStore.take(fallback));
    }

    @Test
    void checkpointExitDeclinesBeforeRunningTheProbe() {
        var invoked = new AtomicBoolean();
        var context = new Context();
        context.cancelled = true;
        try (var session = new FastPlannerEngineSession((amount, simulate) -> {
            invoked.set(true);
            return FastCraftingPlanner.FastAttempt.decline();
        })) {
            assertSame(PlanningAttempt.DECLINE, session.attempt(1, false, context));
        }
        assertFalse(invoked.get());
    }

    @Test
    void cooperativeProbeExitDeclines() {
        try (var session = new FastPlannerEngineSession((amount, simulate) -> {
            throw new PlanningExitException("cancelled during search");
        })) {
            assertSame(PlanningAttempt.DECLINE, session.attempt(1, false, new Context()));
        }
    }

    @Test
    void unexpectedProbeFailuresReachTheCandidateExecutor() {
        var failure = new IllegalStateException("broken backend");
        try (var session = new FastPlannerEngineSession((amount, simulate) -> {
            throw failure;
        })) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> session.attempt(1, false, new Context())));
        }
    }

    @Test
    void unsupportedAndHandledInfeasibleAttemptsStayDistinct() {
        try (var session = new FastPlannerEngineSession((amount, simulate) ->
                amount == 1 ? FastCraftingPlanner.FastAttempt.decline()
                        : FastCraftingPlanner.FastAttempt.infeasible(null, Map.of()))) {
            assertSame(PlanningAttempt.DECLINE, session.attempt(1, false, new Context()));
            var infeasible = session.attempt(2, false, new Context());
            assertEquals(PlanningAttempt.Status.HANDLED, infeasible.status());
            assertNull(infeasible.plan());
            assertNull(infeasible.simulationFallback());
        }
    }

    @Test
    void separateSessionsKeepIndependentMetadata() {
        var plan = plan(false);
        var context = new Context();
        try (var first = new FastPlannerEngineSession((amount, simulate) ->
                FastCraftingPlanner.FastAttempt.handled(plan, Map.of(STOCK, 3L)));
                var second = new FastPlannerEngineSession((amount, simulate) ->
                        FastCraftingPlanner.FastAttempt.handled(plan, Map.of(STOCK, 7L)))) {
            first.attempt(1, false, context);
            second.attempt(1, false, context);
            first.finish(plan, context);
            assertEquals(Map.of(STOCK, 3L), PlanningMetadataStore.take(plan));
            second.finish(plan, context);
            assertEquals(Map.of(STOCK, 7L), PlanningMetadataStore.take(plan));
        }
    }

    private static CraftingPlan plan(boolean simulation) {
        return new CraftingPlan(null, 1L, simulation, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
    }

    private static final class Context implements PlanningAttemptContext {
        private int checkpoints;
        private boolean cancelled;
        private PlanningDiagnosticSnapshot lastReport;

        @Override public long deadlineNanos() { return Long.MAX_VALUE; }
        @Override public void checkpoint() {
            checkpoints++;
            if (cancelled) throw new PlanningExitException("cancelled before search");
        }
        @Override public void report(PlanningDiagnosticSnapshot snapshot) { lastReport = snapshot; }
    }

    private static final class TestKey extends AEKey {
        @Override public AEKeyType getType() { return null; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag(net.minecraft.core.HolderLookup.Provider registries) {
            return new CompoundTag();
        }
        @Override public Object getPrimaryKey() { return this; }
        @Override public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("thunderbolt_test", "session_seed");
        }
        @Override public void writeToPacket(RegistryFriendlyByteBuf data) { }
        @Override protected Component computeDisplayName() { return Component.literal("session seed"); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean hasComponents() { return false; }
    }
}
