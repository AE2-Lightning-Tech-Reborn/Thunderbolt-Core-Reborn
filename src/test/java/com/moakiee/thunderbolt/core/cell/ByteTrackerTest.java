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
    void theLastAllocatedByteCanStillAcceptItsRemainingUnits() {
        var type = new TestKeyType("partial", 8);
        var tracker = new ByteTracker(() -> 1);
        tracker.configure(8, 63, 9, 0);
        tracker.onInsert(type, 1, true);

        assertEquals(9, tracker.getUsedBytes());
        assertEquals(7, tracker.computeMaxInsertable(type, false));
        tracker.onInsert(type, 7, false);
        assertEquals(9, tracker.getUsedBytes());
        assertEquals(0, tracker.computeMaxInsertable(type, false));
    }

    @Test
    void aWideCellStillEnforcesTheTypeLimit() {
        var type = new TestKeyType("wide_types", 8);
        var tracker = new ByteTracker(() -> 1);
        tracker.configure(8, 1, 0, 1);
        tracker.onInsert(type, 1, true);

        assertEquals(0, tracker.computeMaxInsertable(type, true));
        assertEquals(Long.MAX_VALUE, tracker.computeMaxInsertable(type, false));
    }

    @Test
    void aFreeByteDifferenceCanBorrowAcrossThe63BitBoundary() {
        var type = new TestKeyType("borrow", 1);
        var tracker = new ByteTracker(() -> 1);
        tracker.configure(8, 63, 5, 1);
        tracker.onInsert(type, Long.MAX_VALUE - 10, true);

        assertEquals(Long.MAX_VALUE - 2, tracker.getUsedBytesLo());
        assertEquals(8, tracker.computeMaxInsertable(type, false));
        assertEquals(0, tracker.computeMaxInsertable(type, true));
    }

    @Test
    void maximumTransfersAndTypeOverheadCarryAndBorrowIndependently() {
        var type = new TestKeyType("maximum", 1);
        var tracker = new ByteTracker(() -> 2);
        tracker.configure(8, 63, Long.MAX_VALUE, Long.MAX_VALUE);
        tracker.onInsert(type, Long.MAX_VALUE - 8, true);
        tracker.onInsert(type, Long.MAX_VALUE, true);

        assertEquals(2, tracker.getUsedBytesHi());
        assertEquals(6, tracker.getUsedBytesLo());
        tracker.onExtract(type, Long.MAX_VALUE, true);
        assertEquals(0, tracker.getUsedBytesHi());
        assertEquals(Long.MAX_VALUE, tracker.getUsedBytesLo());
        tracker.onExtract(type, Long.MAX_VALUE - 8, true);
        assertEquals(0, tracker.getUsedBytes());
    }

    @Test
    void capacityChecksMatchAnExactOracleAcrossWideAndPartialByteBoundaries() {
        var random = new Random(0xB17E);
        var maximum = java.math.BigInteger.valueOf(Long.MAX_VALUE);
        var mask = maximum;
        var gaps = new java.math.BigInteger[] {
                java.math.BigInteger.valueOf(-1), java.math.BigInteger.ZERO,
                java.math.BigInteger.ONE, java.math.BigInteger.valueOf(8),
                maximum, maximum.add(java.math.BigInteger.ONE)
        };
        for (int run = 0; run < 2000; run++) {
            int apb = new int[] {1, 8, 8000}[random.nextInt(3)];
            var type = new TestKeyType("oracle", apb);
            var amount = new java.math.BigInteger(70, random).add(java.math.BigInteger.ONE);
            var divisor = java.math.BigInteger.valueOf(apb);
            var used = amount.add(divisor).subtract(java.math.BigInteger.ONE).divide(divisor)
                    .add(java.math.BigInteger.valueOf(BYTES_PER_TYPE));
            var capacity = used.add(gaps[random.nextInt(gaps.length)]);
            boolean newKey = random.nextBoolean();
            int maxTypes = random.nextBoolean() ? 1 : 63;
            var tracker = new ByteTracker(() -> 1);
            tracker.configure(BYTES_PER_TYPE, maxTypes,
                    capacity.and(mask).longValueExact(), capacity.shiftRight(63).longValueExact());
            var low = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<AEKeyType>();
            var high = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<AEKeyType>();
            var counts = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<AEKeyType>();
            low.put(type, amount.and(mask).longValueExact());
            high.put(type, amount.shiftRight(63).longValueExact());
            counts.put(type, 1);
            tracker.rebuild(low, high, counts, 1);

            var free = capacity.subtract(used)
                    .subtract(java.math.BigInteger.valueOf(newKey ? BYTES_PER_TYPE : 0));
            long partial = amount.mod(divisor).signum() == 0 ? 0
                    : apb - amount.mod(divisor).longValueExact();
            long expected = (newKey && maxTypes == 1) || free.signum() < 0 ? 0
                    : free.multiply(divisor).add(java.math.BigInteger.valueOf(partial))
                            .min(maximum).longValueExact();
            assertEquals(expected, tracker.computeMaxInsertable(type, newKey), "oracle case " + run);
        }
    }

    @Test
    void highWordCapacityStillHonorsTypeLimit() {
        var type = new TestKeyType("quota", 8);
        var tracker = new ByteTracker(() -> 63);
        tracker.configure(8, 63, 0, 2);
        assertEquals(0, tracker.computeMaxInsertable(type, true));
        assertEquals(Long.MAX_VALUE, tracker.computeMaxInsertable(type, false));
    }

    @Test
    void lowWordBorrowDoesNotTurnOneFreeByteIntoUnlimitedCapacity() {
        var type = new TestKeyType("borrow", 1);
        var tracker = new ByteTracker(() -> 1);
        tracker.configure(0, 63, 0, 1);
        tracker.onInsert(type, Long.MAX_VALUE, true);
        assertEquals(1, tracker.computeMaxInsertable(type, false));
        tracker.configure(8, 63, 0, 1);
        assertEquals(0, tracker.computeMaxInsertable(type, true));
    }

    @Test
    void longSizedInsertAndRemovalPreserveBothCarriesIncludingTypeOverhead() {
        var type = new TestKeyType("two_carries", 1);
        var tracker = new ByteTracker(() -> 2);
        tracker.configure(8, 63, 0, 10);
        long first = Long.MAX_VALUE - 10;
        tracker.onInsert(type, first, true);
        tracker.onInsert(type, Long.MAX_VALUE, true);
        var expected = BigInteger.valueOf(first).add(BigInteger.valueOf(Long.MAX_VALUE)).add(BigInteger.valueOf(16));
        assertEquals(expected, BigInteger.valueOf(tracker.getUsedBytesHi()).shiftLeft(63)
                .add(BigInteger.valueOf(tracker.getUsedBytesLo())));
        tracker.onExtract(type, Long.MAX_VALUE, true);
        assertEquals(0, tracker.getUsedBytesHi());
        assertEquals(first + 8, tracker.getUsedBytesLo());
        tracker.onExtract(type, first, true);
        assertEquals(0, tracker.getUsedBytesHi());
        assertEquals(0, tracker.getUsedBytesLo());
    }

    @Test
    void existingKeyCanFillTheAlreadyChargedPartialByte() {
        var type = new TestKeyType("partial", 8);
        var tracker = new ByteTracker(() -> 1);
        tracker.configure(8, 1, 9, 0);
        tracker.onInsert(type, 1, true);
        assertEquals(7, tracker.computeMaxInsertable(type, false));
        assertEquals(0, tracker.computeMaxInsertable(type, true));
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
