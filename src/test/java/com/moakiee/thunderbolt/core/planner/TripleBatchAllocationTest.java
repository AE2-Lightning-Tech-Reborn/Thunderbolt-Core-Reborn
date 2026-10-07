package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TripleBatchAllocationTest {
    @Test
    void threeSourcesSaveMaterialWhenEveryPairMissesTheTieBreak() {
        var result = TripleBatchAllocation.solve(12, 3, 4, 5,
                new long[] {1}, new long[] {4}, new long[] {6}, new long[] {12}, 3, () -> true);
        assertNotNull(result);
        assertEquals(3, result.executions());
        assertEquals(BigInteger.valueOf(11), result.consumed());
        assertEquals(1, result.first());
        assertEquals(1, result.second());
        assertEquals(1, result.third());
    }

    @Test
    void remainingThreeSourceCorpusCasesReachTheirIndependentlyEnumeratedOptima() {
        var executionGain = TripleBatchAllocation.solve(13, 1, 7, 4,
                new long[] {1}, new long[] {8}, new long[] {5}, new long[] {15}, 6, () -> true);
        assertNotNull(executionGain);
        assertEquals(4, executionGain.executions());
        assertEquals(BigInteger.valueOf(15), executionGain.consumed());
        assertEquals(2, executionGain.first());
        assertEquals(1, executionGain.second());
        assertEquals(1, executionGain.third());

        var resourceGain = TripleBatchAllocation.solve(16, 3, 7, 6,
                new long[] {1}, new long[] {8}, new long[] {7}, new long[] {17}, 3, () -> true);
        assertNotNull(resourceGain);
        assertEquals(3, resourceGain.executions());
        assertEquals(BigInteger.valueOf(16), resourceGain.consumed());
        assertEquals(1, resourceGain.first());
        assertEquals(1, resourceGain.second());
        assertEquals(1, resourceGain.third());
    }

    @Test
    void measuredSmallDomainMatchesIndependentThreeVariableEnumeration() {
        var random = new Random(2026100303L);
        int feasible = 0, optimal = 0;
        for (int sample = 0; sample < 2_000; sample++) {
            int demand = 1 + random.nextInt(36), executionLimit = 1 + random.nextInt(18);
            int dimensions = random.nextInt(4);
            long[] output = new long[3], capacity = new long[dimensions];
            long[][] use = new long[3][dimensions];
            for (int recipe = 0; recipe < 3; recipe++) {
                output[recipe] = 1 + random.nextInt(12);
                for (int resource = 0; resource < dimensions; resource++) use[recipe][resource] = random.nextInt(10);
            }
            for (int resource = 0; resource < dimensions; resource++) capacity[resource] = random.nextInt(91);
            long bestExecutions = Long.MAX_VALUE, bestStock = Long.MAX_VALUE;
            for (int a = 0; a <= executionLimit; a++) for (int b = 0; b <= executionLimit - a; b++)
                for (int c = 0; c <= executionLimit - a - b; c++) {
                    if (a * output[0] + b * output[1] + c * output[2] < demand) continue;
                    boolean fits = true;
                    long stock = 0;
                    for (int resource = 0; resource < dimensions; resource++) {
                        long used = a * use[0][resource] + b * use[1][resource] + c * use[2][resource];
                        fits &= used <= capacity[resource];
                        stock += used;
                    }
                    long executions = a + b + c;
                    if (fits && (executions < bestExecutions || executions == bestExecutions && stock < bestStock)) {
                        bestExecutions = executions;
                        bestStock = stock;
                    }
                }
            var result = TripleBatchAllocation.solve(demand, output[0], output[1], output[2],
                    use[0], use[1], use[2], capacity, executionLimit, () -> true);
            if (bestExecutions == Long.MAX_VALUE) assertNull(result, "sample=" + sample);
            else {
                feasible++;
                assertNotNull(result, "sample=" + sample);
                assertCertified(result, demand, output, use, capacity, executionLimit);
                if (result.executions() == bestExecutions && result.consumed().equals(BigInteger.valueOf(bestStock))) optimal++;
                assertEquals(bestExecutions, result.executions(), "sample=" + sample);
                assertEquals(BigInteger.valueOf(bestStock), result.consumed(), "sample=" + sample);
            }
        }
        assertTrue(feasible > 1_000, "the measured domain must contain substantial feasible coverage");
        // This bounded count/output domain is measured explicitly; arbitrary large triples need not be exact.
        assertEquals(feasible, optimal);
    }

    @Test
    void trillionScaleCountsAndOutputsUseTheSameFixedWorkCeiling() {
        for (boolean scaleBatches : new boolean[] {false, true}) {
            long scale = 1_000_000_000_000L;
            long[] output = scaleBatches ? new long[] {3 * scale, 4 * scale, 5 * scale} : new long[] {3, 4, 5};
            long[][] use = scaleBatches ? new long[][] {{scale}, {4 * scale}, {6 * scale}}
                    : new long[][] {{1}, {4}, {6}};
            long[] capacity = {12 * scale};
            long limit = scaleBatches ? 3 : 3 * scale;
            var work = new AtomicInteger();
            var result = TripleBatchAllocation.solve(12 * scale, output[0], output[1], output[2],
                    use[0], use[1], use[2], capacity, limit, () -> { work.incrementAndGet(); return true; });
            assertNotNull(result);
            assertCertified(result, 12 * scale, output, use, capacity, limit);
            assertTrue(work.get() <= TripleBatchAllocation.MAX_PAIR_CALLS + TripleBatchAllocation.MAX_RESIDUE_STEPS);
        }
    }

    @Test
    void aRefusedWorkDebitStopsEveryLaterPivotAndReturnedCandidatesRemainCertified() {
        long[] output = {3, 4, 5}, capacity = {11};
        long[][] use = {{1}, {4}, {6}};
        for (int limit = 0; limit <= 40; limit++) {
            int allowance = limit;
            var work = new AtomicInteger();
            var result = TripleBatchAllocation.solve(12, 3, 4, 5, use[0], use[1], use[2], capacity, 3,
                    () -> work.incrementAndGet() <= allowance);
            assertTrue(work.get() <= allowance + 1, "no retry after refusal, allowance=" + allowance);
            if (allowance == 0) assertNull(result);
            if (result != null) assertCertified(result, 12, output, use, capacity, 3);
        }
        assertArrayEquals(new long[] {11}, capacity);
        assertArrayEquals(new long[] {1}, use[0]);
        assertArrayEquals(new long[] {4}, use[1]);
        assertArrayEquals(new long[] {6}, use[2]);
    }

    @Test
    void invalidResidualDomainsAreRejectedConservatively() {
        assertNull(TripleBatchAllocation.solve(-1, 3, 4, 5,
                new long[] {1}, new long[] {4}, new long[] {6}, new long[] {12}, 3, () -> true));
        assertNull(TripleBatchAllocation.solve(12, 3, 4, 5,
                new long[] {1}, new long[] {4}, new long[] {6}, new long[] {-1}, 3, () -> true));
        assertNull(TripleBatchAllocation.solve(12, 3, 4, 5,
                new long[] {1}, new long[] {-4}, new long[] {6}, new long[] {12}, 3, () -> true));
        assertNull(TripleBatchAllocation.solve(12, 3, 4, 5,
                new long[] {1}, new long[] {4}, new long[] {6}, new long[] {12}, 0, () -> true));
        assertNull(TripleBatchAllocation.solve(Sat.SAT, 3, 4, 5,
                new long[] {1}, new long[] {4}, new long[] {6}, new long[] {12}, 3, () -> true));
        assertNull(TripleBatchAllocation.solve(12, 3, 4, 5,
                new long[] {1, 2}, new long[] {4}, new long[] {6}, new long[] {12}, 3, () -> true));
        assertNull(TripleBatchAllocation.solve(12, 3, 4, 5,
                new long[] {1}, null, new long[] {6}, new long[] {12}, 3, () -> true));
    }

    @Test
    void resourceProductsAndTotalsAreCheckedBeyondTheLongRange() {
        long maximum = Long.MAX_VALUE;
        assertNull(TripleBatchAllocation.solve(2, 1, 1, 1,
                new long[] {maximum}, new long[] {maximum}, new long[] {maximum},
                new long[] {maximum}, 2, () -> true));
        long[] capacity = {maximum, maximum, maximum};
        long[][] use = {{maximum, 0, 0}, {0, maximum, 0}, {0, 0, maximum}};
        var result = TripleBatchAllocation.solve(3, 1, 1, 1,
                use[0], use[1], use[2], capacity, 3, () -> true);
        assertNotNull(result);
        assertCertified(result, 3, new long[] {1, 1, 1}, use, capacity, 3);
        assertEquals(BigInteger.valueOf(maximum).multiply(BigInteger.valueOf(3)), result.consumed());
    }

    @Test
    void cancellationAndWorkExceptionsPropagateWithoutReplacingTheCallersWitness() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> TripleBatchAllocation.solve(12, 3, 4, 5,
                    new long[] {1}, new long[] {4}, new long[] {6}, new long[] {12}, 3, () -> true));
        } finally {
            Thread.interrupted();
        }
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> TripleBatchAllocation.solve(12, 3, 4, 5,
                    new long[] {1}, new long[] {4}, new long[] {6}, new long[] {12}, 3, () -> true));
        }
        var signal = new CancellationException("work cancelled");
        assertSame(signal, assertThrows(CancellationException.class, () -> TripleBatchAllocation.solve(12, 3, 4, 5,
                new long[] {1}, new long[] {4}, new long[] {6}, new long[] {12}, 3, () -> { throw signal; })));
    }

    private static void assertCertified(TripleBatchAllocation.Allocation result, long demand, long[] output,
            long[][] use, long[] capacity, long executionLimit) {
        long[] counts = {result.first(), result.second(), result.third()};
        BigInteger executions = BigInteger.ZERO, produced = BigInteger.ZERO, total = BigInteger.ZERO;
        for (int recipe = 0; recipe < 3; recipe++) {
            assertTrue(counts[recipe] >= 0);
            var count = BigInteger.valueOf(counts[recipe]);
            executions = executions.add(count);
            produced = produced.add(count.multiply(BigInteger.valueOf(output[recipe])));
        }
        assertTrue(executions.compareTo(BigInteger.valueOf(executionLimit)) <= 0);
        assertTrue(produced.compareTo(BigInteger.valueOf(demand)) >= 0);
        for (int resource = 0; resource < capacity.length; resource++) {
            BigInteger used = BigInteger.ZERO;
            for (int recipe = 0; recipe < 3; recipe++)
                used = used.add(BigInteger.valueOf(counts[recipe]).multiply(BigInteger.valueOf(use[recipe][resource])));
            assertTrue(used.compareTo(BigInteger.valueOf(capacity[resource])) <= 0);
            total = total.add(used);
        }
        assertEquals(total, result.consumed());
    }
}
