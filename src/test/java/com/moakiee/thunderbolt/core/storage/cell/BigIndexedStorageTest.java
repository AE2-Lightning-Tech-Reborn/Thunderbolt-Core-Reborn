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
        assertEquals(amount, inv.snapshotBig(source).get(key));
        assertEquals(
                amount,
                inv.extractBig(key, amount.multiply(BigInteger.TWO), Actionable.SIMULATE, source));
        assertEquals(amount.multiply(BigInteger.TWO), inv.storage().getAmountExact(key));
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
