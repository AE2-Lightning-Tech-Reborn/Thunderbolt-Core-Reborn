package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CpSatFixedRanksTest {
    @Test void includesEveryInputKindAndSideOutputWithContractedGroupsAndDuplicateArcs() {
        var pre = new SparseLongMatrix(2, 7);
        var post = new SparseLongMatrix(2, 7);
        var catalyst = new SparseLongMatrix(2, 7);
        var finite = new SparseLongMatrix(2, 7);
        var reusable = new SparseLongMatrix(2, 1);
        pre.set(0, 0, 1); catalyst.set(0, 1, 1); finite.set(0, 2, 1); reusable.set(0, 0, 1);
        pre.set(1, 4, 1);
        post.set(0, 4, 1); post.set(0, 6, 1); post.set(1, 5, 1); post.set(1, 6, 1);
        var ranks = CpSatBridge.fixedRanks(pre, post, catalyst, finite, reusable,
                new int[] {3}, new int[] {4, 5}, new int[] {100, 200, 300, 400, 500, 500, 900},
                new long[] {1, 1}, Long.MAX_VALUE);
        assertNotNull(ranks);
        for (int i = 0; i < 4; i++) assertTrue(ranks[i] < ranks[4]);
        assertEquals(ranks[4], ranks[5]);
        assertTrue(ranks[4] < ranks[6]);
        for (long rank : ranks) assertTrue(rank >= 0 && rank < 7);
    }

    @Test void optionalUnionCyclesRetainFreeRanksButDisabledRecipesDoNotAddPrecedence() {
        var pre = new SparseLongMatrix(2, 2);
        var post = new SparseLongMatrix(2, 2);
        var empty = new SparseLongMatrix(2, 2);
        var reusable = new SparseLongMatrix(2, 0);
        pre.set(0, 0, 1); post.set(0, 1, 1);
        pre.set(1, 1, 1); post.set(1, 0, 1);
        assertNull(CpSatBridge.fixedRanks(pre, post, empty, empty, reusable, new int[0],
                new int[] {1, 0}, new int[] {0, 1}, new long[] {1, 1}, Long.MAX_VALUE));
        var ranks = CpSatBridge.fixedRanks(pre, post, empty, empty, reusable, new int[0],
                new int[] {1, 0}, new int[] {0, 1}, new long[] {1, 0}, Long.MAX_VALUE);
        assertNotNull(ranks);
        assertTrue(ranks[0] < ranks[1]);
    }

    @Test void expiryAndInterruptionCannotPublishAnUnfinishedOrder() {
        var empty = new SparseLongMatrix(0, 1);
        var reusable = new SparseLongMatrix(0, 0);
        assertNull(CpSatBridge.fixedRanks(empty, empty, empty, empty, reusable, new int[0],
                new int[0], new int[] {0}, new long[0], System.nanoTime() - 1));
        Thread.currentThread().interrupt();
        try {
            assertNull(CpSatBridge.fixedRanks(empty, empty, empty, empty, reusable, new int[0],
                    new int[0], new int[] {0}, new long[0], Long.MAX_VALUE));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
