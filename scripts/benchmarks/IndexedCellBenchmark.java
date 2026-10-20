package com.moakiee.thunderbolt.core.storage.cell;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.IOUtilities;

/**
 * Same public-API workload in separate baseline/candidate JVMs.
 * Timings are diagnostics only; correctness assertions run outside each measured region.
 */
public final class IndexedCellBenchmark {
    private static volatile Object sink;
    private static volatile long checksum;
    private static final IActionSource SOURCE = IActionSource.empty();
    private static final int PAIRS = 100_000;
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 7;

    public static void main(String[] args) throws Exception {
        try {
            benchmark(args);
        } finally {
            // The standalone process owns Minecraft's otherwise long-lived I/O executors.
            IOUtilities.waitUntilIOWorkerComplete();
            net.minecraft.Util.shutdownExecutors();
        }
    }

    private static void benchmark(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        BigIndexedStorageTest.bootstrap();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        System.out.printf("CELL_ENV java=%s arch=%s%n",
                System.getProperty("java.runtime.version"), System.getProperty("os.arch"));
        for (int size : new int[] {1, 1024, 16_384}) {
            var keys = new AEKey[size];
            for (int i = 0; i < size; i++) keys[i] = IndexedCellConservationStressTest.variant(i);
            var cell = new IndexedStorageCellInventory(new ItemStack(Items.PAPER),
                    IndexedCellTestFixtures.definition(), registries, null);
            for (var key : keys) cell.insert(key, 1_000_000, Actionable.MODULATE, SOURCE);
            var store = cell.storage();
            var original = store.snapshotExact();
            measure(args[0], size, "inventory_long_pair", PAIRS, () -> {
                long sum = 0;
                for (int i = 0; i < PAIRS; i++) {
                    var key = keys[(i * 0x9e3779b9) & (size - 1)];
                    sum += cell.insert(key, 8, Actionable.MODULATE, SOURCE);
                    sum += cell.extract(key, 8, Actionable.MODULATE, SOURCE);
                }
                checksum = sum;
            }, () -> {
                require(checksum == PAIRS * 16L, "inventory transfer return");
                require(original.equals(store.snapshotExact()), "inventory transfer ledger");
            });
            measure(args[0], size, "engine_long_pair", PAIRS, () -> {
                long sum = 0;
                for (int i = 0; i < PAIRS; i++) {
                    var key = keys[(i * 0x9e3779b9) & (size - 1)];
                    sum += store.insert(key, 8, Actionable.MODULATE);
                    sum += store.extract(key, 8, Actionable.MODULATE);
                }
                checksum = sum;
            }, () -> {
                require(checksum == PAIRS * 16L, "engine transfer return");
                require(original.equals(store.snapshotExact()), "engine transfer ledger");
            });
            measure(args[0], size, "simulate_pair", PAIRS, () -> {
                long sum = 0;
                for (int i = 0; i < PAIRS; i++) {
                    var key = keys[(i * 0x9e3779b9) & (size - 1)];
                    sum += cell.insert(key, 8, Actionable.SIMULATE, SOURCE);
                    sum += cell.extract(key, 8, Actionable.SIMULATE, SOURCE);
                }
                checksum = sum;
            }, () -> {
                require(checksum == PAIRS * 16L, "simulation return");
                require(original.equals(store.snapshotExact()), "simulation ledger");
            });
            int snapshots = Math.max(30, 30_000 / size);
            measure(args[0], size, "exact_snapshot", snapshots, () -> {
                for (int i = 0; i < snapshots; i++) sink = store.snapshotExact();
            }, () -> require(original.equals(sink), "exact snapshot"));
            measure(args[0], size, "enumeration", snapshots, () -> {
                for (int i = 0; i < snapshots; i++) {
                    var counter = new KeyCounter();
                    store.getAvailableStacks(counter);
                    sink = counter;
                }
            }, () -> {
                var counter = (KeyCounter) sink;
                require(counter.size() == size, "enumeration size");
                for (var key : keys) require(counter.get(key) == 1_000_000, "enumeration amount");
            });
            CompoundTag[] root = {store.persist(null, registries)};
            int persists = Math.max(100, 10_000 / size);
            measure(args[0], size, "amount_persist_128_pairs", persists, () -> {
                for (int i = 0; i < persists; i++) {
                    for (int k = 0; k < Math.min(size, 128); k++) {
                        store.insert(keys[k], 8, Actionable.MODULATE);
                        store.extract(keys[k], 8, Actionable.MODULATE);
                    }
                    root[0] = store.persist(root[0], registries);
                }
            }, () -> {
                var loaded = new IndexedStorage();
                loaded.load(root[0], registries);
                require(original.equals(loaded.snapshotExact()), "incremental persist");
            });
            var data = new IndexedCellStorageSavedData();
            var type = ResourceLocation.parse("ae2lt:infinite_cell");
            var id = UUID.fromString("00000000-0000-0000-0000-000000000126");
            data.persistStorage(type, id, store, registries);
            var directory = Files.createTempDirectory("tb-cell-benchmark-");
            var file = directory.resolve("cells.dat");
            try {
                // Timed: server-thread snapshot preparation + enqueue. Completion outside timing.
                measure(args[0], size, "world_save_enqueue", 1, () -> {
                    store.insert(keys[0], 8, Actionable.MODULATE);
                    store.extract(keys[0], 8, Actionable.MODULATE);
                    data.markStorageDirty(type, id, store);
                    data.save(file.toFile(), registries);
                }, () -> {
                    IOUtilities.waitUntilIOWorkerComplete();
                    try {
                        var saved = net.minecraft.nbt.NbtIo.readCompressed(file,
                                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                        var loaded = new IndexedStorage();
                        loaded.load(saved.getCompound("data").getCompound("Stores").getCompound(type.toString())
                                .getCompound(id.toString()), registries);
                        require(original.equals(loaded.snapshotExact()), "world save");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            } finally {
                IOUtilities.waitUntilIOWorkerComplete();
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            }
            var empty = new IndexedStorageCellInventory(new ItemStack(Items.PAPER),
                    IndexedCellTestFixtures.definition(), registries, null);
            measure(args[0], size, "inventory_empty_pair", PAIRS, () -> {
                long sum = 0;
                for (int i = 0; i < PAIRS; i++) {
                    var key = keys[(i * 0x9e3779b9) & (size - 1)];
                    sum += empty.insert(key, 8, Actionable.MODULATE, SOURCE);
                    sum += empty.extract(key, 8, Actionable.MODULATE, SOURCE);
                }
                checksum = sum;
            }, () -> {
                require(checksum == PAIRS * 16L, "churn transfer return");
                require(empty.storage().getTotalTypes() == 0 && empty.usedBytes() == 0, "churn ledger");
            });
            var wide = java.math.BigInteger.ONE.shiftLeft(512);
            measure(args[0], size, "inventory_exact_512_pair", PAIRS, () -> {
                for (int i = 0; i < PAIRS; i++) {
                    var key = keys[(i * 0x9e3779b9) & (size - 1)];
                    sink = cell.insertBig(key, wide, Actionable.MODULATE, SOURCE);
                    sink = cell.extractBig(key, wide, Actionable.MODULATE, SOURCE);
                }
            }, () -> {
                require(wide.equals(sink), "exact transfer return");
                require(original.equals(store.snapshotExact()), "exact transfer ledger");
            });
        }
    }

    private static void measure(String variant, int keys, String name, int iterations,
            Runnable workload, Runnable verify) {
        for (int i = 0; i < WARMUPS; i++) {
            workload.run();
            verify.run();
        }
        var samples = new double[SAMPLES];
        var allocated = new double[SAMPLES];
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        require(bean.isThreadAllocatedMemorySupported(), "allocation accounting unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < SAMPLES; i++) {
            long bytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            workload.run();
            samples[i] = (System.nanoTime() - start) / (double) iterations;
            allocated[i] = (bean.getThreadAllocatedBytes(thread) - bytes) / (double) iterations;
            verify.run();
        }
        Arrays.sort(samples);
        Arrays.sort(allocated);
        System.out.printf("CELL_BENCH {\"variant\":\"%s\",\"keys\":%d,\"metric\":\"%s\","
                        + "\"median_ns\":%.3f,\"min_ns\":%.3f,\"max_ns\":%.3f,\"median_allocated_bytes\":%.3f}%n",
                variant, keys, name, samples[SAMPLES / 2], samples[0], samples[SAMPLES - 1], allocated[SAMPLES / 2]);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
