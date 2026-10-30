package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.moakiee.thunderbolt.core.storage.big.BigAmounts;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Conservation oracle independent of the cell's projections, indices, and persistence format.
 * These are deterministic stress tests, not timing assertions or an in-game integration test.
 */
class IndexedCellConservationStressTest {
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger MAX = BigInteger.ONE.shiftLeft(BigAmounts.MAX_BITS).subtract(BigInteger.ONE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final IActionSource SOURCE = IActionSource.empty();

    private static AutoCloseable keyRegistry;

    @org.junit.jupiter.api.AfterAll
    static void closeKeyRegistry() throws Exception { keyRegistry.close(); }

    @BeforeAll
    static void bootstrap() {
        BigIndexedStorageTest.bootstrap(); keyRegistry = IndexedCellTestFixtures.openKeyRegistry();
    }

    private static RegistryAccess registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    static AEKey variant(int id) {
        var stack = new ItemStack(Items.IRON_INGOT);
        var tag = new CompoundTag();
        tag.putInt("variant", id);
        tag.putString("payload", "cell-conservation-" + id);
        stack.setTag(tag);
        return AEItemKey.of(stack);
    }

    @ParameterizedTest
    @ValueSource(longs = {20261019, 0x126, 0x16384, 918})
    void mixedTransfersConserveEveryKeyAcrossCellsAndReloads(long seed) throws Exception {
        int operations = Integer.getInteger("cellStress.operations", 250_000);
        int keyCount = Integer.getInteger("cellStress.keys", 1024);
        assertTrue(operations > 0 && keyCount >= 4);
        var random = new Random(seed);
        var keys = new AEKey[keyCount];
        for (int i = 0; i < keyCount - 2; i++) keys[i] = variant(i);
        keys[keyCount - 2] = AEFluidKey.of(Fluids.WATER);
        keys[keyCount - 1] = AEFluidKey.of(Fluids.LAVA);
        var cells = new IndexedStorageCellInventory[3];
        var stacks = new ItemStack[cells.length];
        var ledgers = new BigInteger[cells.length][keyCount];
        var imported = new BigInteger[keyCount];
        var exported = new BigInteger[keyCount];
        var roots = new CompoundTag[cells.length];
        java.util.Arrays.fill(imported, ZERO);
        java.util.Arrays.fill(exported, ZERO);
        for (int c = 0; c < cells.length; c++) {
            stacks[c] = new ItemStack(Items.PAPER);
            cells[c] = new IndexedStorageCellInventory(stacks[c],
                    IndexedCellTestFixtures.definition(), registries(), null);
            java.util.Arrays.fill(ledgers[c], ZERO);
        }
        long started = System.nanoTime();
        int reloads = 0;
        for (int step = 0; step < operations; step++) {
            int c = random.nextInt(cells.length);
            int k = random.nextInt(keyCount);
            // Equal but nonidentical keys exercise the last-lookup cache as well as its misses.
            AEKey key = k < keyCount - 2 && step % 31 == 0 ? variant(k) : keys[k];
            var cell = cells[c];
            var before = ledgers[c][k];
            boolean exact = random.nextInt(8) == 0;
            BigInteger amount = exact ? switch (random.nextInt(5)) {
                case 0 -> BigInteger.ONE.shiftLeft(63);
                case 1 -> BigInteger.ONE.shiftLeft(126).add(BigInteger.valueOf(random.nextInt(32) - 16));
                case 2 -> BigInteger.ONE.shiftLeft(512).add(BigInteger.valueOf(random.nextInt(256)));
                case 3 -> MAX.subtract(BigInteger.valueOf(random.nextInt(32)));
                default -> before; // Delete keys completely, then reuse their IDs.
            } : random.nextBoolean() ? LONG_MAX : BigInteger.valueOf(random.nextInt(257));
            int operation = random.nextInt(6);
            if (operation <= 1) {
                boolean inserting = operation == 0;
                var expected = inserting ? amount.min(MAX.subtract(before)) : amount.min(before);
                var simulated = transfer(cell, key, amount, exact, inserting, Actionable.SIMULATE);
                assertEquals(expected, simulated, "simulation seed=" + seed + " step=" + step);
                assertEquals(before, cell.storage().getAmountExact(key));
                var actual = transfer(cell, key, amount, exact, inserting, Actionable.MODULATE);
                assertEquals(expected, actual, "modulation seed=" + seed + " step=" + step);
                ledgers[c][k] = inserting ? before.add(actual) : before.subtract(actual);
                if (inserting) imported[k] = imported[k].add(actual);
                else exported[k] = exported[k].add(actual);
            } else if (operation <= 3) {
                int destination = (c + 1 + random.nextInt(cells.length - 1)) % cells.length;
                var expected = amount.min(before).min(MAX.subtract(ledgers[destination][k]));
                assertEquals(amount.min(before), transfer(cell, key, amount, exact, false, Actionable.SIMULATE));
                assertEquals(expected, transfer(cells[destination], key, amount.min(before),
                        exact, true, Actionable.SIMULATE));
                assertEquals(expected, transfer(cell, key, expected, exact, false, Actionable.MODULATE));
                assertEquals(expected, transfer(cells[destination], key, expected, exact, true, Actionable.MODULATE));
                ledgers[c][k] = before.subtract(expected);
                ledgers[destination][k] = ledgers[destination][k].add(expected);
                assertEquals(ledgers[destination][k], cells[destination].storage().getAmountExact(key));
            } else {
                long modCount = cell.storage().getModCount();
                var summary = stacks[c].getTag() == null ? null : stacks[c].getTag().copy();
                boolean inserting = operation == 4;
                assertEquals(inserting ? amount.min(MAX.subtract(before)) : amount.min(before),
                        transfer(cell, key, amount, exact, inserting, Actionable.SIMULATE));
                assertEquals(modCount, cell.storage().getModCount());
                assertEquals(summary, stacks[c].getTag());
            }
            assertEquals(ledgers[c][k], cell.storage().getAmountExact(key),
                    "stock seed=" + seed + " step=" + step);

            if ((step + 1) % 8192 == 0 || step + 1 == operations) {
                for (int index = 0; index < cells.length; index++) {
                    assertLedger(cells[index].storage(), keys, ledgers[index]);
                    roots[index] = cells[index].storage().persist(roots[index], registries());
                    var bytes = new ByteArrayOutputStream();
                    NbtIo.writeCompressed(roots[index], bytes);
                    roots[index] = NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()));
                    // Reuse the observed engine: this must invalidate the inventory's byte cache.
                    cells[index].storage().load(roots[index], registries());
                    assertLedger(cells[index].storage(), keys, ledgers[index]);
                    reloads++;
                }
                for (int index = 0; index < keyCount; index++) {
                    var remaining = ZERO;
                    for (var ledger : ledgers) remaining = remaining.add(ledger[index]);
                    assertEquals(imported[index], remaining.add(exported[index]), "conservation key=" + index);
                }
            }
        }
        System.out.printf("CELL_CONSERVATION seed=%d operations=%d keys=%d cells=%d reloads=%d seconds=%.3f%n",
                seed, operations, keyCount, cells.length, reloads, (System.nanoTime() - started) / 1e9);
    }

    private static BigInteger transfer(IndexedStorageCellInventory cell, AEKey key, BigInteger amount,
            boolean exact, boolean insert, Actionable mode) {
        if (exact) return insert ? cell.insertBig(key, amount, mode, SOURCE) : cell.extractBig(key, amount, mode, SOURCE);
        return BigInteger.valueOf(insert ? cell.insert(key, amount.longValueExact(), mode, SOURCE)
                : cell.extract(key, amount.longValueExact(), mode, SOURCE));
    }

    private static void assertLedger(IndexedStorage store, AEKey[] keys, BigInteger[] amounts) {
        var expected = new HashMap<AEKey, BigInteger>();
        for (int k = 0; k < keys.length; k++) if (amounts[k].signum() > 0) expected.put(keys[k], amounts[k]);
        assertEquals(expected.size(), store.getTotalTypes());
        assertEquals(expected, store.snapshotExact());
    }

    @Test
    void forgeFileSavesAndDelayedSnapshotsKeepTheirGenerationThroughCompactionAndIdReuse(@TempDir Path directory) throws Exception {
        int keyCount = 16_384;
        int generations = 12;
        var type = new ResourceLocation("ae2lt:infinite_cell");
        var id = UUID.fromString("00000000-0000-0000-0000-000000000126");
        var data = new IndexedCellStorageSavedData();
        var store = data.getOrCreateStorage(type, id, registries());
        store.enableArbitraryPrecision();
        var expected = new HashMap<AEKey, BigInteger>();
        for (int k = 0; k < keyCount; k++) {
            var key = variant(k);
            var amount = k % 127 == 0 ? BigInteger.ONE.shiftLeft(512) : BigInteger.valueOf(k + 1L);
            store.insertExact(key, amount, Actionable.MODULATE);
            expected.put(key, amount);
        }
        var savedLedgers = new java.util.ArrayList<Map<AEKey, BigInteger>>();
        var releaseWorker = new CountDownLatch(1);
        // Forge saves synchronously. This worker belongs only to the test and deliberately
        // delays detached payload compression until every subsequent live mutation is done.
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var writes = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int generation = 0; generation < generations; generation++) {
                data.markStorageDirty(type, id, store);
                data.save(directory.resolve("generation-" + generation + ".dat").toFile());
                var detachedRoot = new CompoundTag();
                detachedRoot.put("data", data.snapshotForWrite(registries()));
                net.minecraft.nbt.NbtUtils.addCurrentDataVersion(detachedRoot);
                var detachedFile = directory.resolve("detached-" + generation + ".dat").toFile();
                writes.add(worker.submit(() -> {
                    if (!releaseWorker.await(60, TimeUnit.SECONDS)) throw new AssertionError("Save producer timed out");
                    NbtIo.writeCompressed(detachedRoot, detachedFile);
                    NbtIo.writeCompressed(detachedRoot, directory.resolve("detached-latest.dat").toFile());
                    return null;
                }));
                savedLedgers.add(Map.copyOf(expected));
                // Queue the same target repeatedly as well: final file must be the final generation.
                data.setDirty();
                data.save(directory.resolve("latest.dat").toFile());
                assertFalse(data.isDirty());
                if (generation == 2) {
                    // Delete more than two thirds to force deferred compaction.
                    for (int k = 0; k < keyCount - 100; k++) {
                        var key = variant(k);
                        store.extractExact(key, MAX, Actionable.MODULATE);
                        expected.remove(key);
                    }
                } else {
                    for (int k = 0; k < 512; k++) {
                        var key = variant(generation * 512 + k);
                        var before = expected.getOrDefault(key, ZERO);
                        if ((k & 1) == 0) {
                            store.insert(key, Long.MAX_VALUE, Actionable.MODULATE);
                            expected.put(key, before.add(LONG_MAX));
                        } else {
                            store.extractExact(key, MAX, Actionable.MODULATE);
                            expected.remove(key);
                        }
                    }
                }
            }
        } finally {
            releaseWorker.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(60, TimeUnit.SECONDS), "Detached file writer did not finish");
        }
        for (var write : writes) write.get(60, TimeUnit.SECONDS);
        for (int generation = 0; generation < generations; generation++) {
            assertFileLedger(directory.resolve("detached-" + generation + ".dat"), type, id, savedLedgers.get(generation));
            assertFileLedger(directory.resolve("generation-" + generation + ".dat"), type, id, savedLedgers.get(generation));
        }
        assertFileLedger(directory.resolve("latest.dat"), type, id, savedLedgers.get(savedLedgers.size() - 1));
        assertFileLedger(directory.resolve("detached-latest.dat"), type, id, savedLedgers.get(savedLedgers.size() - 1));
        System.out.printf("CELL_FILE_STRESS keys=%d generations=%d forgeWrites=%d detachedWrites=%d%n",
                keyCount, generations, generations * 2, generations * 2);
    }

    private static void assertFileLedger(Path path, ResourceLocation type, UUID id,
            Map<AEKey, BigInteger> expected) throws Exception {
        var root = NbtIo.readCompressed(path.toFile());
        assertTrue(root.contains("DataVersion"));
        var loaded = new IndexedStorage();
        loaded.load(root.getCompound("data").getCompound("Stores").getCompound(type.toString())
                .getCompound(id.toString()), registries());
        assertEquals(expected, loaded.snapshotExact(), path.getFileName().toString());
    }
}
