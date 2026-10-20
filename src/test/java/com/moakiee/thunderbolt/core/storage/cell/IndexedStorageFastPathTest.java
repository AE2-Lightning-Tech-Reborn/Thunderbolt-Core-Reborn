package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.*;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IndexedStorageFastPathTest {
    private static final BigInteger MAX_126 = BigInteger.ONE.shiftLeft(126).subtract(BigInteger.ONE);
    private static final BigInteger MAX_63 = BigInteger.ONE.shiftLeft(63).subtract(BigInteger.ONE);
    private static final IActionSource SOURCE = IActionSource.empty();

    @BeforeAll
    static void bootstrap() { BigIndexedStorageTest.bootstrap(); }

    private static RegistryAccess registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static AEKey variant(int id) {
        var stack = new ItemStack(Items.IRON_INGOT);
        var tag = new CompoundTag();
        tag.putInt("variant", id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return AEItemKey.of(stack);
    }

    @Test
    void reloadAndLegacyUpgradeRestoreExactGuardAtThe126BitCeiling() {
        for (boolean legacy : new boolean[] {false, true}) {
            for (boolean sameKey : new boolean[] {false, true}) {
                var a = variant(0);
                var b = sameKey ? a : variant(1);
                var store = new IndexedStorage();
                store.enableArbitraryPrecision();
                store.insertExact(a, MAX_126, Actionable.MODULATE);
                var root = store.persist(null, registries());
                if (legacy) root.remove("arbitraryPrecision");
                var restored = new IndexedStorage();
                restored.load(root, registries());
                assertTrue(restored.isTypeTotalSaturated(a.getType()));
                restored.enableArbitraryPrecision();
                assertEquals(1, restored.insert(b, 1, Actionable.SIMULATE));
                assertEquals(MAX_126, restored.getAmountExact(a));
                assertEquals(1, restored.insert(b, 1, Actionable.MODULATE));
                assertEquals(sameKey ? MAX_126.add(BigInteger.ONE) : MAX_126, restored.getAmountExact(a));
                assertEquals(MAX_126, projectedTotal(restored, a.getType()));
                assertEquals(1, restored.extract(b, 1, Actionable.MODULATE));
                assertEquals(MAX_126, restored.getAmountExact(a));
                var reloaded = new IndexedStorage();
                reloaded.load(restored.persist(null, registries()), registries());
                assertEquals(restored.snapshotExact(), reloaded.snapshotExact());
            }
        }
    }

    @Test
    void ordinaryTransfersCrossLongAnd126BitTypeBoundariesWithoutLosingStock() {
        var a = variant(0);
        var b = variant(1);
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        store.insertExact(a, MAX_126.subtract(BigInteger.TEN), Actionable.MODULATE);
        assertEquals(20, store.insert(b, 20, Actionable.MODULATE));
        assertEquals(MAX_126, projectedTotal(store, a.getType()));
        assertEquals(15, store.extract(a, 15, Actionable.MODULATE));
        assertEquals(MAX_126.subtract(BigInteger.valueOf(5)), projectedTotal(store, a.getType()));
        assertEquals(MAX_126.subtract(BigInteger.valueOf(25)), store.getAmountExact(a));
        assertEquals(BigInteger.valueOf(20), store.getAmountExact(b));
        assertEquals(30, store.insert(a, 30, Actionable.MODULATE));
        var root = store.persist(null, registries());
        var restored = new IndexedStorage();
        restored.load(root, registries());
        assertEquals(store.snapshotExact(), restored.snapshotExact());
        assertEquals(20, restored.extract(variant(1), Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(MAX_126.add(BigInteger.valueOf(5)), restored.getAmountExact(a));
        assertEquals(10, restored.extract(a, 10, Actionable.MODULATE));
        assertEquals(MAX_126.subtract(BigInteger.valueOf(5)), projectedTotal(restored, a.getType()));
        restored.extractExact(a, MAX_126, Actionable.MODULATE);
        assertTrue(restored.getTypeCounts().isEmpty());
        assertTrue(restored.getTypeAmountLo().isEmpty());
        assertTrue(restored.getTypeAmountHi().isEmpty());
    }

    @Test
    void repeatedKeyReadsStayCorrectAcrossAliasesMissesIdReuseCompactionAndLoad() {
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var a = variant(100);
        var alias = variant(100);
        var b = variant(200);
        assertNotSame(a, alias);
        assertFalse(store.containsKey(a));
        assertEquals(64, store.insert(alias, 64, Actionable.SIMULATE));
        assertFalse(store.containsKey(a));
        store.insert(alias, 64, Actionable.MODULATE);
        assertEquals(64, store.getAmount(a));
        assertEquals(64, store.extract(alias, 64, Actionable.MODULATE));
        store.insert(b, 7, Actionable.MODULATE);
        assertEquals(0, store.getAmount(a));
        assertEquals(0, store.extract(a, 1, Actionable.MODULATE));
        assertEquals(7, store.getAmount(b));
        for (int i = 0; i < 1024; i++) store.insert(variant(2000 + i), 1, Actionable.MODULATE);
        for (int i = 0; i < 1000; i++) store.extract(variant(2000 + i), 1, Actionable.MODULATE);
        var target = variant(3023);
        assertEquals(1, store.getAmount(target));
        store.persist(null, registries());
        assertEquals(1, store.getAmount(target));
        var replacement = new IndexedStorage();
        replacement.enableArbitraryPrecision();
        replacement.insert(a, 9, Actionable.MODULATE);
        store.load(replacement.persist(null, registries()), registries());
        assertFalse(store.containsKey(target));
        assertEquals(0, store.getAmount(target));
        assertEquals(9, store.getAmount(a));
        assertEquals(replacement.snapshotExact(), store.snapshotExact());
    }

    @Test
    void simulatedUnlimitedInventoryTransfersRespectTheExactResourceBoundary() {
        var inv = new IndexedStorageCellInventory(new ItemStack(Items.PAPER),
                IndexedCellTestFixtures.definition(), registries(), null);
        var key = variant(1);
        var max = BigInteger.ONE.shiftLeft(com.moakiee.thunderbolt.core.storage.big.BigAmounts.MAX_BITS)
                .subtract(BigInteger.ONE);
        inv.insertBig(key, max.subtract(BigInteger.TEN), Actionable.MODULATE, SOURCE);
        assertEquals(10, inv.insert(key, 64, Actionable.SIMULATE, SOURCE));
        assertEquals(max.subtract(BigInteger.TEN), inv.storage().getAmountExact(key));
        assertEquals(10, inv.insert(key, 64, Actionable.MODULATE, SOURCE));
        assertEquals(0, inv.insert(key, 1, Actionable.MODULATE, SOURCE));
        assertEquals(64, inv.extract(key, 64, Actionable.MODULATE, SOURCE));
        assertEquals(max.subtract(BigInteger.valueOf(64)), inv.storage().getAmountExact(key));
    }

    @Test
    void simulationsAfterExternalChangesKeepSummaryReadOnlyAndNextTransferResynchronizes() {
        var stack = new ItemStack(Items.PAPER);
        long[] callbacks = {0};
        var inv = new IndexedStorageCellInventory(stack, IndexedCellTestFixtures.definition(),
                registries(), () -> callbacks[0]++);
        var a = variant(1);
        var b = variant(2);
        inv.insert(a, 64, Actionable.MODULATE, SOURCE);
        inv.storage().insert(b, 16, Actionable.MODULATE);
        var summary = stack.get(DataComponents.CUSTOM_DATA);
        assertEquals(32, inv.insert(b, 32, Actionable.SIMULATE, SOURCE));
        assertEquals(32, inv.extract(a, 32, Actionable.SIMULATE, SOURCE));
        assertEquals(summary, stack.get(DataComponents.CUSTOM_DATA));
        assertEquals(1, callbacks[0]);
        assertEquals(16, inv.insert(b, 16, Actionable.MODULATE, SOURCE));
        assertEquals(2, inv.storage().getTotalTypes());
        assertEquals(16 + 96 / a.getType().getAmountPerByte(), inv.usedBytes());
        assertEquals(64, inv.extract(a, 64, Actionable.MODULATE, SOURCE));
        assertEquals(1, inv.storage().getTotalTypes());
        assertEquals(8 + 32 / b.getType().getAmountPerByte(), inv.usedBytes());
        var updated = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        assertEquals(1, updated.getInt("ae2lt:types"));
        assertEquals(inv.usedBytes(), updated.getLong("ae2lt:bytes"));
        assertEquals(3, callbacks[0]);
    }

    @Test
    void finiteCellSimulationStillChecksCapacityAndFilterWithoutChangingStock() {
        var allowed = AEItemKey.of(Items.STONE);
        var base = IndexedCellTestFixtures.definition();
        var definition = new IIndexedStorageCellItem() {
            public net.minecraft.resources.ResourceLocation storageType(ItemStack s) { return base.storageType(s); }
            public double idleDrain(ItemStack s) { return 0; }
            public boolean accepts(ItemStack s, AEKey key, IActionSource source) { return allowed.equals(key); }
            public ByteTracker createByteTracker(ItemStack s, IndexedStorage storage) {
                var tracker = new ByteTracker(storage::getTotalTypes);
                tracker.configure(8, 1, 9, 0);
                return tracker;
            }
            public void writeSummary(ItemStack s, IndexedCellSummary summary) { base.writeSummary(s, summary); }
        };
        var stack = new ItemStack(Items.PAPER);
        long[] callbacks = {0};
        var inv = new IndexedStorageCellInventory(stack, definition, registries(), () -> callbacks[0]++);
        assertEquals(allowed.getType().getAmountPerByte(), inv.insert(allowed, 64, Actionable.SIMULATE, SOURCE));
        assertEquals(0, inv.storage().getTotalTypes());
        assertFalse(stack.has(DataComponents.CUSTOM_DATA));
        assertEquals(0, inv.insert(AEItemKey.of(Items.GOLD_INGOT), 1, Actionable.SIMULATE, SOURCE));
        assertEquals(0, callbacks[0]);
        assertEquals(4, inv.insert(allowed, 4, Actionable.MODULATE, SOURCE));
        var before = stack.get(DataComponents.CUSTOM_DATA);
        assertEquals(4, inv.extract(allowed, 64, Actionable.SIMULATE, SOURCE));
        assertEquals(before, stack.get(DataComponents.CUSTOM_DATA));
        assertEquals(4, inv.storage().getAmount(allowed));
        assertEquals(1, callbacks[0]);
        assertEquals(4, inv.extract(allowed, 64, Actionable.MODULATE, SOURCE));
        assertEquals(0, inv.storage().getTotalTypes());
        assertEquals(0, inv.usedBytes());
        assertEquals(2, callbacks[0]);
    }

    @Test
    void randomLongAndExactTransfersSimulationsAndReloadsMatchAnIndependentLedger() {
        var keys = new AEKey[] {variant(0), variant(1), variant(2), variant(3),
                AEItemKey.of(Items.GOLD_INGOT), AEFluidKey.of(Fluids.WATER), AEFluidKey.of(Fluids.LAVA)};
        var random = new Random(10618);
        var ledger = new HashMap<AEKey, BigInteger>();
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        CompoundTag root = null;
        for (int i = 0; i < 12_000; i++) {
            var key = keys[random.nextInt(keys.length)];
            var before = ledger.getOrDefault(key, BigInteger.ZERO);
            var mode = random.nextInt(4) == 0 ? Actionable.SIMULATE : Actionable.MODULATE;
            boolean insert = random.nextBoolean();
            boolean exact = i >= 4000 && random.nextInt(4) == 0;
            var amount = exact ? new BigInteger(new int[] {63, 126, 512}[random.nextInt(3)], random)
                    : BigInteger.valueOf(random.nextBoolean() ? random.nextInt(256) : Long.MAX_VALUE);
            var expected = insert ? amount : before.min(amount);
            var actual = exact
                    ? (insert ? store.insertExact(key, amount, mode) : store.extractExact(key, amount, mode))
                    : BigInteger.valueOf(insert ? store.insert(key, amount.longValueExact(), mode)
                            : store.extract(key, amount.longValueExact(), mode));
            assertEquals(expected, actual, "transfer " + i);
            if (mode == Actionable.MODULATE) {
                var after = insert ? before.add(expected) : before.subtract(expected);
                if (after.signum() == 0) ledger.remove(key); else ledger.put(key, after);
            }
            assertEquals(ledger.getOrDefault(key, BigInteger.ZERO), store.getAmountExact(key));
            if (i % 113 == 0) {
                root = store.persist(root, registries());
                var loaded = new IndexedStorage();
                loaded.load(root, registries());
                assertEquals(ledger, loaded.snapshotExact());
                // Continue with the restored object and the same incremental root.
                store = loaded;
                var totals = new HashMap<AEKeyType, BigInteger>();
                ledger.forEach((k, n) -> totals.merge(k.getType(), n, BigInteger::add));
                for (var type : new AEKeyType[] {AEKeyType.items(), AEKeyType.fluids()}) {
                    assertEquals(totals.getOrDefault(type, BigInteger.ZERO).min(MAX_126),
                            projectedTotal(store, type));
                }
            }
        }
        assertEquals(ledger, store.snapshotExact());
    }

    @Test
    void inventoryByteSummaryMatchesExactLedgerThroughLongTransfersAndTypeRemoval() {
        var item = new ItemStack(Items.PAPER);
        var inv = new IndexedStorageCellInventory(item, IndexedCellTestFixtures.definition(), registries(), null);
        var keys = new AEKey[] {variant(0), variant(1), AEFluidKey.of(Fluids.WATER)};
        var totals = new HashMap<AEKey, BigInteger>();
        var random = new Random(1818);
        for (int i = 0; i < 3000; i++) {
            var key = keys[random.nextInt(keys.length)];
            long amount = random.nextInt(300);
            boolean insert = random.nextBoolean();
            var before = totals.getOrDefault(key, BigInteger.ZERO);
            long accepted = insert ? inv.insert(key, amount, Actionable.MODULATE, SOURCE)
                    : inv.extract(key, amount, Actionable.MODULATE, SOURCE);
            var next = insert ? before.add(BigInteger.valueOf(accepted)) : before.subtract(BigInteger.valueOf(accepted));
            if (next.signum() == 0) totals.remove(key); else totals.put(key, next);
            assertEquals(totals, inv.storage().snapshotExact());
            var byType = new HashMap<AEKeyType, BigInteger>();
            totals.forEach((k, n) -> byType.merge(k.getType(), n, BigInteger::add));
            var bytes = BigInteger.valueOf(totals.size() * 8L);
            for (var e : byType.entrySet()) {
                var apb = BigInteger.valueOf(e.getKey().getAmountPerByte());
                bytes = bytes.add(e.getValue().add(apb).subtract(BigInteger.ONE).divide(apb));
            }
            assertEquals(bytes.longValueExact(), inv.usedBytes());
            if (accepted > 0) {
                var summary = item.get(DataComponents.CUSTOM_DATA).copyTag();
                assertEquals(totals.size(), summary.getInt("ae2lt:types"));
                assertEquals(bytes.longValueExact(), summary.getLong("ae2lt:bytes"));
            }
        }
        inv.insertBig(keys[0], BigInteger.ONE.shiftLeft(512), Actionable.MODULATE, SOURCE);
        assertEquals(Long.MAX_VALUE, inv.usedBytes());
        inv.extractBig(keys[0], BigInteger.ONE.shiftLeft(513), Actionable.MODULATE, SOURCE);
        assertTrue(inv.usedBytes() < Long.MAX_VALUE);
    }

    private static BigInteger projectedTotal(IndexedStorage store, AEKeyType type) {
        return BigInteger.valueOf(store.getTypeAmountHi().getLong(type)).shiftLeft(63)
                .add(BigInteger.valueOf(store.getTypeAmountLo().getLong(type)));
    }

    @Test
    void rebuiltTotalsPromoteOnHighWordAndCarryOverflowAndPreserveWideValues() {
        for (var values : List.of(
                List.of(MAX_126, BigInteger.ONE),
                List.of(BigInteger.ONE.shiftLeft(125), BigInteger.ONE.shiftLeft(125)),
                List.of(BigInteger.ONE.shiftLeft(512), BigInteger.valueOf(19), MAX_126),
                List.of(BigInteger.valueOf(Long.MAX_VALUE), BigInteger.ONE))) {
            var store = new IndexedStorage();
            store.enableArbitraryPrecision();
            for (int i = 0; i < values.size(); i++) store.insertExact(variant(i), values.get(i), Actionable.MODULATE);
            store.insertExact(AEFluidKey.of(Fluids.WATER), BigInteger.valueOf(5000), Actionable.MODULATE);
            var expected = store.snapshotExact();
            var loaded = new IndexedStorage();
            loaded.load(store.persist(null, registries()), registries());
            assertEquals(expected, loaded.snapshotExact());
            assertEquals(values.stream().reduce(BigInteger.ZERO, BigInteger::add).min(MAX_126),
                    projectedTotal(loaded, AEKeyType.items()));
            assertEquals(BigInteger.valueOf(5000), projectedTotal(loaded, AEKeyType.fluids()));
            // Subtract enough to leave a small total: a saturated projection must retain its exact ledger.
            for (int i = 0; i < values.size(); i++) loaded.extractExact(variant(i), values.get(i), Actionable.MODULATE);
            assertEquals(BigInteger.ZERO, projectedTotal(loaded, AEKeyType.items()));
            assertEquals(Map.of(AEFluidKey.of(Fluids.WATER), BigInteger.valueOf(5000)), loaded.snapshotExact());
        }
    }

    @Test
    void highWordSnapshotsStayIsolatedAcrossCarriesBorrowReuseGrowthAndReload() throws Exception {
        var type = net.minecraft.resources.ResourceLocation.parse("ae2lt:infinite_cell");
        var id = UUID.randomUUID();
        var data = new IndexedCellStorageSavedData();
        var store = data.getOrCreateStorage(type, id, registries());
        store.enableArbitraryPrecision();
        var key = variant(0);
        store.insert(key, Long.MAX_VALUE - 1, Actionable.MODULATE);
        var snapshots = new ArrayList<CompoundTag>();
        var expected = new ArrayList<CompoundTag>();
        net.minecraft.nbt.Tag previousHigh = null;
        for (int step = 0; step < 8; step++) {
            switch (step) {
                case 1 -> store.insert(key, 1, Actionable.MODULATE);
                case 2 -> store.insert(key, 1, Actionable.MODULATE); // carry
                case 3 -> store.extract(key, 1, Actionable.MODULATE); // borrow
                case 4 -> store.insertExact(key, BigInteger.ONE.shiftLeft(512), Actionable.MODULATE);
                case 5 -> { // recycle a wide slot as an ordinary quantity
                    store.extractExact(key, BigInteger.ONE.shiftLeft(513), Actionable.MODULATE);
                    store.insert(variant(1000), 64, Actionable.MODULATE);
                }
                case 6 -> { for (int i = 1; i < 300; i++) store.insert(variant(i), 64, Actionable.MODULATE); }
                case 7 -> store.load(store.persist(null, registries()), registries());
            }
            data.markStorageDirty(type, id, store);
            var snapshot = data.snapshotForWrite(registries());
            var payload = snapshot.getCompound("Stores").getCompound(type.toString()).getCompound(id.toString());
            var high = payload.get("hi");
            if (step == 1) assertSame(previousHigh, high);
            else if (step > 1 && step < 7) assertNotSame(previousHigh, high);
            previousHigh = high;
            var loaded = new IndexedStorage();
            loaded.load(payload, registries());
            assertEquals(store.snapshotExact(), loaded.snapshotExact());
            snapshots.add(snapshot);
            expected.add(snapshot.copy());
        }
        // Compress every old generation after all live mutations have completed.
        for (int i = 0; i < snapshots.size(); i++) {
            assertEquals(expected.get(i), snapshots.get(i));
            var bytes = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(snapshots.get(i), bytes);
            assertEquals(expected.get(i), net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(bytes.toByteArray()), net.minecraft.nbt.NbtAccounter.unlimitedHeap()));
        }
    }

    @Test
    void amountOnlyWorldSnapshotsReuseDetachedKeysAndStructuralChangesReplaceThem() {
        var type = net.minecraft.resources.ResourceLocation.parse("ae2lt:infinite_cell");
        var id = UUID.randomUUID();
        var data = new IndexedCellStorageSavedData();
        var store = data.getOrCreateStorage(type, id, registries());
        store.enableArbitraryPrecision();
        for (int i = 0; i < 32; i++) store.insert(variant(i), 64, Actionable.MODULATE);
        var initial = data.snapshotForWrite(registries());
        var expected = initial.copy();
        var initialPayload = initial.getCompound("Stores").getCompound(type.toString()).getCompound(id.toString());

        store.insert(variant(0), 1, Actionable.MODULATE);
        var amounts = data.snapshotForWrite(registries());
        var amountPayload = amounts.getCompound("Stores").getCompound(type.toString()).getCompound(id.toString());
        assertSame(initialPayload.get("keys"), amountPayload.get("keys"));
        assertNotSame(initialPayload.get("lo"), amountPayload.get("lo"));
        assertEquals(expected, initial);

        store.extract(variant(1), 64, Actionable.MODULATE);
        store.insert(variant(100), 64, Actionable.MODULATE);
        var changed = data.snapshotForWrite(registries());
        var changedPayload = changed.getCompound("Stores").getCompound(type.toString()).getCompound(id.toString());
        assertNotSame(amountPayload.get("keys"), changedPayload.get("keys"));
        assertEquals(expected, initial);
        var restored = new IndexedStorage();
        restored.load(changedPayload, registries());
        assertEquals(store.snapshotExact(), restored.snapshotExact());

        // Reusing the engine for a different save must not reuse the old list.
        store.load(initialPayload, registries());
        var reencoded = store.persist(null, registries());
        var replacedKeys = store.snapshotKeysForWrite(reencoded.getList("keys", net.minecraft.nbt.Tag.TAG_COMPOUND));
        assertNotSame(changedPayload.get("keys"), replacedKeys);
        assertEquals(initialPayload.get("keys"), replacedKeys);
        // Full serialization can replace the source list without changing the inventory.
        var anotherRoot = store.persist(null, registries());
        var anotherKeys = store.snapshotKeysForWrite(anotherRoot.getList("keys", net.minecraft.nbt.Tag.TAG_COMPOUND));
        assertNotSame(replacedKeys, anotherKeys);
        assertEquals(replacedKeys, anotherKeys);
        assertEquals(expected, initial);
    }

    @Test
    void backgroundSaveSnapshotSurvivesAmountChangesSlotReuseCompactionAndWideNarrowing() throws Exception {
        var type = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ae2lt", "infinite_cell");
        var id = UUID.randomUUID();
        var data = new IndexedCellStorageSavedData();
        var store = data.getOrCreateStorage(type, id, registries());
        store.enableArbitraryPrecision();
        var keys = new AEKey[32];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = variant(i);
            store.insertExact(keys[i], BigInteger.ONE.shiftLeft(512).add(BigInteger.valueOf(i)), Actionable.MODULATE);
        }
        var snapshot = data.snapshotForWrite(registries());
        var expected = snapshot.copy();
        var compression = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                var bytes = new java.io.ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.writeCompressed(snapshot, bytes);
                return bytes.toByteArray();
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        // Amount-only updates mutate the cached lo/hi arrays and wide map in place.
        store.extractExact(keys[0], BigInteger.ONE.shiftLeft(512).subtract(BigInteger.ONE), Actionable.MODULATE);
        data.persistStorage(type, id, store, registries());
        // Reuse a removed ID with a different NBT key before persisting.
        store.extractExact(keys[1], BigInteger.ONE.shiftLeft(513), Actionable.MODULATE);
        store.insert(variant(100), 64, Actionable.MODULATE);
        data.persistStorage(type, id, store, registries());
        for (int i = 2; i < 30; i++) store.extractExact(keys[i], BigInteger.ONE.shiftLeft(513), Actionable.MODULATE);
        var latest = data.snapshotForWrite(registries());
        assertEquals(expected, snapshot);
        var decoded = net.minecraft.nbt.NbtIo.readCompressed(
                new java.io.ByteArrayInputStream(compression.get()), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        assertEquals(expected, decoded);
        var restored = new IndexedStorage();
        restored.load(latest.getCompound("Stores").getCompound(type.toString()).getCompound(id.toString()), registries());
        assertEquals(store.snapshotExact(), restored.snapshotExact());
        assertEquals(BigInteger.ONE, restored.getAmountExact(keys[0]));
        assertEquals(BigInteger.valueOf(64), restored.getAmountExact(variant(100)));
    }

    @Test
    void repeatedWorldSnapshotsStayIsolatedAcrossFlushRemovalAndRecreatedCellIds() throws Exception {
        var type = net.minecraft.resources.ResourceLocation.parse("ae2lt:infinite_cell");
        var otherType = net.minecraft.resources.ResourceLocation.parse("test:other_cell");
        var id = UUID.randomUUID();
        var data = new IndexedCellStorageSavedData();
        var first = data.getOrCreateStorage(type, id, registries());
        var second = data.getOrCreateStorage(otherType, id, registries());
        first.enableArbitraryPrecision();
        second.enableArbitraryPrecision();
        var key = variant(1);
        first.insert(key, 100, Actionable.MODULATE);
        second.insertExact(key, BigInteger.ONE.shiftLeft(512), Actionable.MODULATE);
        var snapshots = new ArrayList<CompoundTag>();
        var expected = new ArrayList<CompoundTag>();
        for (int step = 0; step < 8; step++) {
            var snapshot = data.snapshotForWrite(registries());
            snapshots.add(snapshot);
            expected.add(snapshot.copy());
            first.insert(key, 1, Actionable.MODULATE);
            if (step % 2 == 0) data.persistStorage(type, id, first, registries());
            else data.save(new CompoundTag(), registries());
        }
        // Delete both namespaces independently, then reuse the UUID with a new storage object.
        data.removeCell(type, id);
        var removed = data.snapshotForWrite(registries());
        assertFalse(removed.getCompound("Stores").getCompound(type.toString()).contains(id.toString()));
        assertTrue(removed.getCompound("Stores").getCompound(otherType.toString()).contains(id.toString()));
        var replacement = data.getOrCreateStorage(type, id, registries());
        replacement.insert(variant(2), 42, Actionable.MODULATE);
        var recreated = data.snapshotForWrite(registries());
        var restored = new IndexedStorage();
        restored.load(recreated.getCompound("Stores").getCompound(type.toString()).getCompound(id.toString()), registries());
        assertEquals(replacement.snapshotExact(), restored.snapshotExact());
        data.removeCell(otherType, id);
        var latest = data.snapshotForWrite(registries());
        assertFalse(latest.getCompound("Stores").contains(otherType.toString()));
        var workers = new ArrayList<java.util.concurrent.CompletableFuture<CompoundTag>>();
        for (var snapshot : snapshots) workers.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                var bytes = new java.io.ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.writeCompressed(snapshot, bytes);
                return net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(bytes.toByteArray()),
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }));
        for (int i = 0; i < workers.size(); i++) assertEquals(expected.get(i), workers.get(i).get());
    }

    @Test
    void reloadingADirtyObjectInvalidatesObserversAndResetsPendingCompaction() {
        var original = new IndexedStorage();
        original.enableArbitraryPrecision();
        original.insert(variant(90), 123, Actionable.MODULATE);
        var root = original.persist(null, registries());
        var reused = new IndexedStorage();
        reused.enableArbitraryPrecision();
        for (int i = 0; i < 1000; i++) reused.insert(variant(i), 1, Actionable.MODULATE);
        for (int i = 0; i < 999; i++) reused.extract(variant(i), 1, Actionable.MODULATE);
        long previousVersion = reused.getModCount();
        reused.load(root, registries());
        assertNotEquals(previousVersion, reused.getModCount());
        assertFalse(reused.needsPersist());
        reused.insert(variant(1), 10, Actionable.MODULATE);
        var restored = new IndexedStorage();
        restored.load(reused.persist(root, registries()), registries());
        assertEquals(reused.snapshotExact(), restored.snapshotExact());
    }

    @Test
    void perTypeSlotsMatchAnIndependentAggregateThroughCarryRemovalAndReload() {
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var items = new AEKey[] {variant(1), variant(2), variant(3)};
        var fluid = AEFluidKey.of(Fluids.WATER);
        var random = new Random(4711);
        var ledger = new HashMap<AEKey, BigInteger>();
        var live = new ArrayList<AEKey>(List.of(items[0], items[1], fluid));
        for (var key : live) {
            store.insert(key, 1, Actionable.MODULATE);
            ledger.put(key, BigInteger.ONE);
        }
        for (int step = 0; step < 4000; step++) {
            boolean insert = random.nextBoolean();
            var key = live.get(random.nextInt(live.size()));
            var before = ledger.get(key);
            long amount = insert
                    ? (random.nextInt(8) == 0 ? Long.MAX_VALUE - random.nextInt(3) : 1 + random.nextInt(500))
                    : 1 + random.nextLong(Math.max(1, before.min(BigInteger.valueOf(500)).longValueExact()));
            long accepted = insert ? store.insert(key, amount, Actionable.MODULATE)
                    : store.extract(key, amount, Actionable.MODULATE);
            if (accepted == 0) continue;
            var after = insert ? before.add(BigInteger.valueOf(accepted))
                    : before.subtract(BigInteger.valueOf(accepted));
            if (after.signum() == 0) {
                ledger.remove(key);
                live.remove(key);
                if (live.isEmpty()) {
                    var replacement = items[random.nextInt(items.length)];
                    store.insert(replacement, 1, Actionable.MODULATE);
                    ledger.put(replacement, BigInteger.ONE);
                    live.add(replacement);
                }
            } else {
                ledger.put(key, after);
            }
            assertSlotsMatchLedger(store, ledger);
        }
        var loaded = new IndexedStorage();
        loaded.load(store.persist(null, registries()), registries());
        assertEquals(store.snapshotExact(), loaded.snapshotExact());
        assertSlotsMatchLedger(loaded, ledger);
        // Remove everything: a type whose last key leaves must drop its slot aggregates.
        for (var key : new ArrayList<>(live)) {
            store.extractExact(key, store.getAmountExact(key), Actionable.MODULATE);
            ledger.remove(key);
        }
        assertTrue(ledger.isEmpty());
        assertEquals(0, store.getTotalTypes());
        assertTrue(store.getTypeCounts().isEmpty());
        assertTrue(store.getTypeAmountLo().isEmpty());
        assertTrue(store.getTypeAmountHi().isEmpty());
    }

    private static void assertSlotsMatchLedger(IndexedStorage store, Map<AEKey, BigInteger> ledger) {
        var counts = new HashMap<AEKeyType, Integer>();
        var totals = new HashMap<AEKeyType, BigInteger>();
        ledger.forEach((key, amount) -> {
            counts.merge(key.getType(), 1, Integer::sum);
            totals.merge(key.getType(), amount, BigInteger::add);
        });
        assertEquals(counts, new HashMap<>(store.getTypeCounts()));
        for (var entry : totals.entrySet()) {
            var projected = entry.getValue().min(MAX_126);
            assertEquals(projected.and(MAX_63).longValueExact(), store.getTypeAmountLo().getLong(entry.getKey()));
            assertEquals(projected.shiftRight(63).longValueExact(), store.getTypeAmountHi().getLong(entry.getKey()));
        }
    }

    @Test
    void enumerationAppendsToEmptyCountersAndMergesIntoPopulatedOnes() {
        var store = new IndexedStorage();
        var keys = new AEKey[] {variant(0), variant(1), AEFluidKey.of(Fluids.WATER)};
        store.insert(keys[0], 5, Actionable.MODULATE);
        store.insert(keys[1], 7, Actionable.MODULATE);
        store.insert(keys[2], 9000, Actionable.MODULATE);

        var empty = new KeyCounter();
        store.getAvailableStacks(empty);
        assertEquals(3, empty.size());
        assertEquals(5, empty.get(keys[0]));
        assertEquals(7, empty.get(keys[1]));
        assertEquals(9000, empty.get(keys[2]));

        // A populated counter must be merged, not replaced.
        var populated = new KeyCounter();
        populated.add(keys[0], 100);
        populated.add(variant(9), 1);
        store.getAvailableStacks(populated);
        assertEquals(4, populated.size());
        assertEquals(105, populated.get(keys[0]));
        assertEquals(7, populated.get(keys[1]));
        assertEquals(1, populated.get(variant(9)));

        // An empty store must not add anything to either kind of counter.
        var blank = new IndexedStorage();
        var counter = new KeyCounter();
        counter.add(keys[0], 3);
        blank.getAvailableStacks(counter);
        assertEquals(1, counter.size());
        assertEquals(3, counter.get(keys[0]));
        var fresh = new KeyCounter();
        blank.getAvailableStacks(fresh);
        assertTrue(fresh.isEmpty());
    }

    @Test
    void exactSnapshotsAreReadOnlyViewsOfOneLedgerPass() {
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        for (int i = 0; i < 64; i++) store.insert(variant(i), 1_000 + i, Actionable.MODULATE);
        var snapshot = store.snapshotExact();
        assertEquals(64, snapshot.size());
        assertEquals(BigInteger.valueOf(1_037), snapshot.get(variant(37)));
        assertEquals(Set.of(variant(0), variant(63)),
                snapshot.keySet().stream().filter(k -> k.equals(variant(0)) || k.equals(variant(63)))
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(64, snapshot.entrySet().size());
        var iterated = new HashMap<AEKey, BigInteger>();
        snapshot.forEach(iterated::put);
        assertEquals(64, iterated.size());
        assertEquals(BigInteger.valueOf(1_000), iterated.get(variant(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put(variant(0), BigInteger.ZERO));
        // The snapshot must not follow later engine changes.
        store.insert(variant(0), 1, Actionable.MODULATE);
        assertEquals(BigInteger.valueOf(1_000), snapshot.get(variant(0)));
        assertEquals(BigInteger.valueOf(1_001), store.snapshotExact().get(variant(0)));
    }

    @Test
    void denseExactSnapshotKeepsMapContractsAndSurvivesIdReuseCompactionAndReload() {
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var expected = new HashMap<AEKey, BigInteger>();
        for (int i = 0; i < 1024; i++) {
            var amount = i == 100 ? BigInteger.ONE.shiftLeft(512) : BigInteger.valueOf(i + 1);
            store.insertExact(variant(i), amount, Actionable.MODULATE);
            expected.put(variant(i), amount);
        }
        var snapshot = store.snapshotExact();
        assertEquals(expected, snapshot);
        assertEquals(snapshot, expected);
        assertEquals(expected.hashCode(), snapshot.hashCode());
        assertEquals(expected.keySet(), snapshot.keySet());
        assertEquals(expected.entrySet(), snapshot.entrySet());
        assertEquals(1024, snapshot.values().size());
        assertNull(snapshot.get(variant(2048)));
        assertFalse(snapshot.containsKey(variant(2048)));
        var iterated = new HashMap<AEKey, BigInteger>();
        snapshot.forEach(iterated::put);
        assertEquals(expected, iterated);
        var entry = snapshot.entrySet().iterator().next();
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue(BigInteger.ZERO));
        var iterator = snapshot.entrySet().iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.keySet().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values().clear());
        for (int i = 0; i < 1000; i++) store.extractExact(variant(i), expected.get(variant(i)), Actionable.MODULATE);
        store.insert(variant(2048), 7, Actionable.MODULATE);
        var sparse = store.snapshotExact();
        store.load(store.persist(null, registries()), registries());
        assertEquals(sparse, store.snapshotExact());
        assertEquals(expected, snapshot);
    }

    @Test
    void byteTrackerDetectsReloadedCustomChannelUnitsAndForgetsRemovedChannels() {
        int[] units = {1024};
        var type = new AEKeyType(net.minecraft.resources.ResourceLocation.parse("test:energy"),
                AEItemKey.class, net.minecraft.network.chat.Component.literal("Test energy")) {
            public int getAmountPerByte() { return units[0]; }
            public com.mojang.serialization.MapCodec<? extends AEKey> codec() { return AEItemKey.MAP_CODEC; }
            public AEKey readFromPacket(net.minecraft.network.RegistryFriendlyByteBuf input) { return null; }
        };
        var tracker = new ByteTracker(() -> 1);
        tracker.configure(8, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        tracker.onInsert(type, 2048, true);
        assertEquals(10, tracker.getUsedBytes());
        assertTrue(tracker.hasCurrentUnits());
        units[0] = 128;
        assertFalse(tracker.hasCurrentUnits());
        var lo = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<AEKeyType>();
        var hi = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<AEKeyType>();
        var counts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<AEKeyType>();
        lo.put(type, 2048); counts.put(type, 1);
        tracker.rebuild(lo, hi, counts, 1);
        assertTrue(tracker.hasCurrentUnits());
        assertEquals(24, tracker.getUsedBytes());
        tracker.onExtract(type, 2048, true);
        units[0] = 4096;
        assertTrue(tracker.hasCurrentUnits());
        assertEquals(0, tracker.getUsedBytes());
    }
}
