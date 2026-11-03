package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CraftGraphStockSnapshotTest {
    @Test void missingStockLookupDoesNotScanEveryCollidingInventoryEntry() {
        var builder = CraftGraph.<CollisionKey>builder();
        for (int i = 0; i < 512; i++) builder.stock(new CollisionKey(i), i + 1L);
        var graph = builder.build();

        CollisionKey.comparisons = 0;
        assertEquals(0L, graph.stock(new CollisionKey(512)));
        assertTrue(CollisionKey.comparisons < 64,
                () -> "absent stock scanned " + CollisionKey.comparisons + " colliding entries");
        assertEquals(258L, graph.stock(new CollisionKey(257)));
        assertEquals(BigInteger.valueOf(258), graph.exactStock(new CollisionKey(257)));
    }

    @Test void builderAndStockProjectionsOwnIndependentSnapshotsIncludingExactAmounts() {
        var builder = CraftGraph.<String>builder();
        var limits = new HashMap<String, Long>();
        for (int i = 0; i < 512; i++) {
            builder.stock("R" + i, i + 1L);
            limits.put("R" + i, 1L);
        }
        var huge = BigInteger.valueOf(Sat.SAT).multiply(BigInteger.TWO);
        var graph = builder.stockExact("huge", huge).build();
        builder.stock("R0", 99).stock("later", 1);
        var additional = new HashMap<>(Map.of("R0", 5L, "huge", 7L));
        var supplied = graph.withAdditionalStock(additional);
        var limited = graph.withStockLimits(limits);
        var withdrawn = graph.withoutStock(Map.of("R0", 1L));
        limits.clear();
        additional.clear();

        assertEquals(1L, graph.stock("R0"));
        assertEquals(0L, graph.stock("later"));
        assertEquals(huge, graph.exactStock("huge"));
        assertEquals(6L, supplied.stock("R0"));
        assertEquals(huge.add(BigInteger.valueOf(7)), supplied.exactStock("huge"));
        assertEquals(1L, limited.stock("R511"));
        assertEquals(BigInteger.ONE, limited.exactStock("R511"));
        assertEquals(0L, withdrawn.stock("R0"));
        assertEquals(huge, withdrawn.exactStock("huge"));
    }

    @Test void largeSnapshotsStillRejectNullKeys() {
        var builder = CraftGraph.<String>builder();
        for (int i = 0; i < 16; i++) builder.stock("R" + i, 1);
        var graph = builder.build();
        assertThrows(NullPointerException.class, () -> graph.stock(null));
        assertThrows(NullPointerException.class, () -> graph.exactStock(null));
        builder.stock(null, 1);
        assertThrows(NullPointerException.class, builder::build);
    }

    private record CollisionKey(int value) implements Comparable<CollisionKey> {
        static int comparisons;
        @Override public int hashCode() { return 1; }
        @Override public boolean equals(Object other) {
            comparisons++;
            return other instanceof CollisionKey key && value == key.value;
        }
        @Override public int compareTo(CollisionKey other) { return Integer.compare(value, other.value); }
    }
}
