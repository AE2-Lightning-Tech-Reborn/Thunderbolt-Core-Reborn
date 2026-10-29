package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ByteTrackerDeltaOverflowTest {
    private static final BigInteger MASK = BigInteger.valueOf(Long.MAX_VALUE);

    private static final class Type extends AEKeyType {
        final int units;
        Type(int units) {
            super(ResourceLocation.parse("test:delta_" + units), AEItemKey.class, Component.literal("delta"));
            this.units = units;
        }
        public int getAmountPerByte() { return units; }
        public MapCodec<? extends AEKey> codec() { throw new UnsupportedOperationException(); }
        public AEKey readFromPacket(RegistryFriendlyByteBuf input) { throw new UnsupportedOperationException(); }
    }

    private static ByteTracker tracker(int charge) {
        var tracker = new ByteTracker(() -> 0);
        tracker.configure(charge, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        return tracker;
    }

    private static void assertBytes(BigInteger expected, ByteTracker tracker) {
        assertEquals(expected.shiftRight(63).longValueExact(), tracker.getUsedBytesHi());
        assertEquals(expected.and(MASK).longValueExact(), tracker.getUsedBytesLo());
        assertEquals(expected.min(MASK).longValueExact(), tracker.getUsedBytes());
    }

    @Test
    void keyChargeAndExistingLowWordCanEachCarryDuringTheSameInsertion() {
        for (int charge : new int[] {1, 8, Integer.MAX_VALUE}) {
            var tracker = tracker(charge);
            var type = new Type(1);
            tracker.onInsert(type, Long.MAX_VALUE - charge, true);
            assertBytes(MASK, tracker);
            tracker.onInsert(type, Long.MAX_VALUE, true);
            assertBytes(MASK.shiftLeft(1).add(BigInteger.valueOf(charge)), tracker);
        }
    }

    @Test
    void removingAKeyWhoseByteChargeExceedsLongMaxBorrowsBothWords() {
        for (int charge : new int[] {1, 8, Integer.MAX_VALUE}) {
            var tracker = tracker(charge);
            var type = new Type(1);
            var lo = new Object2LongOpenHashMap<AEKeyType>();
            var hi = new Object2LongOpenHashMap<AEKeyType>();
            var counts = new Object2IntOpenHashMap<AEKeyType>();
            var amount = MASK.shiftLeft(1).subtract(BigInteger.valueOf(charge));
            lo.put(type, amount.and(MASK).longValueExact());
            hi.put(type, amount.shiftRight(63).longValueExact());
            counts.put(type, 2);
            tracker.rebuild(lo, hi, counts, 2);
            tracker.onExtract(type, Long.MAX_VALUE, true);
            assertBytes(MASK, tracker);
            tracker.onExtract(type, Long.MAX_VALUE - charge, true);
            assertBytes(BigInteger.ZERO, tracker);
            assertEquals(0, tracker.trackedTypeEntries());
        }
    }

    @Test
    void mixedNearLongMaxDeltasMatchIndependentWideArithmetic() {
        var types = new Type[] {new Type(1), new Type(2), new Type(8), new Type(17), new Type(8000)};
        for (int charge : new int[] {0, 8, Integer.MAX_VALUE}) {
            var tracker = tracker(charge);
            var random = new Random(0xCA22126L);
            var keys = new ArrayList<Map.Entry<Type, Long>>();
            for (int step = 0; step < 256; step++) {
                if (keys.isEmpty() || random.nextBoolean()) {
                    var type = types[random.nextInt(types.length)];
                    long amount = random.nextBoolean() ? Long.MAX_VALUE - random.nextInt(32) : 1 + random.nextInt(32);
                    keys.add(Map.entry(type, amount));
                    tracker.onInsert(type, amount, true);
                } else {
                    var removed = keys.remove(random.nextInt(keys.size()));
                    tracker.onExtract(removed.getKey(), removed.getValue(), true);
                }
                var totals = new HashMap<Type, BigInteger>();
                for (var key : keys) totals.merge(key.getKey(), BigInteger.valueOf(key.getValue()), BigInteger::add);
                var expected = BigInteger.valueOf((long) keys.size() * charge);
                for (var entry : totals.entrySet()) {
                    var divisor = BigInteger.valueOf(entry.getKey().units);
                    expected = expected.add(entry.getValue().add(divisor).subtract(BigInteger.ONE).divide(divisor));
                }
                assertBytes(expected, tracker);
            }
        }
    }
}
