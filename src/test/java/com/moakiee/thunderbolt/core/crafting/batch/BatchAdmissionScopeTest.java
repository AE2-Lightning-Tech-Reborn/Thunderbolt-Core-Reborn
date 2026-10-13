package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.inv.ListCraftingInventory;
import org.junit.jupiter.api.Test;

class BatchAdmissionScopeTest {
    @Test
    void nestedExtractionCannotBorrowAnotherTaskOrCpuAdmissionAndAlwaysRestoresScope() {
        var pattern = pattern();
        var otherPattern = pattern();
        var stock = new ListCraftingInventory(key -> {});
        var otherStock = new ListCraftingInventory(key -> {});
        ParallelBatchCpuHelper.BatchCapacityLimiter outer = (prototype, copies) -> 64;
        ParallelBatchCpuHelper.BatchCapacityLimiter inner = (prototype, copies) -> 2;
        ParallelBatchCpuHelper.withBatchCapacityLimiter(pattern, stock, outer, () -> {
            assertSame(outer, ParallelBatchCpuHelper.currentBatchCapacityLimiter(pattern, stock));
            assertNull(ParallelBatchCpuHelper.currentBatchCapacityLimiter(otherPattern, stock));
            assertNull(ParallelBatchCpuHelper.currentBatchCapacityLimiter(pattern, otherStock));
            assertThrows(IllegalStateException.class, () ->
                    ParallelBatchCpuHelper.withBatchCapacityLimiter(otherPattern, otherStock, inner, () -> {
                        assertSame(inner, ParallelBatchCpuHelper.currentBatchCapacityLimiter(otherPattern, otherStock));
                        assertNull(ParallelBatchCpuHelper.currentBatchCapacityLimiter(pattern, stock));
                        throw new IllegalStateException("nested extraction failed");
                    }));
            assertSame(outer, ParallelBatchCpuHelper.currentBatchCapacityLimiter(pattern, stock));
            return null;
        });
        assertNull(ParallelBatchCpuHelper.currentBatchCapacityLimiter(pattern, stock));
        assertNull(ParallelBatchCpuHelper.currentBatchCapacityLimiter(otherPattern, otherStock));
    }

    private static IPatternDetails pattern() {
        return (IPatternDetails) Proxy.newProxyInstance(BatchAdmissionScopeTest.class.getClassLoader(),
                new Class<?>[]{IPatternDetails.class}, (proxy, method, args) -> null);
    }
}
