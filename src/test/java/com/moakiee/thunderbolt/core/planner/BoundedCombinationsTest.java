package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BoundedCombinationsTest {

    @Test
    void sparseFrontierPreservesLegacyTieOrder() {
        var random = new java.util.Random(20261024);
        for (int trial = 0; trial < 200; trial++) {
            var slots = new ArrayList<List<Integer>>();
            for (int j = random.nextInt(7); j > 0; j--) {
                var options = new ArrayList<Integer>();
                for (int k = 0, count = 1 + random.nextInt(4); k < count; k++) options.add(k);
                slots.add(options);
            }
            int limit = 1 + random.nextInt(64);
            assertEquals(legacy(slots, limit), BoundedCombinations.bestFirst(slots, limit));
        }
    }

    @Test
    void veryWideTwoChoicePatternDoesNotCopyDenseFrontierVectors() {
        var slots = java.util.Collections.nCopies(8192, List.of(0, 1));
        var result = BoundedCombinations.bestFirst(slots, 16);
        assertEquals(16, result.size());
        assertEquals(16, new java.util.HashSet<>(result).size());
        assertEquals(0, result.getFirst().stream().mapToInt(Integer::intValue).sum());
        for (int i = 1; i < result.size(); i++)
            assertEquals(1, result.get(i).stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void cancelledExpansionDoesNotEnumerate() {
        Thread.currentThread().interrupt();
        try {
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,
                    () -> BoundedCombinations.bestFirst(List.of(List.of(0, 1)), 64));
        } finally { Thread.interrupted(); }
    }

    private static List<List<Integer>> legacy(List<List<Integer>> slots, int limit) {
        int n = slots.size();
        var heap = new java.util.PriorityQueue<int[]>(java.util.Comparator.comparingInt(a -> a[n]));
        var visited = new java.util.HashSet<String>();
        var result = new ArrayList<List<Integer>>();
        heap.add(new int[n + 1]);
        visited.add(java.util.Arrays.toString(new int[n + 1]));
        while (!heap.isEmpty() && result.size() < limit) {
            var current = heap.remove();
            var values = new ArrayList<Integer>();
            for (int s = 0; s < n; s++) values.add(slots.get(s).get(current[s]));
            result.add(values);
            for (int s = 0; s < n; s++) {
                if (current[s] + 1 >= slots.get(s).size()) continue;
                var next = current.clone(); next[s]++; next[n]++;
                if (visited.add(java.util.Arrays.toString(next))) heap.add(next);
            }
        }
        return result;
    }

    private static List<String> slot(String... opts) {
        return List.of(opts);
    }

    @Test
    void withinBudgetEmitsEveryCombination() {
        List<List<String>> slots = List.of(slot("a0", "a1"), slot("b0", "b1", "b2"));
        List<List<String>> combos = BoundedCombinations.bestFirst(slots, 32);
        assertEquals(6, combos.size()); // 2 * 3, all kept
        // first combination is the all-best (index 0 in every slot)
        assertEquals(List.of("a0", "b0"), combos.get(0));
    }

    @Test
    void overBudgetKeepsExactlyTheLowestRankSumFront() {
        // 4 slots x 4 options = 256 combos, budget 5: must return the 5 lowest rank-sum vectors.
        List<List<String>> slots = List.of(
            slot("a0", "a1", "a2", "a3"),
            slot("b0", "b1", "b2", "b3"),
            slot("c0", "c1", "c2", "c3"),
            slot("d0", "d1", "d2", "d3"));
        List<List<String>> combos = BoundedCombinations.bestFirst(slots, 5);
        assertEquals(5, combos.size());
        // rank-sum must be non-decreasing across the returned front
        long prev = -1;
        for (List<String> combo : combos) {
            long rank = combo.stream().mapToLong(s -> s.charAt(1) - '0').sum();
            assertTrue(rank >= prev, "front must be ordered by non-decreasing rank-sum");
            prev = rank;
        }
        // the cheapest is all-best (rank 0); the next four each differ from it by exactly one step (rank 1)
        assertEquals(List.of("a0", "b0", "c0", "d0"), combos.get(0));
        for (int i = 1; i < 5; i++) {
            long rank = combos.get(i).stream().mapToLong(s -> s.charAt(1) - '0').sum();
            assertEquals(1, rank, "the four runners-up are the single-step neighbors of the all-best vector");
        }
    }

    @Test
    void singleHugeSlotKeepsTheFirstBudgetOptions() {
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            big.add("x" + i);
        }
        List<List<String>> combos = BoundedCombinations.bestFirst(List.of(big), 32);
        assertEquals(32, combos.size());
        assertEquals(List.of("x0"), combos.get(0));
        assertEquals(List.of("x31"), combos.get(31));
    }

    @Test
    void astronomicalProductIsBoundedAndDoesNotHang() {
        // 50 slots x 4 options => 4^50 ≈ 1.27e30, which overflows long if multiplied naively. The
        // enumeration must stay bounded by the budget and finish near-instantly regardless.
        List<List<String>> slots = new ArrayList<>();
        for (int s = 0; s < 50; s++) {
            slots.add(slot("o0", "o1", "o2", "o3"));
        }
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            List<List<String>> combos = BoundedCombinations.bestFirst(slots, 32);
            assertEquals(32, combos.size());
            for (List<String> combo : combos) {
                assertEquals(50, combo.size());
            }
        });
    }

    @Test
    void anEmptySlotYieldsNoCombination() {
        List<List<String>> slots = List.of(slot("a0", "a1"), List.of());
        assertTrue(BoundedCombinations.bestFirst(slots, 32).isEmpty());
    }

    @Test
    void nonPositiveLimitYieldsNothing() {
        List<List<String>> slots = List.of(slot("a0"), slot("b0"));
        assertTrue(BoundedCombinations.bestFirst(slots, 0).isEmpty());
        assertFalse(BoundedCombinations.bestFirst(slots, 1).isEmpty());
    }
}
