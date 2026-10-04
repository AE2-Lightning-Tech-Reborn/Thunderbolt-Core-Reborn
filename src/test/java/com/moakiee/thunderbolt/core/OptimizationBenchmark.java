package com.moakiee.thunderbolt.core;

import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlannerV2;
import com.moakiee.thunderbolt.core.crafting.batch.ParallelBatchCpuHelper;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.LoadingModList;

/** Opt-in benchmark: independent JVMs, warmup, per-thread allocation, and feasibility checks. */
public final class OptimizationBenchmark {
    private static final int MEASUREMENT_SAMPLES = 20;
    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        LoadingModList.of(List.of(), List.of(), null);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var rows = new java.util.ArrayList<String>();
        var chain = CraftGraph.<String>builder().stock("raw", 1_000_000);
        String previous = "raw";
        for (int i = 0; i < 500; i++) {
            String next = "chain" + i;
            chain.pattern(next, 1, List.of(CraftInput.of(previous, 1)));
            previous = next;
        }
        var chainGraph = chain.build();
        String target = previous;
        rows.add(measure("chain-500", 10, () -> checkedPlan(chainGraph, target, 1000)));
        for (long amount : new long[]{1, 1_000_000_000_000L}) {
            var alternatives = CraftGraph.<String>builder().stock("raw", 100 * amount)
                    .pattern("T", 10 * amount, List.of(CraftInput.of("raw", 10 * amount)))
                    .pattern("T", amount, List.of(CraftInput.of("raw", 2 * amount))).build();
            rows.add(measure("alternatives-" + amount, 50,
                    () -> checkedPlan(alternatives, "T", amount)));
            rows.add(measure("quantity-session-" + amount, 10, () -> {
                var session = new CraftPlannerV2.PlanningSession<String>();
                for (int q = 0; q < 8; q++) {
                    var result = CraftPlannerV2.planDetailed(alternatives, "T", amount, session);
                    if (!result.plan().feasible()) throw new AssertionError("lost feasible plan");
                    sink = result;
                }
            }));
        }
        var mixed = CraftGraph.<String>builder().stock("iron", 100).stock("diamond", 100)
                .pattern("T", 2, List.of(CraftInput.of("iron", 2), CraftInput.of("diamond", 2)))
                .pattern("T", 4, List.of(CraftInput.of("iron", 7)))
                .pattern("T", 4, List.of(CraftInput.of("diamond", 7))).build();
        rows.add(measure("three-route-mixed", 50, () -> {
            var plan = CraftPlannerV2.plan(mixed, "T", 10);
            if (!plan.feasible() || plan.firings().values().stream().mapToLong(n -> n).sum() != 3
                    || !plan.usedStock().equals(Map.of("iron", 9L, "diamond", 9L)))
                throw new AssertionError("mixed-route witness changed");
            sink = plan;
        }));
        var key = AEItemKey.of(Items.STONE);
        var inventory = new ListCraftingInventory(ignored -> {});
        inventory.insert(key, 1000, Actionable.MODULATE);
        var details = new IPatternDetails() {
            private final IInput[] inputs = {new IInput() {
                public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(key, 2)}; }
                public long getMultiplier() { return 1; }
                public boolean isValid(AEKey candidate, Level level) { return key.equals(candidate); }
                public AEKey getRemainingKey(AEKey candidate) { return null; }
            }};
            public AEItemKey getDefinition() { return null; }
            public IInput[] getInputs() { return inputs; }
            public GenericStack[] getOutputs() { return new GenericStack[]{new GenericStack(key, 1)}; }
        };
        rows.add(measure("ordinary-bulk-extract", 3000, () -> {
            var batch = ParallelBatchCpuHelper.bulkExtract(details, inventory, 16, true, Map.of(), null);
            if (batch == null || batch.actualCopies != 16) throw new AssertionError("batch extraction changed");
            ParallelBatchCpuHelper.reinject(batch, batch.actualCopies, inventory);
            if (inventory.extract(key, Long.MAX_VALUE, Actionable.SIMULATE) != 1000)
                throw new AssertionError("refund changed inventory");
            sink = batch;
        }));
        String json = "{\"java\":\"" + System.getProperty("java.version") + "\",\"samples\":["
                + String.join(",", rows) + "]}";
        Path output = Path.of(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json);
        System.out.println(json);
    }

    private static void checkedPlan(CraftGraph<String> graph, String target, long amount) {
        var plan = CraftPlannerV2.plan(graph, target, amount);
        if (!plan.feasible()) throw new AssertionError("lost feasible plan");
        if (target.equals("T") && plan.usedStock().get("raw") != 2 * amount)
            throw new AssertionError("lost optimum in reference case");
        sink = plan;
    }

    private static String measure(String name, int iterations, Runnable action) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation metrics unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        for (int w = 0; w < 20; w++) for (int i = 0; i < iterations; i++) action.run();
        double[] nanos = new double[MEASUREMENT_SAMPLES], bytes = new double[MEASUREMENT_SAMPLES];
        for (int sample = 0; sample < nanos.length; sample++) {
            long beforeAllocation = bean.getThreadAllocatedBytes(thread), started = System.nanoTime();
            for (int i = 0; i < iterations; i++) action.run();
            nanos[sample] = (System.nanoTime() - started) / (double) iterations;
            bytes[sample] = (bean.getThreadAllocatedBytes(thread) - beforeAllocation) / (double) iterations;
        }
        Arrays.sort(nanos); Arrays.sort(bytes);
        int p50 = percentileIndex(nanos.length, 0.50D);
        int p95 = percentileIndex(nanos.length, 0.95D);
        return String.format(Locale.ROOT,
                "{\"scenario\":\"%s\",\"iterations\":%d,\"p50_ns\":%.1f,\"p95_ns\":%.1f,\"bytes_per_op\":%.1f}",
                name, iterations, nanos[p50], nanos[p95], bytes[p50]);
    }

    private static int percentileIndex(int sampleCount, double percentile) {
        return Math.min(sampleCount - 1,
                Math.max(0, (int) Math.ceil(sampleCount * percentile) - 1));
    }
}
