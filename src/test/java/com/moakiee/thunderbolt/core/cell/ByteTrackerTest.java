package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Locks the incremental remainder-delta bookkeeping of {@link ByteTracker} against the plain
 * formula {@code usedBytes = uniqueKeys x bytesPerType + sum(ceil(typeTotal / apb))}, and the
 * per-type entry cleanup when a type's last key is removed.
 */
class ByteTrackerTest {

    private static final int BYTES_PER_TYPE = 8;

    /** Minimal key type with a fixed amount-per-byte; never touches game registries. */
    private static final class TestKeyType extends AEKeyType {
        private final int apb;

        TestKeyType(String id, int apb) {
            super(new ResourceLocation("thunderbolt_test", id),
                    AEItemKey.class, Component.literal(id));
            this.apb = apb;
        }

        @Override
        public int getAmountPerByte() {
            return apb;
        }

        @Override
        public AEKey loadKeyFromTag(CompoundTag tag) {
            throw new UnsupportedOperationException("not used by ByteTracker");
        }

        @Override
        public AEKey readFromPacket(FriendlyByteBuf input) {
            throw new UnsupportedOperationException("not used by ByteTracker");
        }
    }

    /** Naive reference model recomputing used bytes from scratch after every operation. */
    private static final class ReferenceModel {
        final Map<AEKeyType, Long> totals = new HashMap<>();
        final Map<AEKeyType, Integer> keys = new HashMap<>();

        void insert(AEKeyType type, long amount, boolean newKey) {
            totals.merge(type, amount, Long::sum);
            if (newKey) keys.merge(type, 1, Integer::sum);
        }

        void extract(AEKeyType type, long amount, boolean keyRemoved) {
            totals.merge(type, -amount, Long::sum);
            if (totals.get(type) == 0) totals.remove(type);
            if (keyRemoved) {
                keys.merge(type, -1, Integer::sum);
                if (keys.get(type) == 0) keys.remove(type);
            }
        }

        long usedBytes() {
            long used = 0;
            for (var entry : totals.entrySet()) {
                int apb = entry.getKey().getAmountPerByte();
                used += (entry.getValue() + apb - 1) / apb;
            }
            long uniqueKeys = keys.values().stream().mapToInt(Integer::intValue).sum();
            return used + uniqueKeys * BYTES_PER_TYPE;
        }

