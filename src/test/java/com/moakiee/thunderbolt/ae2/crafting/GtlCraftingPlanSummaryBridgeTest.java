package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import com.moakiee.thunderbolt.core.crafting.planner.FastCraftingPlanner;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;

public class GtlCraftingPlanSummaryBridgeTest {
    static { MinecraftTestBootstrap.ensureInitialized(); }

    @Test
    void countsExecutionsForEveryOutputAndSaturatesSharedOutputs() {
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var input = new GtlEntry(AEItemKey.of(Items.STONE));
        var primary = new GtlEntry(iron);
        var byproduct = new GtlEntry(gold);
        var plan = plan(Map.of(
                pattern(new GenericStack(iron, 64), new GenericStack(gold, 2)), Long.MAX_VALUE - 2,
                pattern(new GenericStack(iron, 1)), 9L,
                pattern(new GenericStack(gold, 1)), -10L));
        var summary = new CraftingPlanSummary(1, false, List.of(input, primary, byproduct));

        GtlCraftingPlanSummaryBridge.attach(summary, plan);

        assertEquals(0, input.times);
        assertEquals(Long.MAX_VALUE, primary.times);
        assertEquals(Long.MAX_VALUE - 2, byproduct.times);
    }

    @Test
    void ordinaryForgeEntriesDoNotRequireGtlClassesOrReadPatternOutputs() {
        var entry = new CraftingPlanSummaryEntry(AEItemKey.of(Items.STONE), 0, 3, 0);
        var summary = new CraftingPlanSummary(1, false, List.of(entry));
        IPatternDetails pattern = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IPatternDetails.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    throw new AssertionError("absent integration must not inspect patterns");
                });

        assertDoesNotThrow(() -> GtlCraftingPlanSummaryBridge.attach(summary, plan(Map.of(pattern, 1L))));
        assertSame(entry, summary.getEntries().get(0));
    }

    @Test
    void exactDisplayCountsStayImmutableAndSaturateOnlyAtTheGtlBoundary() {
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var primary = new GtlEntry(iron);
        var secondary = new GtlEntry(gold);
        var plan = new CraftingPlan(null, 1, true, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        var wide = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN);
        var counts = new HashMap<AEKey, BigInteger>();
        counts.put(iron, wide);
        counts.put(gold, BigInteger.valueOf(123));
        ExactPlanReports.attachCraftingTimes(plan, counts);
        counts.clear();

        GtlCraftingPlanSummaryBridge.attach(new CraftingPlanSummary(1, true, List.of(primary, secondary)), plan);

        assertEquals(Long.MAX_VALUE, primary.times);
        assertEquals(123, secondary.times);
        assertEquals(wide, ExactPlanReports.craftingTimes(plan).get(iron));
        assertTrue(plan.patternTimes().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"2147483652, 1", "2147483652, 64", "9223372036854775807, 1", "9223372036854775807, 64"})
    void fundedLongPreviewCountsExecutionsWithoutMakingThePlanExecutable(long requested, long outputAmount) {
        var stone = AEItemKey.of(Items.STONE);
        var button = AEItemKey.of(Items.STONE_BUTTON);
        IPatternDetails.IInput input = new IPatternDetails.IInput() {
            @Override public GenericStack[] getPossibleInputs() { return new GenericStack[] {new GenericStack(stone, 1)}; }
            @Override public long getMultiplier() { return 1; }
            @Override public boolean isValid(AEKey key, Level level) { return stone.equals(key); }
            @Override public AEKey getRemainingKey(AEKey key) { return null; }
        };
        IPatternDetails pattern = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputs" -> new IPatternDetails.IInput[] {input};
                    case "getOutputs" -> new GenericStack[] {new GenericStack(button, outputAmount)};
                    case "getPrimaryOutput" -> new GenericStack(button, outputAmount);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        ICraftingService service = (ICraftingService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ICraftingService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCraftingFor" -> button.equals(args[0]) ? List.of(pattern) : List.of();
                    case "getCraftables" -> Set.of(button);
                    case "canEmitFor" -> false;
                    case "getFuzzyCraftable" -> null;
                    default -> throw new AssertionError(method);
                });
        var inventory = new ListCraftingInventory(ignored -> {});
        inventory.insert(stone, requested, Actionable.MODULATE);
        var attempt = FastCraftingPlanner.tryAttempt(service,
                new ChildCraftingSimulationState(inventory), null, button, requested, false);
        assertTrue(attempt.handled());
        var plan = attempt.plan();
        assertNotNull(plan, attempt.toString());
        long expectedTimes = requested / outputAmount + (requested % outputAmount == 0 ? 0 : 1);
        assertEquals(expectedTimes, plan.usedItems().get(stone));
        assertTrue(plan.missingItems().isEmpty());
        var material = new GtlEntry(stone);
        var product = new GtlEntry(button);
        var summary = new CraftingPlanSummary(plan.bytes(), plan.simulation(), List.of(material, product));

        GtlCraftingPlanSummaryBridge.attach(summary, plan);

        assertEquals(expectedTimes, product.times, "GTL counts executions, independently of the output stack size");
        assertEquals(0, material.times);
        assertEquals(requested, plan.finalOutput().amount());
        assertEquals(requested, inventory.extract(stone, Long.MAX_VALUE, Actionable.SIMULATE));
        if (requested == Long.MAX_VALUE) {
            assertTrue(plan.simulation());
            assertTrue(ExactPlanReports.isPreview(plan));
            assertTrue(plan.patternTimes().isEmpty(), "display counts must never become executable pattern counts");
            var report = ExactPlanReports.get(ThunderboltCraftingPlanSummary.fromPlan(plan));
            assertFalse(report.incomplete());
            assertEquals(BigInteger.valueOf(expectedTimes).multiply(BigInteger.valueOf(outputAmount)),
                    report.entries().get(button).crafting());
            assertTrue(report.bytes().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        } else {
            assertFalse(plan.simulation());
            assertFalse(ExactPlanReports.isPreview(plan));
            assertEquals(expectedTimes, plan.patternTimes().get(pattern));
        }
    }

    private static CraftingPlan plan(Map<IPatternDetails, Long> patterns) {
        return new CraftingPlan(null, 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), patterns);
    }

    private static IPatternDetails pattern(GenericStack... outputs) {
        return (IPatternDetails) Proxy.newProxyInstance(GtlCraftingPlanSummaryBridgeTest.class.getClassLoader(),
                new Class<?>[] {IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getOutputs" -> outputs;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method);
                });
    }

    public static final class GtlEntry extends CraftingPlanSummaryEntry {
        long times = -1;
        GtlEntry(AEKey key) { super(key, 0, 0, 0); }
        public void gtlcore$setCraftTimes(long value) { times = value; }
    }
}
