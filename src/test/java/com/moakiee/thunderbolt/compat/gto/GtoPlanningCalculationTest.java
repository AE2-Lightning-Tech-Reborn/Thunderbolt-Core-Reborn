package com.moakiee.thunderbolt.compat.gto;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.stacks.*;
import appeng.crafting.CraftingPlan;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;
import com.moakiee.thunderbolt.ae2.crafting.CapturedPlanningChoice;
import com.moakiee.thunderbolt.api.crafting.*;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

class GtoPlanningCalculationTest {
    @BeforeAll static void bootstrap() {
        com.moakiee.thunderbolt.test.MinecraftTestBootstrap.ensureInitialized();
    }
    private static CraftingPlan plan(long amount, boolean simulate) {
        return new CraftingPlan(new GenericStack(AEItemKey.of(Items.IRON_INGOT), amount),
                1, simulate, false, new KeyCounter(), new KeyCounter(), new KeyCounter(),
                new Object2LongOpenHashMap<>());
    }
    private static GtoPlanningCalculation calculation(PlanningEngineSession session,
            CalculationStrategy strategy, Callable<ICraftingPlan> fallback) throws Exception {
        var request = new PlanningRequest(null, null, null, AEItemKey.of(Items.IRON_INGOT), 20, strategy, null);
        var candidates = new ArrayList<CapturedPlanningChoice>();
        if (session != null) {
            var engine = new CraftingPlanningEngine() {
                public ResourceLocation id() { return new ResourceLocation("thunderbolt", "gto_qa"); }
                public boolean check(IGrid grid, PlanningRequest request) { return true; }
                public PlanningEngineSession createSession(PlanningRequest request, Object captured,
                        PlanningAttemptContext context) { return session; }
            };
            candidates.add(new CapturedPlanningChoice(PlanningChoice.engine(engine.id()), engine, null));
        }
        candidates.add(CapturedPlanningChoice.vanilla());
        var constructor = GtoPlanningCalculation.class.getDeclaredConstructor(Callable.class,
                PlanningRequest.class, ICraftingSimulationRequester.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(fallback, request, null, List.copyOf(candidates));
    }
    @Test void nativeFallbackRetainsExactPlanIdentity() throws Exception {
        var nativePlan = plan(20, false);
        assertSame(nativePlan, calculation(null, CalculationStrategy.REPORT_MISSING_ITEMS, () -> nativePlan).call());
    }
    @Test void successfulCandidateClosesAndSkipsNativeCalculation() throws Exception {
        var result = plan(20, false);
        var closed = new AtomicInteger();
        var session = new PlanningEngineSession() {
            public PlanningAttempt attempt(long amount, boolean simulate, PlanningAttemptContext context) {
                return PlanningAttempt.handled(result);
            }
            public void close() { closed.incrementAndGet(); }
        };
        assertSame(result, calculation(session, CalculationStrategy.REPORT_MISSING_ITEMS,
                () -> { throw new AssertionError("unexpected native fallback"); }).call());
        assertEquals(1, closed.get());
        assertInstanceOf(Object2LongOpenHashMap.class, result.patternTimes());
    }
    @Test void declineAndRuntimeFailureCloseThenUseNativePlan() throws Exception {
        for (boolean fail : List.of(false, true)) {
            var closed = new AtomicInteger();
            var nativePlan = plan(20, false);
            var session = new PlanningEngineSession() {
                public PlanningAttempt attempt(long amount, boolean simulate, PlanningAttemptContext context) {
                    if (fail) throw new IllegalStateException("candidate unavailable");
                    return PlanningAttempt.DECLINE;
                }
                public void close() { closed.incrementAndGet(); }
            };
            assertSame(nativePlan, calculation(session, CalculationStrategy.REPORT_MISSING_ITEMS, () -> nativePlan).call());
            assertEquals(1, closed.get());
        }
    }
    @Test void craftLessFindsLargestFeasibleAmountInSameSession() throws Exception {
        var probes = new ArrayList<Long>();
        var session = new PlanningEngineSession() {
            public PlanningAttempt attempt(long amount, boolean simulate, PlanningAttemptContext context) {
                assertFalse(simulate);
                probes.add(amount);
                return PlanningAttempt.handled(amount <= 13 ? plan(amount, false) : null);
            }
        };
        var result = calculation(session, CalculationStrategy.CRAFT_LESS,
                () -> { throw new AssertionError("unexpected native fallback"); }).call();
        assertEquals(13, result.finalOutput().amount());
        assertEquals(List.of(20L,16L,8L,12L,14L,13L), probes);
    }
    @Test void interruptedCalculationNeverStartsFallback() throws Exception {
        var task = calculation(null, CalculationStrategy.REPORT_MISSING_ITEMS,
                () -> { throw new AssertionError("cancelled fallback ran"); });
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, task::call); }
        finally { Thread.interrupted(); }
    }
    @Test void nativeFailureRemainsVisibleToCaller() throws Exception {
        var failure = new IllegalStateException("native failure");
        var task = calculation(null, CalculationStrategy.REPORT_MISSING_ITEMS, () -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, task::call));
    }
}
