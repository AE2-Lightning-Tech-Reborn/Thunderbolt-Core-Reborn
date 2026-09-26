package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.*;

class BigIndexedStorageTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraftforge.fml.loading.LoadingModList.of(
                List.of(),
                List.of(),
                new net.minecraftforge.fml.loading.EarlyLoadingException(
                        "test bootstrap", null, List.of()));
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static appeng.api.stacks.AEKey decodeItemKey(
            net.minecraft.nbt.CompoundTag tag,
            net.minecraft.core.HolderLookup.Provider ignored) {
        var item = BuiltInRegistries.ITEM.get(
                new net.minecraft.resources.ResourceLocation(tag.getString("id")));
        return AEItemKey.of(item);
    }

    private static net.minecraft.nbt.CompoundTag legacyRoot(long[] low, long[] high) {
        var root = new net.minecraft.nbt.CompoundTag();
        var entries = new net.minecraft.nbt.ListTag();
        for (int index = 0; index < low.length; index++) {
            var entry = new net.minecraft.nbt.CompoundTag();
            var keyTag = new net.minecraft.nbt.CompoundTag();
            keyTag.putInt("index", index);
            entry.put("key", keyTag);
            entries.add(entry);
        }
        root.put("keys", entries);
        root.putLongArray("lo", low);
        root.putLongArray("hi", high);
        return root;
    }

    @Test
    void legacyInsertAt126BitLimitReportsOnlyStoredUnits() {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var store = new IndexedStorage();
        var maximum = BigInteger.ONE.shiftLeft(126).subtract(BigInteger.ONE);
        store.load(legacyRoot(new long[]{Long.MAX_VALUE - 3}, new long[]{Long.MAX_VALUE}),
                (tag, ignored) -> key, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));

        assertEquals(3, store.insert(key, 10, Actionable.SIMULATE));
        assertEquals(3, store.insert(key, 10, Actionable.MODULATE));
        assertEquals(maximum, store.getAmountExact(key));
        long modCount = store.getModCount();
        assertEquals(0, store.insert(key, 1, Actionable.SIMULATE));
        assertEquals(0, store.insert(key, 1, Actionable.MODULATE));
        assertEquals(modCount, store.getModCount());
        assertEquals(7, store.extract(key, 7, Actionable.MODULATE));
        assertEquals(7, store.insert(key, 10, Actionable.MODULATE));
        assertEquals(maximum, store.getAmountExact(key));
    }

    @Test
    void legacySaturatedTypeTotalRecoversAfterExtractionAndRandomUpdates() {
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var maximum = BigInteger.ONE.shiftLeft(126).subtract(BigInteger.ONE);
        var store = new IndexedStorage();
        store.load(legacyRoot(new long[]{Long.MAX_VALUE - 10, 20},
                        new long[]{Long.MAX_VALUE, 0}),
                (tag, ignored) -> tag.getInt("index") == 0 ? iron : gold,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        BigInteger ironAmount = maximum.subtract(BigInteger.TEN);
        BigInteger goldAmount = BigInteger.valueOf(20);
        var random = new Random(20260926);
        for (int iteration = 0; iteration < 5_000; iteration++) {
            var key = random.nextBoolean() ? iron : gold;
            var current = key.equals(iron) ? ironAmount : goldAmount;
            long requested = 1 + random.nextInt(128);
            BigInteger delta;
            if (random.nextInt(4) == 0) {
                delta = current.min(BigInteger.valueOf(requested));
                assertEquals(delta.longValueExact(), store.extract(key, requested, Actionable.MODULATE));
                delta = delta.negate();
            } else {
                delta = maximum.subtract(current).min(BigInteger.valueOf(requested));
                assertEquals(delta.longValueExact(), store.insert(key, requested, Actionable.SIMULATE));
                assertEquals(delta.longValueExact(), store.insert(key, requested, Actionable.MODULATE));
            }
            if (key.equals(iron)) ironAmount = ironAmount.add(delta);
            else goldAmount = goldAmount.add(delta);
            assertEquals(ironAmount, store.getAmountExact(iron));
            assertEquals(goldAmount, store.getAmountExact(gold));
            var projected = ironAmount.add(goldAmount).min(maximum);
            assertEquals(projected, BigInteger.valueOf(store.getTypeAmountHi().getLong(iron.getType()))
                    .shiftLeft(63).add(BigInteger.valueOf(store.getTypeAmountLo().getLong(iron.getType()))));
        }
    }


    @Test
    void exactSnapshotRemainsImmutableAndIndependentOfLaterUpdates() {
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var key = AEItemKey.of(Items.IRON_INGOT);
        var wideAmount = BigInteger.TEN.pow(100);
        assertEquals(Map.of(), store.snapshotExact());
        store.insertExact(key, wideAmount, Actionable.MODULATE);
        var snapshot = store.snapshotExact();
        assertEquals(Map.of(key, wideAmount), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put(key, BigInteger.ONE));
        store.extractExact(key, wideAmount, Actionable.MODULATE);
        assertEquals(Map.of(key, wideAmount), snapshot);
        assertEquals(Map.of(), store.snapshotExact());
    }

    @Test
    void availableStacksSkipsHolesAndSaturatesExistingCounter() {
        var store = new IndexedStorage();
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var removed = AEItemKey.of(Items.PAPER);
        store.insert(iron, 3, Actionable.MODULATE);
        store.insert(removed, 2, Actionable.MODULATE);
        store.insert(gold, 7, Actionable.MODULATE);
        store.extract(removed, 2, Actionable.MODULATE);

        var available = new appeng.api.stacks.KeyCounter();
        available.set(iron, Long.MAX_VALUE - 1);
        store.getAvailableStacks(available);

        assertEquals(Long.MAX_VALUE, available.get(iron));
        assertEquals(7, available.get(gold));
        assertEquals(0, available.get(removed));
    }

    @Test
    void nonOptedInCellsAdvertiseOnlyOneExtractableLongChunk() {
        var definition =
                new IIndexedStorageCellItem() {
                    public net.minecraft.resources.ResourceLocation storageType(
                            net.minecraft.world.item.ItemStack stack) {
                        return new net.minecraft.resources.ResourceLocation(
                                "thunderbolt", "test_legacy_cell");
                    }

                    public ByteTracker createByteTracker(
                            net.minecraft.world.item.ItemStack stack, IndexedStorage storage) {
                        var tracker = new ByteTracker(storage::getTotalTypes);
                        tracker.configure(0, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
                        return tracker;
                    }

                    public double idleDrain(net.minecraft.world.item.ItemStack stack) {
                        return 0;
                    }
                };
        var inv =
                new IndexedStorageCellInventory(
                        new net.minecraft.world.item.ItemStack(Items.PAPER),
                        definition,
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
                        null);
        var key = AEItemKey.of(Items.IRON_INGOT);
        var source = appeng.api.networking.security.IActionSource.empty();
        assertEquals(Long.MAX_VALUE, inv.insert(key, Long.MAX_VALUE, Actionable.MODULATE, source));
        assertEquals(Long.MAX_VALUE, inv.insert(key, Long.MAX_VALUE, Actionable.MODULATE, source));
        var amount = BigInteger.valueOf(Long.MAX_VALUE);
        assertEquals(amount.multiply(BigInteger.TWO), inv.storage().getAmountExact(key));
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var removed = AEItemKey.of(Items.DIAMOND);
        assertEquals(7, inv.insert(gold, 7, Actionable.MODULATE, source));
        assertEquals(2, inv.insert(removed, 2, Actionable.MODULATE, source));
        assertEquals(2, inv.extract(removed, 2, Actionable.MODULATE, source));
        long beforeSnapshot = inv.storage().getModCount();
        var snapshot = inv.snapshotBig(source);
        assertEquals(Map.of(key, amount, gold, BigInteger.valueOf(7)), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertEquals(beforeSnapshot, inv.storage().getModCount());
        assertEquals(
                amount,
                inv.extractBig(key, amount.multiply(BigInteger.TWO), Actionable.SIMULATE, source));
        assertEquals(amount.multiply(BigInteger.TWO), inv.storage().getAmountExact(key));
        assertEquals(7, inv.extract(gold, 7, Actionable.MODULATE, source));
        assertEquals(BigInteger.valueOf(7), snapshot.get(gold));
        assertFalse(inv.snapshotBig(source).containsKey(gold));
    }

    @Test
    void exactAndLegacyViewsShareOneInventoryAcrossSaveAndLoad() {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var n = BigInteger.TEN.pow(100);
        assertEquals(n, store.insertExact(key, n, Actionable.SIMULATE));
        assertEquals(BigInteger.ZERO, store.getAmountExact(key));
        store.insertExact(key, n, Actionable.MODULATE);
        assertEquals(Long.MAX_VALUE, store.getAmount(key));
        assertEquals(3, store.extract(key, 3, Actionable.MODULATE));
        assertEquals(n.subtract(BigInteger.valueOf(3)), store.getAmountExact(key));
        var tag = store.persist(null, registries);
        var loaded = new IndexedStorage();
        loaded.load(tag, BigIndexedStorageTest::decodeItemKey, registries);
        assertEquals(store.snapshotExact(), loaded.snapshotExact());
        loaded.extractExact(key, n.subtract(BigInteger.valueOf(4)), Actionable.MODULATE);
        assertEquals(BigInteger.ONE, loaded.getAmountExact(key));
        var narrowed = loaded.persist(tag, registries);
        assertTrue(narrowed.getCompound("bigAmounts").isEmpty());
        var again = new IndexedStorage();
        again.load(narrowed, BigIndexedStorageTest::decodeItemKey, registries);
        assertEquals(1, again.extract(key, Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(0, again.getTotalTypes());
        assertEquals(BigInteger.ZERO, again.getAmountExact(key));
    }

    @Test
    void oldDualLongSaveUpgradesExactlyWithoutCopyingStock() {
        var key = AEItemKey.of(Items.GOLD_INGOT);
        var old = new IndexedStorage();
        old.insert(key, Long.MAX_VALUE, Actionable.MODULATE);
        old.insert(key, Long.MAX_VALUE, Actionable.MODULATE);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var upgraded = new IndexedStorage();
        upgraded.load(
                old.persist(null, registries), BigIndexedStorageTest::decodeItemKey, registries);
        upgraded.enableArbitraryPrecision();
        assertEquals(
                BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO),
                upgraded.getAmountExact(key));
        assertEquals(
                upgraded.getAmountExact(key),
                upgraded.extractExact(key, BigInteger.TEN.pow(100), Actionable.SIMULATE));
        assertEquals(1, upgraded.getTotalTypes());
    }

    @Test
    void interleavedLegacyAndExactOperationsMatchAnIndependentLedger() {
        var key = AEItemKey.of(Items.REDSTONE);
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var expected = BigInteger.ZERO;
        var random = new Random(918);
        for (int i = 0; i < 1000; i++) {
            var n = new BigInteger(500, random);
            if (random.nextBoolean()) {
                store.insertExact(key, n, Actionable.MODULATE);
                expected = expected.add(n);
            } else {
                assertEquals(expected.min(n), store.extractExact(key, n, Actionable.MODULATE));
                expected = expected.subtract(expected.min(n));
            }
            long small = random.nextInt(1000);
            long taken = expected.min(BigInteger.valueOf(small)).longValueExact();
            assertEquals(taken, store.extract(key, small, Actionable.MODULATE));
            expected = expected.subtract(BigInteger.valueOf(taken));
            assertEquals(expected, store.getAmountExact(key));
        }
    }

    @Test
    void compactionAndReloadOnAnExistingObjectDoNotReuseOldKeyAmounts() {
        var keys =
                BuiltInRegistries.ITEM.stream()
                        .filter(i -> i != Items.AIR)
                        .limit(240)
                        .map(AEItemKey::of)
                        .toList();
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var expected = new LinkedHashMap<appeng.api.stacks.AEKey, BigInteger>();
        for (int i = 0; i < keys.size(); i++) {
            var n = BigInteger.TEN.pow(100).add(BigInteger.valueOf(i));
            store.insertExact(keys.get(i), n, Actionable.MODULATE);
            expected.put(keys.get(i), n);
        }
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var old = store.persist(null, registries);
        for (int i = 0; i < 200; i++) {
            store.extractExact(keys.get(i), BigInteger.TEN.pow(101), Actionable.MODULATE);
            expected.remove(keys.get(i));
        }
        var compact = store.persist(old, registries);
        var loaded = new IndexedStorage();
        loaded.load(old, BigIndexedStorageTest::decodeItemKey, registries);
        loaded.load(compact, BigIndexedStorageTest::decodeItemKey, registries);
        assertEquals(expected, loaded.snapshotExact());
        assertEquals(40, loaded.getTotalTypes());
        assertEquals(expected, store.snapshotExact());
    }

    @Test
    void resourceBoundaryReturnsPartialAcceptanceWithoutLosingExistingStock() {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var maximum =
                BigInteger.ONE
                        .shiftLeft(com.moakiee.thunderbolt.core.storage.big.BigAmounts.MAX_BITS)
                        .subtract(BigInteger.ONE);
        store.insertExact(key, maximum.subtract(BigInteger.TEN), Actionable.MODULATE);
        assertEquals(
                BigInteger.TEN,
                store.insertExact(key, BigInteger.valueOf(20), Actionable.MODULATE));
        assertEquals(maximum, store.getAmountExact(key));
        assertEquals(0, store.insert(key, 1, Actionable.MODULATE));
    }
}
