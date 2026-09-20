package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CpSatDeadlineTest {
    @Test void finiteBudgetStaysFiniteWithANegativeNanoTimeOrigin() {
        assertEquals(-7_000_000_000L, CpSatBridge.deadlineNanos(3.0D, -10_000_000_000L));
        assertEquals(Long.MIN_VALUE + 3_000_000_000L,
                CpSatBridge.deadlineNanos(3.0D, Long.MIN_VALUE));
    }

    @Test void positiveOverflowSaturatesAndEmptyBudgetsExpireImmediately() {
        assertEquals(Long.MAX_VALUE, CpSatBridge.deadlineNanos(3.0D, Long.MAX_VALUE - 1L));
        assertEquals(17L, CpSatBridge.deadlineNanos(0.0D, 17L));
        assertEquals(-17L, CpSatBridge.deadlineNanos(Double.NaN, -17L));
        assertEquals(Long.MAX_VALUE, CpSatBridge.deadlineNanos(Double.POSITIVE_INFINITY, -17L));
    }
}
