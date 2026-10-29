package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.moakiee.thunderbolt.core.storage.big.BigAmounts;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SmallExactTransferTest {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger MAX_126 = BigInteger.ONE.shiftLeft(126).subtract(BigInteger.ONE);

    @BeforeAll
    static void bootstrap() { BigIndexedStorageTest.bootstrap(); }

    private Object previousKeyTypes;

    @BeforeEach
    void installKeyTypes() throws ReflectiveOperationException {
        previousKeyTypes = IndexedCellTestFixtures.installKeyTypes();
    }

    @AfterEach
    void restoreKeyTypes() throws ReflectiveOperationException {
        IndexedCellTestFixtures.restoreKeyTypes(previousKeyTypes);
    }

    @Test
    void smallRequestsCarryBorrowRemoveAndReuseIdsAcrossReload() {
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        assertEquals(LONG_MAX, store.insertExact(iron, LONG_MAX, Actionable.MODULATE));
        assertEquals(BigInteger.TWO, store.insertExact(iron, BigInteger.TWO, Actionable.MODULATE));
        assertEquals(BigInteger.ONE, store.insertExact(gold, BigInteger.ONE, Actionable.MODULATE));
        assertEquals(LONG_MAX.add(BigInteger.TWO), store.getAmountExact(iron));
        assertEquals(BigInteger.TWO, store.extractExact(iron, BigInteger.TWO, Actionable.MODULATE));
        assertEquals(LONG_MAX, store.extractExact(iron, LONG_MAX, Actionable.MODULATE));
        assertFalse(store.containsKey(iron));
        assertEquals(BigInteger.TEN, store.insertExact(iron, BigInteger.TEN, Actionable.MODULATE));
        assertEquals(BigInteger.TEN, store.extractExact(iron, LONG_MAX, Actionable.MODULATE));
        assertEquals(Map.of(gold, BigInteger.ONE), store.snapshotExact());
        assertEquals(1, store.getTypeAmountLo().getLong(AEKeyType.items()));
        assertEquals(0, store.getTypeAmountHi().getLong(AEKeyType.items()));
        assertEquals(1, store.getTypeCounts().getInt(AEKeyType.items()));

        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var loaded = new IndexedStorage();
        loaded.load(store.persist(null, registries), registries);
        assertEquals(store.snapshotExact(), loaded.snapshotExact());
        assertEquals(BigInteger.ONE, loaded.extractExact(gold, LONG_MAX, Actionable.MODULATE));
        assertEquals(0, loaded.getTotalTypes());
    }

    @Test
    void smallTransfersCrossProjectionBoundaryAndRespectExactCeiling() {
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        // The first long transfer reaches the high-word guard; the next must fall back.
        var initial = MAX_126.subtract(LONG_MAX).subtract(BigInteger.ONE);
        store.insertExact(iron, initial, Actionable.MODULATE);
        store.insertExact(iron, LONG_MAX, Actionable.MODULATE);
        store.insertExact(gold, LONG_MAX, Actionable.MODULATE);
        store.insertExact(iron, BigInteger.TEN, Actionable.MODULATE);
        assertEquals(MAX_126.add(BigInteger.valueOf(9)), store.getAmountExact(iron));
        assertEquals(LONG_MAX, store.getAmountExact(gold));
        assertEquals(3, store.extract(iron, 3, Actionable.MODULATE));
        assertEquals(MAX_126.add(BigInteger.valueOf(6)), store.getAmountExact(iron));
        assertEquals(LONG_MAX, store.extractExact(gold, LONG_MAX, Actionable.MODULATE));

        var max = BigInteger.ONE.shiftLeft(BigAmounts.MAX_BITS).subtract(BigInteger.ONE);
        store.insertExact(gold, max.subtract(BigInteger.ONE), Actionable.MODULATE);
        assertEquals(BigInteger.ONE, store.insertExact(gold, BigInteger.TEN, Actionable.SIMULATE));
        assertEquals(max.subtract(BigInteger.ONE), store.getAmountExact(gold));
        assertEquals(BigInteger.ONE, store.insertExact(gold, BigInteger.TEN, Actionable.MODULATE));
        assertEquals(BigInteger.ZERO, store.insertExact(gold, LONG_MAX, Actionable.MODULATE));
        assertEquals(BigInteger.TEN, store.extractExact(gold, BigInteger.TEN, Actionable.MODULATE));
        assertEquals(max.subtract(BigInteger.TEN), store.getAmountExact(gold));
    }

    @Test
    void simulationAndZeroRequestsLeavePersistedStateClean() {
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        store.insertExact(iron, BigInteger.TEN, Actionable.MODULATE);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var saved = store.persist(null, registries).copy();
        long version = store.getModCount();
        var before = store.snapshotExact();
        for (var key : new AEItemKey[] {iron, gold}) {
            assertEquals(LONG_MAX, store.insertExact(key, LONG_MAX, Actionable.SIMULATE));
            assertEquals(before.getOrDefault(key, BigInteger.ZERO),
                    store.extractExact(key, LONG_MAX, Actionable.SIMULATE));
            assertEquals(BigInteger.ZERO, store.insertExact(key, BigInteger.ZERO, Actionable.MODULATE));
            assertEquals(BigInteger.ZERO, store.extractExact(key, BigInteger.ZERO, Actionable.MODULATE));
        }
        assertEquals(version, store.getModCount());
        assertFalse(store.needsPersist());
        assertEquals(before, store.snapshotExact());
        assertEquals(saved, store.persist(null, registries));
    }

    @Test
    void randomSmallExactAndLegacyTransfersStayConservativeAcrossIncrementalReloads() {
        var keys = new AEKey[] {AEItemKey.of(Items.IRON_INGOT), AEItemKey.of(Items.GOLD_INGOT),
                AEItemKey.of(Items.DIAMOND), AEFluidKey.of(Fluids.WATER), AEFluidKey.of(Fluids.LAVA)};
        var ledger = new HashMap<AEKey, BigInteger>();
        var random = new Random(0x5A11E7AC7L);
        var store = new IndexedStorage();
        store.enableArbitraryPrecision();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        net.minecraft.nbt.CompoundTag root = null;
        // Every request fits long; even all 10,000 additions together remain far below 126 bits.
        // This exercises the new exact fast path throughout, without a wide request disabling it.
        for (int step = 0; step < 10_000; step++) {
            var key = keys[random.nextInt(keys.length)];
            long quantity = switch (random.nextInt(5)) {
                case 0 -> 0;
                case 1 -> Long.MAX_VALUE;
                case 2 -> Long.MAX_VALUE - 1;
                default -> 1 + random.nextInt(1_024);
            };
            var amount = BigInteger.valueOf(quantity);
            var before = ledger.getOrDefault(key, BigInteger.ZERO);
            boolean insert = random.nextBoolean();
            var mode = random.nextInt(4) == 0 ? Actionable.SIMULATE : Actionable.MODULATE;
            var expected = insert ? amount : before.min(amount);
            long version = store.getModCount();
            var transferred = random.nextInt(4) == 0
                    ? BigInteger.valueOf(insert ? store.insert(key, quantity, mode) : store.extract(key, quantity, mode))
                    : (insert ? store.insertExact(key, amount, mode) : store.extractExact(key, amount, mode));
            assertEquals(expected, transferred, "transfer " + step);
            if (mode == Actionable.MODULATE) {
                var after = insert ? before.add(expected) : before.subtract(expected);
                if (after.signum() == 0) ledger.remove(key); else ledger.put(key, after);
            } else {
                assertEquals(version, store.getModCount(), "simulation version " + step);
            }
            assertEquals(ledger.getOrDefault(key, BigInteger.ZERO), store.getAmountExact(key));
            if (step % 97 == 0) {
                root = store.persist(root, registries);
                var loaded = new IndexedStorage();
                loaded.load(root, registries);
                assertEquals(ledger, loaded.snapshotExact(), "reload " + step);
                store = loaded;
                var totals = new HashMap<AEKeyType, BigInteger>();
                ledger.forEach((k, n) -> totals.merge(k.getType(), n, BigInteger::add));
                for (var type : new AEKeyType[]{AEKeyType.items(), AEKeyType.fluids()}) {
                    var actual = BigInteger.valueOf(store.getTypeAmountHi().getLong(type)).shiftLeft(63)
                            .add(BigInteger.valueOf(store.getTypeAmountLo().getLong(type)));
                    assertEquals(totals.getOrDefault(type, BigInteger.ZERO), actual);
                }
            }
        }
        assertEquals(ledger, store.snapshotExact());
    }

    @Test
    void validationAndOptInAreRequiredForSmallRequests() {
        var store = new IndexedStorage();
        var key = AEItemKey.of(Items.IRON_INGOT);
        assertThrows(IllegalStateException.class, () -> store.insertExact(key, BigInteger.ONE, Actionable.MODULATE));
        assertThrows(IllegalStateException.class, () -> store.extractExact(key, BigInteger.ONE, Actionable.MODULATE));
        store.enableArbitraryPrecision();
        for (var invalid : new BigInteger[] {null, BigInteger.ONE.negate(), BigInteger.ONE.shiftLeft(BigAmounts.MAX_BITS)}) {
            assertThrows(IllegalArgumentException.class, () -> store.insertExact(key, invalid, Actionable.SIMULATE));
            assertThrows(IllegalArgumentException.class, () -> store.extractExact(key, invalid, Actionable.SIMULATE));
        }
        assertEquals(0, store.getTotalTypes());
        assertEquals(0, store.getModCount());
    }
}
