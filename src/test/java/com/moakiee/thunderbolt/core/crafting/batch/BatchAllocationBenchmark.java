package com.moakiee.thunderbolt.core.crafting.batch;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.LoadingModList;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;

/** Opt-in synthetic benchmark; each measured operation includes extraction, a provider copy and refund. */
public final class BatchAllocationBenchmark {
    private static final int SAMPLES = 20;
    private static final int ITERATIONS = 2_000;
    private static final long STOCK = 1_000_000;
    private static volatile Object sink;

    private BatchAllocationBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        LoadingModList.of(Collections.emptyList(), Collections.emptyList(), null);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var keys = new AEKey[]{AEItemKey.of(Items.STONE), AEItemKey.of(Items.DIRT),
                AEItemKey.of(Items.SAND), AEItemKey.of(Items.GRAVEL), AEItemKey.of(Items.COBBLESTONE),
                AEItemKey.of(Items.IRON_INGOT), AEItemKey.of(Items.GOLD_INGOT),
                AEItemKey.of(Items.COPPER_INGOT), AEItemKey.of(Items.DIAMOND)};
        var rows = new ArrayList<String>();
        rows.add(measure("native-one-slot", extraction(new AEKey[]{keys[0]}, false, false, false)));
        var repeated = new AEKey[9];
        Arrays.fill(repeated, keys[0]);
        rows.add(measure("native-nine-repeated", extraction(repeated, false, false, false)));
        rows.add(measure("native-nine-distinct", extraction(keys, false, false, false)));
        rows.add(measure("native-nine-reserved", extraction(keys, false, true, false)));
        rows.add(measure("native-shared-seed", extraction(keys, false, false, true)));
        rows.add(measure("legacy-nine-repeated", extraction(repeated, true, false, false)));
        rows.add(measure("legacy-nine-distinct", extraction(keys, true, false, false)));
        String json = String.format(Locale.ROOT,
                "{\"java\":\"%s\",\"vm\":\"%s\",\"processors\":%d,\"max_heap\":%d,"
                        + "\"helper_sha256\":\"%s\",\"executor_sha256\":\"%s\","
                        + "\"helper_source\":\"%s\",\"executor_source\":\"%s\","
                        + "\"cold_definition\":\"first scenario invocation before its warmup; only the first scenario starts in a cold JVM\","
                        + "\"warmup_samples\":%d,\"measurement_samples\":%d,\"samples\":[%s]}",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(),
                classHash(ParallelBatchCpuHelper.class), classHash(BatchExecutor.class),
                classSource(ParallelBatchCpuHelper.class), classSource(BatchExecutor.class),
                SAMPLES, SAMPLES, String.join(",", rows));
        var output = Path.of(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json);
        System.out.println(json);
    }

    private static String classHash(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }

    private static String classSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
    }

    private static long[] gcMetrics() {
        long count = 0, millis = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, gc.getCollectionCount());
            millis += Math.max(0, gc.getCollectionTime());
        }
        return new long[] {count, millis};
    }

    private static Runnable extraction(AEKey[] keys, boolean legacy, boolean reserve, boolean shared) {
        var inputs = new IPatternDetails.IInput[keys.length];
        var inventory = new ListCraftingInventory(ignored -> {});
        for (int slot = 0; slot < keys.length; slot++) {
            inputs[slot] = new Input(new GenericStack[]{new GenericStack(keys[slot], 2)});
            // Repeated keys represent distinct slots, backed by one inventory entry.
            if (inventory.extract(keys[slot], Long.MAX_VALUE, Actionable.SIMULATE) == 0) {
                inventory.insert(keys[slot], STOCK, Actionable.MODULATE);
            }
        }
        var details = new Pattern(inputs, shared);
        Map<AEKey, Long> reserved = reserve ? Map.of(keys[0], STOCK - 128) : Collections.emptyMap();
        long expectedCopies = reserve ? 64 : 512;
        return () -> {
            var result = legacy
                    ? ParallelBatchCpuHelper.bulkExtract(details, inventory, 512, true, reserved)
                    : ParallelBatchCpuHelper.bulkExtract(details, inventory, 512, true, reserved, null);
            if (result == null || result.actualCopies != expectedCopies) {
                throw new AssertionError("batch capacity changed");
            }
            var prototype = ParallelBatchCpuHelper.cloneSingleCopy(result);
            for (int slot = 0; slot < keys.length; slot++) {
                long expected = shared && slot == 0 ? 2 : 2 * expectedCopies;
                if (result.scaledInputs[slot].get(keys[slot]) != expected
                        || prototype[slot].get(keys[slot]) != 2) {
                    throw new AssertionError("slot accounting changed");
                }
                prototype[slot].clear();
                if (result.scaledInputs[slot].get(keys[slot]) != expected) {
                    throw new AssertionError("provider copy aliases owned input");
                }
            }
            ParallelBatchCpuHelper.reinject(result, result.actualCopies, inventory);
            for (var key : keys) {
                if (inventory.extract(key, Long.MAX_VALUE, Actionable.SIMULATE) != STOCK) {
                    throw new AssertionError("refund changed inventory");
                }
            }
            sink = result;
        };
    }

    private static String measure(String name, Runnable action) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("allocation metrics unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long[] coldGc = gcMetrics();
        long coldBefore = bean.getThreadAllocatedBytes(thread), coldStarted = System.nanoTime();
        action.run();
        long coldNanos = System.nanoTime() - coldStarted;
        long coldBytes = bean.getThreadAllocatedBytes(thread) - coldBefore;
        long[] afterColdGc = gcMetrics();
        long[] warmupGc = gcMetrics();
        for (int w = 0; w < SAMPLES; w++) {
            for (int i = 0; i < ITERATIONS; i++) action.run();
        }
        long[] afterWarmupGc = gcMetrics();
        var nanos = new double[SAMPLES];
        var bytes = new double[SAMPLES];
        var gcCounts = new long[SAMPLES];
        var gcMillis = new long[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            long[] gcBefore = gcMetrics();
            long before = bean.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) action.run();
            nanos[sample] = (System.nanoTime() - started) / (double) ITERATIONS;
            bytes[sample] = (bean.getThreadAllocatedBytes(thread) - before) / (double) ITERATIONS;
            long[] gcAfter = gcMetrics();
            gcCounts[sample] = gcAfter[0] - gcBefore[0];
            gcMillis[sample] = gcAfter[1] - gcBefore[1];
        }
        String rawNanos = Arrays.toString(nanos), rawBytes = Arrays.toString(bytes);
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        return String.format(Locale.ROOT,
                "{\"scenario\":\"%s\",\"iterations\":%d,\"p50_ns\":%.1f,\"p95_ns\":%.1f,\"bytes_per_op\":%.1f,"
                        + "\"cold_ns\":%d,\"cold_bytes\":%d,\"cold_gc_count\":%d,\"cold_gc_ms\":%d,"
                        + "\"warmup_gc_count\":%d,\"warmup_gc_ms\":%d,\"raw_ns_per_op\":%s,\"raw_bytes_per_op\":%s,"
                        + "\"sample_gc_count\":%s,\"sample_gc_ms\":%s}",
                name, ITERATIONS, nanos[9], nanos[18], bytes[9], coldNanos, coldBytes,
                afterColdGc[0] - coldGc[0], afterColdGc[1] - coldGc[1],
                afterWarmupGc[0] - warmupGc[0], afterWarmupGc[1] - warmupGc[1],
                rawNanos, rawBytes, Arrays.toString(gcCounts), Arrays.toString(gcMillis));
    }

    private record Input(GenericStack[] templates) implements IPatternDetails.IInput {
        @Override public GenericStack[] getPossibleInputs() { return templates; }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey key, Level level) { return templates[0].what() == key; }
        @Override public AEKey getRemainingKey(AEKey key) { return null; }
    }

    private record Pattern(IInput[] inputs, boolean shared) implements IPatternDetails, SharedBatchInputPattern {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public GenericStack[] getOutputs() { return new GenericStack[0]; }
        @Override public boolean isSharedBatchInput(int slot, AEKey key) { return shared && slot == 0; }
    }
}
