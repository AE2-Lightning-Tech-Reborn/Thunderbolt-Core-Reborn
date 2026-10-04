package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PairBatchAllocationTest {
    @Test
    void mixedBatchesReachTheIntegerExecutionMinimum() {
        var result = PairBatchAllocation.solve(8, 2, 3, new long[] {4}, new long[] {6}, new long[] {16}, 4, () -> true);
        assertEquals(1, result.first());
        assertEquals(2, result.second());
        assertEquals(BigInteger.valueOf(16), result.consumed());
    }

    @Test
    void independentEnumerationAgreesAcrossMultipleResourceLimitsAndEmptyInputs() {
        var random = new Random(300926);
        for (int sample = 0; sample < 6000; sample++) {
            int dimensions = random.nextInt(4), demand = 1 + random.nextInt(50), executions = 1 + random.nextInt(15);
            long a = 1 + random.nextInt(12), b = 1 + random.nextInt(12);
            long[] ca = new long[dimensions], cb = new long[dimensions], capacity = new long[dimensions];
            for (int i = 0; i < dimensions; i++) {
                ca[i] = random.nextInt(10); cb[i] = random.nextInt(10); capacity[i] = random.nextInt(60);
            }
            long bestExecutions = Long.MAX_VALUE, bestStock = Long.MAX_VALUE;
            for (int x = 0; x <= executions; x++) for (int y = 0; y <= executions - x; y++) {
                if (a*x+b*y < demand) continue;
                boolean feasible = true; long stock = 0;
                for (int i = 0; i < dimensions; i++) {
                    long consumed = ca[i]*x+cb[i]*y;
                    feasible &= consumed <= capacity[i]; stock += consumed;
                }
                if (feasible && (x+y < bestExecutions || x+y == bestExecutions && stock < bestStock)) {
                    bestExecutions = x+y; bestStock = stock;
                }
            }
            var result = PairBatchAllocation.solve(demand,a,b,ca,cb,capacity,executions,() -> true);
            String message = "sample=" + sample;
            if (bestExecutions == Long.MAX_VALUE) assertNull(result, message);
            else {
                assertNotNull(result, message);
                assertEquals(bestExecutions, result.executions(), message);
                assertEquals(BigInteger.valueOf(bestStock), result.consumed(), message);
                assertTrue(a*result.first()+b*result.second() >= demand, message);
                for (int i = 0; i < dimensions; i++) assertTrue(ca[i]*result.first()+cb[i]*result.second() <= capacity[i], message);
            }
        }
    }

    @Test
    void hugeCountsAndHugeBatchSizesDoNotEnumerateFiringsOrOverflow() {
        long scale = 1_000_000_000_000L;
        var work = new AtomicInteger();
        var result = PairBatchAllocation.solve(8*scale,2,3,new long[] {4},new long[] {6},new long[] {16*scale},
                4*scale,() -> { work.incrementAndGet(); return true; });
        assertEquals(1, result.first());
        assertEquals((8*scale-2)/3, result.second());
        assertEquals(BigInteger.valueOf(16*scale), result.consumed());
        assertTrue(work.get() <= 2);
        scale = 100_000_000_000_000_000L;
        result = PairBatchAllocation.solve(8*scale,2*scale,3*scale,new long[] {4*scale},new long[] {6*scale},
                new long[] {16*scale},4,() -> true);
        assertEquals(1, result.first());
        assertEquals(2, result.second());
        assertNull(PairBatchAllocation.solve(3,1,2,new long[] {Sat.SAT-1},new long[] {Sat.SAT-2},
                new long[] {Sat.SAT-1},Sat.SAT-1,() -> true));
    }

    @Test
    void cappedSearchStillReturnsOnlyFeasibleCandidatesAndRespectsWork() {
        var work = new AtomicInteger();
        var result = PairBatchAllocation.solve(1_000_000,10007,10009,new long[] {1},new long[] {1},new long[] {200},
                200,() -> work.incrementAndGet() <= 3);
        assertNotNull(result);
        assertTrue(result.first()*10007+result.second()*10009 >= 1_000_000);
        assertTrue(result.executions() <= 200);
        assertEquals(4, work.get());
    }

    @Test
    void externalCancellationAndOptionalDeadlineRemainVisible() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> PairBatchAllocation.solve(8, 3, 2,
                    new long[] {3}, new long[] {1}, new long[] {9}, 3, () -> true));
        } finally {
            Thread.interrupted();
        }
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> PairBatchAllocation.solve(8, 3, 2,
                    new long[] {3}, new long[] {1}, new long[] {9}, 3, () -> true));
        }
    }
}
