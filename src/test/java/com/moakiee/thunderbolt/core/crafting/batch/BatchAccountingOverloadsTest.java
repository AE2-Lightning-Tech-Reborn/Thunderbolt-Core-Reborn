package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BatchAccountingOverloadsTest {
    @Test
    void legacyQuadraticOverloadPreservesSignedIntegerBoundaries() {
        for (int ops : new int[] {Integer.MIN_VALUE, -1, 0, 1, 2, 100_000, Integer.MAX_VALUE}) {
            long expected = ops <= 0 ? 0 : (long) ops * ops;
            assertEquals(expected, BatchCpuAccounting.maxCopiesForCpuOps(ops));
            assertEquals(expected, BatchCpuAccounting.maxCopiesForCpuOps(ops, BatchCpuAccounting.Mode.QUADRATIC));
        }
    }

    @Test
    void nonpositiveBudgetsStillLimitAllAccountingModes() {
        for (var mode : BatchCpuAccounting.Mode.values()) {
            for (int ops : new int[] {Integer.MIN_VALUE, -1, 0}) {
                assertEquals(0, BatchCpuAccounting.maxCopiesForCpuOps(ops, mode));
                assertEquals(0, BatchCpuAccounting.maxCopiesForBatch(ops, Integer.MAX_VALUE, Long.MAX_VALUE, mode));
            }
        }
    }
}
