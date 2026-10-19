package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import net.minecraft.world.item.Items;

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
