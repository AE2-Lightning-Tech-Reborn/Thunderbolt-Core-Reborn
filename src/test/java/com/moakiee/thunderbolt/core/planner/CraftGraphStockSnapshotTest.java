package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CraftGraphStockSnapshotTest {
    private static final int KEYS = 2048;

    @Test
    void clusteredStockHashesKeepLookupWorkBoundedAcrossInventoryProjections() {
        var probes = new Probes();
        var builder = CraftGraph.<Key>builder();
        var adjustment = new HashMap<Key, Long>();
        for (int i = 0; i < KEYS; i++) {
            builder.stock(new Key(i, probes), 10);
            adjustment.put(new Key(i, probes), 1L);
        }
        var original = builder.build();
        var added = original.withAdditionalStock(adjustment);
        var limited = original.withStockLimits(adjustment);
        var remaining = original.withoutStock(adjustment);
        var projected = original.withPatterns(Map.of());
        builder.stock(new Key(0, probes), 100);
        adjustment.clear();

        var graphs = List.of(original, added, limited, remaining, projected);
        long[] amounts = {10, 11, 1, 9, 10};
        for (int index = 0; index < graphs.size(); index++) {
            var graph = graphs.get(index);
            long amount = amounts[index];
            probes.equalsCalls = 0;
            for (int i = 0; i < KEYS; i++) {
                var present = new Key(i, probes);
                var absent = new Key(i + KEYS, probes);
                assertEquals(amount, graph.stock(present));
                assertEquals(BigInteger.valueOf(amount), graph.exactStock(present));
                assertEquals(0, graph.stock(absent));
                assertEquals(BigInteger.ZERO, graph.exactStock(absent));
            }
            assertTrue(probes.equalsCalls <= 16 * KEYS,
                    "stock lookups scanned clusters: " + probes.equalsCalls);
        }
    }

    @Test
    void largeSnapshotsPreserveExactStockAndSaturatedProjections() {
        var builder = CraftGraph.<Integer>builder();
        for (int i = 0; i < 32; i++) builder.stock(i, i + 1);
        var wide = BigInteger.ONE.shiftLeft(100);
        builder.stockExact(0, wide);
        var graph = builder.build();
        var added = graph.withAdditionalStock(Map.of(0, 7L));
        var remaining = graph.withoutStock(Map.of(0, 3L));
        var limits = new HashMap<Integer, Long>();
        for (int i = 0; i < 32; i++) limits.put(i, 1L);
        var limited = graph.withStockLimits(limits);

        assertEquals(wide.add(BigInteger.ONE), graph.exactStock(0));
        assertEquals(Sat.SAT, graph.stock(0));
        assertEquals(wide.add(BigInteger.valueOf(8)), added.exactStock(0));
        assertEquals(Sat.SAT, added.stock(0));
        assertEquals(wide.subtract(BigInteger.TWO), remaining.exactStock(0));
        assertEquals(Sat.SAT - 3, remaining.stock(0));
        assertEquals(BigInteger.ONE, limited.exactStock(0));
        assertEquals(1, limited.stock(0));
    }

    @Test
    void snapshotsStillRejectNullInventoryKeysAtBothSizes() {
        for (int size : new int[] {1, 32}) {
            var builder = CraftGraph.<Integer>builder();
            for (int i = 0; i < size; i++) builder.stock(i, 1);
            var graph = builder.build();
            assertThrows(NullPointerException.class, () -> graph.stock(null));
            assertThrows(NullPointerException.class, () -> graph.exactStock(null));
            builder.stock(null, 1);
            assertThrows(NullPointerException.class, builder::build);
        }
    }

    private static final class Probes {
        int equalsCalls;
    }

    /** Distinct hashes share MapN's initial bucket, while a spread hash table separates them. */
    private record Key(int id, Probes probes) {
        @Override public int hashCode() { return id * (2 * KEYS); }
        @Override public boolean equals(Object other) {
            probes.equalsCalls++;
            return other instanceof Key key && id == key.id;
        }
    }
}
