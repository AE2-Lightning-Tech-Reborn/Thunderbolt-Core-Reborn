package com.moakiee.thunderbolt.core.crafting.big;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BigLongSliceTest {
    private static final BigInteger HUGE = BigInteger.TEN.pow(100);

    @Test void limitsBothInputAndOutputTransfersWithoutTruncatingTheOrder() {
        var slice = BigLongSlice.plan(HUGE, Map.of("raw", HUGE),
                Map.of("raw", 2L), Map.of(), Map.of("out", 7L), Map.of(), Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE / 7, slice.copies());
        assertEquals(BigInteger.valueOf(slice.copies()).multiply(BigInteger.TWO), slice.inputs().get("raw"));
        assertEquals(BigInteger.valueOf(slice.copies()).multiply(BigInteger.valueOf(7)), slice.outputs().get("out"));
        assertTrue(HUGE.subtract(BigInteger.valueOf(slice.copies())).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
    }

    @Test void singleCopyProviderAndPhysicalStockLimitDispatch() {
        var slice = BigLongSlice.plan(HUGE, Map.of("raw", BigInteger.valueOf(7)),
                Map.of("raw", 3L), Map.of(), Map.of("out", 1L), Map.of(), Long.MAX_VALUE);
        assertEquals(2, slice.copies());
        assertEquals(1, BigLongSlice.plan(HUGE, Map.of("raw", HUGE), Map.of("raw", 3L),
                Map.of(), Map.of("out", 1L), Map.of(), 1).copies());
    }

    @Test void sharedSeedIsSuppliedAndReturnedOnce() {
        var slice = BigLongSlice.plan(HUGE, Map.of("raw", HUGE, "tool", BigInteger.ONE),
                Map.of("raw", 1L, "tool", 1L), Map.of("tool", 1L),
                Map.of("out", 1L, "tool", 1L), Map.of("tool", 1L), 1000);
        assertEquals(1000, slice.copies());
        assertEquals(BigInteger.ONE, slice.inputs().get("tool"));
        assertEquals(BigInteger.ONE, slice.outputs().get("tool"));
        assertEquals(1, BigLongSlice.plan(HUGE, Map.of("raw", HUGE, "tool", BigInteger.ONE),
                Map.of("raw", 1L, "tool", 1L), Map.of(), Map.of("out", 1L), Map.of(), 1000).copies());
    }

    @Test void rejectionOwnsNoSharedSeedAndAbsentSeedCannotDispatch() {
        assertTrue(BigLongSlice.of(0, Map.of("tool", 1L), Map.of("tool", 1L),
                Map.of("out", 1L), Map.of()).inputs().isEmpty());
        assertEquals(0, BigLongSlice.plan(HUGE, Map.of(), Map.of("tool", 1L), Map.of("tool", 1L),
                Map.of("out", 1L), Map.of(), 1000).copies());
    }

    @Test void overlappingFixedAndScaledKeyRemainsInLongRange() {
        var slice = BigLongSlice.plan(HUGE, Map.of("raw", HUGE), Map.of("raw", 2L), Map.of("raw", 1L),
                Map.of("out", 1L), Map.of(), Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE - 1, slice.copies());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), slice.inputs().get("raw"));
        assertThrows(ArithmeticException.class, () -> BigLongSlice.of(Long.MAX_VALUE,
                Map.of("raw", 2L), Map.of(), Map.of(), Map.of()));
    }
}
