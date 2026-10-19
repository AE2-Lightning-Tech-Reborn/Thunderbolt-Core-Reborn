package com.moakiee.thunderbolt.gametest;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.crafting.*;
import appeng.api.stacks.*;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.*;
import com.moakiee.thunderbolt.ae2.crafting.*;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingAlgorithmCalculationStatus;
import com.moakiee.thunderbolt.core.crafting.plan.LoopCraftingPlan;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.*;

@GameTestHolder("thunderbolt") @PrefixGameTestTemplate(false)
public final class CompatTests {
    public static ICraftingService service;
    public static ICraftingSimulationRequester requester;
    public interface GtlEntry { long gtlcore$getCraftTimes(); }
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
    private static CraftingPlan plan(Map<IPatternDetails, Long> patterns) {
        return new CraftingPlan(new GenericStack(AEItemKey.of(Items.STONE), 1), 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), patterns);
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(CompatTests.class.getClassLoader(), new Class<?>[] {type}, handler);
    }
    private static void tree(GameTestHelper helper, CraftingPlanSummary summary) throws Exception {
        var api = Class.forName("com.neuvillette.ae2ct.api.ICraftingPlanSummary");
        helper.assertTrue(api.getMethod("getJob").invoke(summary) != null, "Crafting Tree payload is missing");
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
        var result = CraftingPlanSummary.fromJob(null, null, new CustomPlan(plan(Map.of()), summary));
        helper.assertTrue(result == summary, "third-party plan type or summary was replaced");
        helper.assertTrue(ExactPlanReports.get(result).equals(report), "exact metadata was lost");
        tree(helper, result);
        helper.succeed();
    }
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void nativeLoopSummaryStillFeedsCraftingTree(GameTestHelper helper) throws Exception {
        var storage = proxy(IStorageService.class, (p, m, a) -> null);
        var grid = proxy(IGrid.class, (p, m, a) -> m.getName().equals("getStorageService") ? storage : null);
        var loop = new LoopCraftingPlan(plan(Map.of()),
                List.of((com.moakiee.thunderbolt.core.crafting.loop.CraftingCpuRestrictedPattern) ignored -> true),
                Map.of(), Map.of(), List.of());
        var summary = CraftingPlanSummary.fromJob(grid, null, loop);
        tree(helper, summary);
        helper.assertTrue(summary.getUsedBytes() == 1, "native summary did not complete");
        helper.succeed();
    }
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void customSummaryIncludesGtlExecutionCounts(GameTestHelper helper) throws Exception {
        var key = AEItemKey.of(Items.STONE);
        var pattern = proxy(IPatternDetails.class, (p, m, a) -> switch (m.getName()) {
            case "getOutputs" -> new GenericStack[] {new GenericStack(key, 64)};
            case "getInputs" -> new IPatternDetails.IInput[0];
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> null;
        });
        var summary = ThunderboltCraftingPlanSummary.fromPlan(plan(Map.of(pattern, 7L)));
        helper.assertTrue(((GtlEntry) summary.getEntries().get(0)).gtlcore$getCraftTimes() == 7,
                "GTL execution count was absent or multiplied by the output amount");
        tree(helper, summary);
        helper.succeed();
    }
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void mergedLongEntryResetsAndTracksNewJob(GameTestHelper helper) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var menu = (CraftConfirmMenu) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(CraftConfirmMenu.class);
        var oldRequester = (ICraftingSimulationRequester) () -> null;
        var oldJob = new CompletableFuture<ICraftingPlan>();
        var algorithm = new ResourceLocation("thunderbolt", "compat_test");
        CraftingAlgorithmCalculationStatus.track(oldRequester, () -> oldJob);
        CraftingAlgorithmCalculationStatus.select(oldRequester, algorithm);
        var jobField = CraftConfirmMenu.class.getDeclaredField("job");
        jobField.setAccessible(true);
        jobField.set(menu, oldJob);
        requester = () -> null;
        var newJob = new CompletableFuture<ICraftingPlan>();
        service = proxy(ICraftingService.class, (p, m, a) -> {
            if (!m.getName().equals("beginCraftingCalculation")) throw new AssertionError(m);
            helper.assertTrue((long) a[3] == (long) Integer.MAX_VALUE + 123, "long quantity truncated");
            CraftingAlgorithmCalculationStatus.select((ICraftingSimulationRequester) a[1], algorithm);
            return newJob;
        });
        try {
            CraftConfirmMenu.class.getMethod("gtlcore$planLongAmountJob", AEKey.class, long.class, CalculationStrategy.class)
                    .invoke(menu, AEItemKey.of(Items.STONE), (long) Integer.MAX_VALUE + 123,
                            CalculationStrategy.REPORT_MISSING_ITEMS);
            helper.assertTrue(CraftingAlgorithmCalculationStatus.selected(oldJob) == null, "old status was retained");
            helper.assertTrue(algorithm.equals(CraftingAlgorithmCalculationStatus.selected(newJob)), "new status was not tracked");
            helper.assertTrue(jobField.get(menu) == newJob, "original GTL entry was bypassed");
        } finally {
            CraftingAlgorithmCalculationStatus.forget(oldJob);
            CraftingAlgorithmCalculationStatus.forget(newJob);
            service = null;
            requester = null;
        }
        helper.succeed();
    }
}
