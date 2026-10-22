package com.moakiee.thunderbolt.core.storage.cell;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.Locale;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Real storage engine and inventory facade in separate baseline/candidate JVMs. */
public final class SmallExactTransferBenchmark {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long THREAD = Thread.currentThread().threadId();
    private static final IActionSource SOURCE = IActionSource.empty();
    private static volatile long sink;
    private static final int BATCH = 256;

    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT);
            BigIndexedStorageTest.bootstrap();
            THREADS.setThreadAllocatedMemoryEnabled(true);
            THREADS.setThreadCpuTimeEnabled(true);
            for (int size : new int[] {1, 1024}) {
                for (String kind : new String[] {"engine_small", "engine_long_sized", "engine_simulate",
                        "inventory_small", "engine_legacy", "engine_wide"}) {
                    String selected = System.getProperty("benchmark.scenario", "");
                    if (selected.isEmpty() || selected.equals(kind + "_" + size)) benchmark(size, kind);
                }
            }
        } finally {
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            net.minecraft.Util.shutdownExecutors();
        }
    }

    private static void benchmark(int size, String kind) {
        var keys = new AEKey[size];
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var inventory = new IndexedStorageCellInventory(new ItemStack(Items.PAPER),
                IndexedCellTestFixtures.definition(), registries, null);
        var store = inventory.storage();
        var initial = kind.equals("engine_wide") ? BigInteger.TEN.pow(100)
                : kind.equals("engine_long_sized") ? BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)
                : BigInteger.valueOf(1_000_000);
        for (int i = 0; i < size; i++) {
            keys[i] = IndexedCellConservationStressTest.variant(i);
            inventory.insertBig(keys[i], initial, Actionable.MODULATE, SOURCE);
        }
        var before = store.snapshotExact();
        var requested = kind.equals("engine_long_sized") ? BigInteger.valueOf(Long.MAX_VALUE)
                : BigInteger.valueOf(4096);
        boolean facade = kind.equals("inventory_small");
        boolean legacy = kind.equals("engine_legacy");
        var mode = kind.equals("engine_simulate") ? Actionable.SIMULATE : Actionable.MODULATE;
        long warmup = Long.getLong("benchmark.warmupMillis", 1000) * 1_000_000L;
        long duration = Long.getLong("benchmark.sampleMillis", 150) * 1_000_000L;
        for (int sample = -1; sample < 7; sample++) {
            long beforeVersion = store.getModCount();
            long bytes = THREADS.getThreadAllocatedBytes(THREAD);
            long cpu = THREADS.getCurrentThreadCpuTime();
            long started = System.nanoTime();
            long deadline = started + (sample < 0 ? warmup : duration);
            long pairs = 0;
            long checksum = 0;
            do {
                for (int i = 0; i < BATCH; i++) {
                    var key = keys[(int) ((pairs + i) * 0x9e3779b9L) & (size - 1)];
                    if (facade) {
                        checksum += inventory.insertBig(key, requested, mode, SOURCE).longValue();
                        checksum += inventory.extractBig(key, requested, mode, SOURCE).longValue();
                    } else if (legacy) {
                        checksum += store.insert(key, requested.longValue(), mode);
                        checksum += store.extract(key, requested.longValue(), mode);
                    } else {
                        checksum += store.insertExact(key, requested, mode).longValue();
                        checksum += store.extractExact(key, requested, mode).longValue();
                    }
                }
                pairs += BATCH;
            } while (System.nanoTime() < deadline);
            long elapsed = System.nanoTime() - started;
            long cpuElapsed = THREADS.getCurrentThreadCpuTime() - cpu;
            long allocated = THREADS.getThreadAllocatedBytes(THREAD) - bytes;
            sink = checksum;
            if (checksum != pairs * 2 * requested.longValue()) throw new AssertionError("Transfer result");
            if (!before.equals(store.snapshotExact())) throw new AssertionError("Conservation");
            if (mode == Actionable.SIMULATE && beforeVersion != store.getModCount())
                throw new AssertionError("Simulation mutated storage");
            if (sample >= 0) System.out.printf(Locale.ROOT,
                    "{\"scenario\":\"%s_%d\",\"sample\":%d,\"pairs\":%d,\"ns_per_op\":%.4f,"
                    + "\"cpu_ns_per_op\":%.4f,\"bytes_per_op\":%.4f,\"signature\":\"%d:%s:%s\"}%n",
                    kind, size, sample, pairs, (double) elapsed / pairs, (double) cpuElapsed / pairs,
                    (double) allocated / pairs, size, initial, requested);
        }
    }
}
