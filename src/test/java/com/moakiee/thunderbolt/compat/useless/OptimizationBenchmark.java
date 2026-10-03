package com.moakiee.thunderbolt.compat.useless;

import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlannerV2;
import com.moakiee.thunderbolt.core.crafting.batch.ParallelBatchCpuHelper;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
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
        verifyUselessOwnershipScenarios();
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
        var provider = new Provider();
        var adapter = new UselessBatchAdapter(new UselessBatchApi(
                Provider.class, Target.class, Capacity.class, Batch.class, Binding.class)).resolve(provider);
        var key = AEItemKey.of(Items.STONE);
        KeyCounter[] input = {new KeyCounter()};
        input[0].add(key, 2);
        rows.add(measure("useless-dispatch", 10_000, () -> {
            long remaining = adapter.pushBatch(null, input, 16);
            if (remaining != 0 || input[0].get(key) != 2) throw new AssertionError("ownership changed");
            sink = remaining;
        }));
        var throttledProvider = new Provider();
        throttledProvider.target.limit = BigInteger.valueOf(3);
        var throttledAdapter = new UselessBatchAdapter(new UselessBatchApi(
                Provider.class, Target.class, Capacity.class, Batch.class, Binding.class)).resolve(throttledProvider);
        var partialInput = singleInput();
        rows.add(measure("useless-partial-dispatch", 10_000, () -> {
            long remaining = throttledAdapter.pushBatch(null, partialInput, 10);
            if (remaining != 7 || partialInput[0].get(key) != 2) {
                throw new AssertionError("partial acceptance changed ownership");
            }
            sink = remaining;
        }));
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

    /**
     * Exercises ownership-sensitive paths that a timing loop must not silently skip. These checks
     * intentionally run before measurements so a broken provider contract cannot produce a useful
     * looking benchmark report.
     */
    private static void verifyUselessOwnershipScenarios() throws Exception {
        var partialProvider = new Provider();
        partialProvider.target.limit = BigInteger.valueOf(3);
        var partial = resolver().resolve(partialProvider);
        var source = singleInput();
        check(partial.pushBatch(null, source, 10) == 7, "partial acceptance count changed");
        check(source[0].get(AEItemKey.of(Items.STONE)) == 2,
                "partial acceptance mutated the borrowed template");
        check(partialProvider.ordinaryCalls == 0, "partial acceptance fell back to ordinary dispatch");

        var rejectedProvider = new Provider();
        rejectedProvider.target.success = false;
        var rejected = resolver().resolve(rejectedProvider);
        check(rejected.pushBatch(null, singleInput(), 10) == 9,
                "rejected commit did not leave one ordinary attempt");
        check(rejectedProvider.ordinaryCalls == 1, "rejected commit did not use ordinary fallback");

        var failedProvider = new Provider();
        failedProvider.target.failure = new IllegalStateException("ownership uncertain");
        try {
            resolver().resolve(failedProvider).pushBatch(null, singleInput(), 10);
            throw new AssertionError("ambiguous commit was swallowed");
        } catch (IllegalStateException expected) {
            check(failedProvider.ordinaryCalls == 0,
                    "ambiguous commit was retried through ordinary dispatch");
        }
    }

    private static UselessBatchAdapter resolver() throws Exception {
        return new UselessBatchAdapter(new UselessBatchApi(
                Provider.class, Target.class, Capacity.class, Batch.class, Binding.class));
    }

    private static KeyCounter[] singleInput() {
        var input = new KeyCounter();
        input.add(AEItemKey.of(Items.STONE), 2);
        return new KeyCounter[]{input};
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Binding {}
    public record Capacity(BigInteger accepted) {}
    public interface Batch { BigInteger count(); boolean commit(KeyCounter[] inputs); }
    public static final class Target {
        private BigInteger limit;
        private boolean success = true;
        private RuntimeException failure;

        public Capacity capacity(IPatternDetails pattern, KeyCounter[] inputs, BigInteger requested) {
            var accepted = limit == null ? requested : limit.min(requested);
            return new Capacity(accepted.max(BigInteger.ZERO));
        }
        public Batch admit(IPatternDetails pattern, KeyCounter[] inputs, BigInteger requested, Binding binding) {
            var accepted = capacity(pattern, inputs, requested).accepted();
            return new Batch() {
                public BigInteger count() { return accepted; }
                public boolean commit(KeyCounter[] prototype) {
                    if (failure != null) throw failure;
                    if (success) prototype[0].clear();
                    return success;
                }
            };
        }
    }
    public static final class Provider implements ICraftingProvider {
        private final Target target = new Target();
        private int ordinaryCalls;
        public Target bigIntegerTarget() { return target; }
        public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        public boolean isBusy() { return false; }
        public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) {
            ordinaryCalls++;
            return true;
        }
    }
}