        int uniqueKeys() {
            return keys.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static ByteTracker configuredTracker(ReferenceModel model) {
        var tracker = new ByteTracker(model::uniqueKeys);
        tracker.configure(BYTES_PER_TYPE, 63, Long.MAX_VALUE, 0);
        return tracker;
    }

    @Test
    void fullByteStillAcceptsTheUnusedUnitsInItsLastByte() {
        var tracker = new ByteTracker(() -> 1);
        var items = new TestKeyType("partial", 8);
        tracker.configure(0, 1, 1, 0);
        tracker.onInsert(items, 1, true);

        assertEquals(7, tracker.computeMaxInsertable(items, false));
        assertEquals(0, tracker.computeMaxInsertable(items, true));
    }

    @Test
    void capacityAboveOneLimbStillEnforcesTheTypeLimit() {
        var tracker = new ByteTracker(() -> 1);
        var items = new TestKeyType("type_limit", 8);
        tracker.configure(8, 1, 0, 1);
        tracker.onInsert(items, 1, true);

        assertEquals(0, tracker.computeMaxInsertable(items, true));
        assertEquals(Long.MAX_VALUE, tracker.computeMaxInsertable(items, false));
    }

    @Test
    void adjacentLimbsCanHaveOnlyAFewFreeBytes() {
        var tracker = new ByteTracker(() -> 1);
        var items = new TestKeyType("borrow", 1);
        tracker.configure(0, 2, 5, 1);
        tracker.onInsert(items, Long.MAX_VALUE - 2, true);

        assertEquals(8, tracker.computeMaxInsertable(items, false));
    }

    @Test
    void largeInsertionIncludesBothTheAmountAndMetadataCarries() {
        var tracker = new ByteTracker(() -> 2);
        var items = new TestKeyType("insert_carry", 1);
        tracker.configure(8, 3, 0, 4);
        tracker.onInsert(items, Long.MAX_VALUE - 10, true);
        tracker.onInsert(items, Long.MAX_VALUE, true);

        assertEquals(2, tracker.getUsedBytesHi());
        assertEquals(4, tracker.getUsedBytesLo());
    }

    @Test
    void largeExtractionIncludesBothTheAmountAndMetadataBorrows() {
        var tracker = new ByteTracker(() -> 1);
        var items = new TestKeyType("extract_borrow", 1);
        tracker.configure(8, 3, 0, 4);
        var lo = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<AEKeyType>();
        var hi = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<AEKeyType>();
        var counts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<AEKeyType>();
        lo.put(items, Long.MAX_VALUE - 11);
        hi.put(items, 1);
        counts.put(items, 2);
        tracker.rebuild(lo, hi, counts, 2);
        tracker.onExtract(items, Long.MAX_VALUE, true);

        assertEquals(0, tracker.getUsedBytesHi());
        assertEquals(Long.MAX_VALUE - 2, tracker.getUsedBytesLo());
    }

    @Test
    void insertionLimitsMatchIndependentBigIntegerCapacityFormula() {
        var limb = BigInteger.ONE.shiftLeft(63);
        var mask = limb.subtract(BigInteger.ONE);
        for (int apb : new int[] {1, 8, 8000}) {
            var type = new TestKeyType("oracle_" + apb, apb);
            for (int metadata : new int[] {0, 8}) {
                for (int keys : new int[] {1, 2}) {
                    BigInteger[] amounts = {BigInteger.valueOf(keys), BigInteger.valueOf(8001),
                            mask.subtract(BigInteger.TWO), mask, limb, limb.add(BigInteger.ONE),
                            limb.multiply(BigInteger.valueOf(apb)).subtract(BigInteger.ONE)};
                    for (var amount : amounts) {
                        var used = amount.add(BigInteger.valueOf(apb - 1)).divide(BigInteger.valueOf(apb))
                                .add(BigInteger.valueOf((long) keys * metadata));
                        BigInteger[] capacities = {BigInteger.ZERO, used.subtract(BigInteger.ONE), used,
                                used.add(BigInteger.ONE), used.add(BigInteger.valueOf(8)),
                                mask, limb, limb.add(BigInteger.valueOf(5)), limb.shiftLeft(1)};
                        for (var capacity : capacities) {
                            if (capacity.signum() < 0) continue;
                            for (int limit : new int[] {keys, keys + 1}) {
                                var tracker = new ByteTracker(() -> keys);
                                tracker.configure(metadata, limit,
                                        capacity.and(mask).longValueExact(), capacity.shiftRight(63).longValueExact());
                                var lo = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<AEKeyType>();
                                var hi = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<AEKeyType>();
                                var counts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<AEKeyType>();
                                lo.put(type, amount.and(mask).longValueExact());
                                hi.put(type, amount.shiftRight(63).longValueExact());
                                counts.put(type, keys);
                                tracker.rebuild(lo, hi, counts, keys);
                                for (boolean newKey : new boolean[] {false, true}) {
                                    var availableBytes = capacity.subtract(BigInteger.valueOf(
                                            (long) (keys + (newKey ? 1 : 0)) * metadata));
                                    long expected = newKey && keys >= limit ? 0 : availableBytes
                                            .multiply(BigInteger.valueOf(apb)).subtract(amount)
                                            .max(BigInteger.ZERO).min(mask).longValueExact();
                                    assertEquals(expected, tracker.computeMaxInsertable(type, newKey),
                                            "apb=" + apb + ", metadata=" + metadata + ", keys=" + keys
                                                    + ", amount=" + amount + ", capacity=" + capacity
                                                    + ", limit=" + limit + ", newKey=" + newKey);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void removingTheLastKeyOfATypeDropsItsBookkeepingEntries() {
        var model = new ReferenceModel();
        var tracker = configuredTracker(model);
        var items = new TestKeyType("items", 8);
        var fluids = new TestKeyType("fluids", 8000);

        tracker.onInsert(items, 100, true);
        tracker.onInsert(fluids, 24_000, true);
        assertEquals(4, tracker.trackedTypeEntries());

        tracker.onExtract(fluids, 24_000, true);
        assertEquals(2, tracker.trackedTypeEntries(),
                "removing the last fluid key must drop both per-type entries");

        tracker.onExtract(items, 100, true);
        assertEquals(0, tracker.trackedTypeEntries());
        assertEquals(0, tracker.getUsedBytes());
    }

    @Test
    void interleavedKeysOfOneTypeKeepEntriesUntilTheLastKeyLeaves() {
        var model = new ReferenceModel();
        var tracker = configuredTracker(model);
        var items = new TestKeyType("items", 8);

        tracker.onInsert(items, 3, true);
        tracker.onInsert(items, 5, true);
        tracker.onExtract(items, 3, true);
        assertEquals(2, tracker.trackedTypeEntries(),
                "a remaining key of the type must keep its entries");
        tracker.onExtract(items, 5, true);
        assertEquals(0, tracker.trackedTypeEntries());
        assertEquals(0, tracker.getUsedBytes());
    }

    @Test
    void randomizedSequencesMatchTheClosedFormFormula() {
        var random = new Random(0x7B0);
        var types = new TestKeyType[] {
                new TestKeyType("a", 1),
                new TestKeyType("b", 8),
                new TestKeyType("c", 8000),
        };

        for (int round = 0; round < 200; round++) {
            var model = new ReferenceModel();
            var tracker = configuredTracker(model);
            // Track live per-type key amounts so extracts stay physically consistent.
            List<Map.Entry<AEKeyType, Long>> liveKeys = new ArrayList<>();

            for (int step = 0; step < 120; step++) {
                boolean insertNew = liveKeys.isEmpty() || random.nextInt(4) == 0;
                if (insertNew) {
                    var type = types[random.nextInt(types.length)];
                    long amount = 1 + random.nextLong(20_000);
                    tracker.onInsert(type, amount, true);
                    model.insert(type, amount, true);
                    liveKeys.add(Map.entry(type, amount));
                } else if (random.nextBoolean()) {
                    int pick = random.nextInt(liveKeys.size());
                    var entry = liveKeys.get(pick);
                    long amount = 1 + random.nextLong(20_000);
                    tracker.onInsert(entry.getKey(), amount, false);
                    model.insert(entry.getKey(), amount, false);
                    liveKeys.set(pick, Map.entry(entry.getKey(), entry.getValue() + amount));
                } else {
                    int pick = random.nextInt(liveKeys.size());
                    var entry = liveKeys.get(pick);
                    boolean removeKey = entry.getValue() == 1 || random.nextBoolean();
                    // Partial extracts leave at least one unit so live keys never hit zero.
                    long amount = removeKey
                            ? entry.getValue()
                            : 1 + random.nextLong(entry.getValue() - 1);
                    tracker.onExtract(entry.getKey(), amount, removeKey);
                    model.extract(entry.getKey(), amount, removeKey);
                    if (removeKey) {
                        liveKeys.remove(pick);
                    } else {
                        liveKeys.set(pick, Map.entry(entry.getKey(), entry.getValue() - amount));
                    }
                }
                assertEquals(model.usedBytes(), tracker.getUsedBytes(),
                        "incremental byte accounting diverged from the closed form");
            }
        }
    }
}
