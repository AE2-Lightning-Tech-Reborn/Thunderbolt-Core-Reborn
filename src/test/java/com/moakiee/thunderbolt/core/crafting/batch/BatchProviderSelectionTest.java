package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.batch.BatchDispatchMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BatchProviderSelectionTest {
    private static BatchExecutor.EligibleProvider provider(long capacity, BatchDispatchMode mode) {
        return new BatchExecutor.EligibleProvider(null, null, capacity, mode, null);
    }

    @Test
    void sharedInputPrefersUnboundedThenCapacityAndKeepsTheFirstTie() {
        var normal = provider(Long.MAX_VALUE, BatchDispatchMode.NORMAL);
        var unbounded = provider(2, BatchDispatchMode.UNBOUNDED);
        var larger = provider(8, BatchDispatchMode.UNBOUNDED);
        var tied = provider(8, BatchDispatchMode.UNBOUNDED);
        var candidates = new ArrayList<>(List.of(normal, unbounded, larger, tied));
        BatchExecutor.retainBestSharedProvider(candidates);
        assertEquals(1, candidates.size());
        assertSame(larger, candidates.getFirst());
    }

    @Test
    void emptyAndSingleCandidateListsKeepTheirContents() {
        var candidates = new ArrayList<BatchExecutor.EligibleProvider>();
        BatchExecutor.retainBestSharedProvider(candidates);
        assertTrue(candidates.isEmpty());
        var only = provider(Long.MAX_VALUE, BatchDispatchMode.NORMAL);
        candidates.add(only);
        BatchExecutor.retainBestSharedProvider(candidates);
        assertSame(only, candidates.getFirst());
    }

    @Test
    void randomizedWinnersMatchTheOriginalStableSortIncludingTiesAndLargeCapacities() {
        var random = new Random(0xBA7C4L);
        var originalOrder = Comparator
                .comparing((BatchExecutor.EligibleProvider p) -> p.mode() != BatchDispatchMode.UNBOUNDED)
                .thenComparing(BatchExecutor.EligibleProvider::capacity, Comparator.reverseOrder());
        for (int run = 0; run < 2_000; run++) {
            var candidates = new ArrayList<BatchExecutor.EligibleProvider>();
            int count = 1 + random.nextInt(256);
            for (int i = 0; i < count; i++) {
                long capacity = switch (random.nextInt(3)) {
                    case 0 -> Long.MAX_VALUE;
                    case 1 -> 2 + random.nextInt(4);
                    default -> 2 + random.nextLong(Long.MAX_VALUE - 2);
                };
                candidates.add(provider(capacity, random.nextBoolean()
                        ? BatchDispatchMode.NORMAL : BatchDispatchMode.UNBOUNDED));
            }
            var sorted = new ArrayList<>(candidates);
            sorted.sort(originalOrder);
            BatchExecutor.retainBestSharedProvider(candidates);
            assertEquals(1, candidates.size());
            assertSame(sorted.getFirst(), candidates.getFirst());
        }
    }
}
