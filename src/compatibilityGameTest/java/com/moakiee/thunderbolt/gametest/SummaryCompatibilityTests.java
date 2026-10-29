package com.moakiee.thunderbolt.gametest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReport;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports;
import com.moakiee.thunderbolt.ae2.crafting.ThunderboltCraftingPlanSummary;
import com.moakiee.thunderbolt.core.crafting.plan.LoopCraftingPlan;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Adapted from upstream a305f6f's isolated summary composition checks. */
@GameTestHolder("thunderbolt")
@PrefixGameTestTemplate(false)
public final class SummaryCompatibilityTests {
    public record CustomPlan(CraftingPlan delegate, CraftingPlanSummary summary) implements ICraftingPlan {
        public GenericStack finalOutput() { return delegate.finalOutput(); }
        public long bytes() { return delegate.bytes(); }
        public boolean simulation() { return delegate.simulation(); }
        public boolean multiplePaths() { return false; }
        public KeyCounter usedItems() { return delegate.usedItems(); }
        public KeyCounter emittedItems() { return delegate.emittedItems(); }
        public KeyCounter missingItems() { return delegate.missingItems(); }
        public Map<IPatternDetails, Long> patternTimes() { return delegate.patternTimes(); }
    }

    private static CraftingPlan plan() {
        return new CraftingPlan(new GenericStack(AEItemKey.of(Items.STONE), 1), 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(SummaryCompatibilityTests.class.getClassLoader(),
                new Class<?>[] {type}, handler);
    }

    private static Object tree(CraftingPlanSummary summary) throws ReflectiveOperationException {
        return Class.forName("com.neuvillette.ae2ct.api.ICraftingPlanSummary")
                .getMethod("getJob").invoke(summary);
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void thirdPartyHeadFactoryKeepsExactSummary(GameTestHelper helper) throws Exception {
        var key = AEItemKey.of(Items.STONE);
        var wide = BigInteger.TEN.pow(150).add(BigInteger.ONE);
        var summary = new CraftingPlanSummary(1, false,
                List.of(new CraftingPlanSummaryEntry(key, 0, 0, Long.MAX_VALUE)));
        var report = new ExactPlanReport(BigInteger.ONE,
                Map.of(key, new ExactPlanReport.Amounts(BigInteger.ZERO, BigInteger.ZERO, wide)), false, false);
        ExactPlanReports.attach(summary, report);
        var result = CraftingPlanSummary.fromJob(null, null, new CustomPlan(plan(), summary));
        helper.assertTrue(result == summary, "third-party plan type or summary was replaced");
        helper.assertTrue(ExactPlanReports.get(result).equals(report), "exact metadata was lost");
        helper.assertTrue(tree(result) != null, "Crafting Tree payload is missing");
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void nativeLoopSummaryStillFeedsCraftingTree(GameTestHelper helper) throws Exception {
        var storage = proxy(IStorageService.class, (p, m, a) -> null);
        var grid = proxy(IGrid.class, (p, m, a) -> m.getName().equals("getStorageService") ? storage : null);
        var loop = new LoopCraftingPlan(plan(),
                List.of((com.moakiee.thunderbolt.core.crafting.loop.CraftingCpuRestrictedPattern) ignored -> true),
                Map.of(), Map.of(), List.of());
        var summary = CraftingPlanSummary.fromJob(grid, null, loop);
        helper.assertTrue(tree(summary) != null, "native summary has no Crafting Tree payload");
        helper.assertTrue(summary.getUsedBytes() == 1, "native summary did not complete");
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void customFactoryKeepsItsExistingTree(GameTestHelper helper) throws Exception {
        var summary = ThunderboltCraftingPlanSummary.fromPlan(plan());
        Object originalTree = tree(summary);
        helper.assertTrue(originalTree != null, "test summary needs an existing tree");
        var result = CraftingPlanSummary.fromJob(null, null, new CustomPlan(plan(), summary));
        helper.assertTrue(result == summary, "factory summary identity was lost");
        helper.assertTrue(tree(result) == originalTree, "third-party tree was overwritten");
        helper.succeed();
    }
}
