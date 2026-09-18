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
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        try {
            var field = appeng.core.definitions.ItemDefinition.class.getDeclaredField("item");
            field.setAccessible(true);
            var deferred = field.get(appeng.core.definitions.AEItems.MISSING_CONTENT);
            var holder =
                    net.neoforged.neoforge.registries.DeferredHolder.class.getDeclaredField(
                            "holder");
            holder.setAccessible(true);
            holder.set(deferred, Items.BARRIER.builtInRegistryHolder());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        try {
            appeng.api.stacks.AEKeyTypesInternal.getRegistry();
        } catch (IllegalStateException ignored) {
            var registry =
                    new net.minecraft.core.MappedRegistry<appeng.api.stacks.AEKeyType>(
                            appeng.api.stacks.AEKeyType.REGISTRY_KEY,
                            com.mojang.serialization.Lifecycle.stable(),
                            false);
            appeng.api.stacks.AEKeyTypesInternal.setRegistry(registry);
            appeng.api.stacks.AEKeyTypes.register(appeng.api.stacks.AEKeyType.items());
            appeng.api.stacks.AEKeyTypes.register(appeng.api.stacks.AEKeyType.fluids());
        }
    }

    @Test
    void nonOptedInCellsAdvertiseOnlyOneExtractableLongChunk() {
        var definition =
                new IIndexedStorageCellItem() {
                    public net.minecraft.resources.ResourceLocation storageType(
                            net.minecraft.world.item.ItemStack stack) {
                        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
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
        loaded.load(tag, registries);
        assertEquals(store.snapshotExact(), loaded.snapshotExact());
        loaded.extractExact(key, n.subtract(BigInteger.valueOf(4)), Actionable.MODULATE);
        assertEquals(BigInteger.ONE, loaded.getAmountExact(key));
        var narrowed = loaded.persist(tag, registries);
        assertTrue(narrowed.getCompound("bigAmounts").isEmpty());
        var again = new IndexedStorage();
        again.load(narrowed, registries);
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
        upgraded.load(old.persist(null, registries), registries);
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
        loaded.load(old, registries);
        loaded.load(compact, registries);
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
