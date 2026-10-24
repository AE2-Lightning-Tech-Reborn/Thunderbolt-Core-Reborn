package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Bounded, overflow-free enumeration of the "best" combinations across per-slot option lists.
 *
 * <p>Used by the hard-fuzzy (OR) expansion: each input slot accepts several concrete substitutes, and a
 * recipe is the cartesian product of one choice per slot. That product can be astronomically large (many
 * slots over big tags), so instead of either enumerating it all (hang / Long overflow) or dropping the
 * whole recipe when it overruns a budget (false negative for something craftable), we keep only the best
 * {@code limit} combinations.
 *
 * <p>"Best" = lowest rank-sum, where each slot's options are assumed pre-sorted best-first (e.g. most
 * available substitute at index 0). Starting from the all-best vector we pop the lowest rank-sum index
 * vector and push its single-step neighbors; since every neighbor's rank-sum is strictly larger, the
 * first {@code limit} popped vectors are exactly the {@code limit} cheapest combinations.
 * Sparse frontier indices retain only nonzero choices (at most {@code limit}), rather than
 * copying every slot into every neighbor. Result vectors remain dense; the same rank heap and
 * neighbor insertion order preserve the existing tie order.
 */
public final class BoundedCombinations {

    private BoundedCombinations() {
    }

    /**
     * @param slots per-slot option lists, each ordered best-first
     * @param limit maximum number of combinations to return (must be &gt; 0 to yield anything)
     * @return up to {@code limit} combinations, lowest rank-sum first; empty if any slot has no options
     */
    public static <T> List<List<T>> bestFirst(List<List<T>> slots, int limit) {
        List<List<T>> out = new ArrayList<>();
        if (limit <= 0) {
            return out;
        }
        PlanningCancellation.check();
        int n = slots.size();
        for (List<T> slot : slots) {
            PlanningCancellation.check();
            if (slot.isEmpty()) {
                return out; // an unsatisfiable slot => no combination exists
            }
        }
        var heap = new PriorityQueue<Indices>(Comparator.comparingInt(Indices::rank));
        Set<Indices> visited = new HashSet<>();
        var start = new Indices(new int[0], new int[0], 0);
        heap.add(start);
        visited.add(start);
        while (!heap.isEmpty() && out.size() < limit) {
            PlanningCancellation.check();
            var cur = heap.poll();
            List<T> combo = new ArrayList<>(n);
            for (int s = 0, nonzero = 0; s < n; s++) {
                if ((s & 63) == 0) PlanningCancellation.check();
                int index = nonzero < cur.slots.length && cur.slots[nonzero] == s
                        ? cur.choices[nonzero++] : 0;
                combo.add(slots.get(s).get(index));
            }
            out.add(combo);
            if (out.size() == limit) break;
            for (int s = 0, nonzero = 0; s < n; s++) {
                if ((s & 63) == 0) PlanningCancellation.check();
                boolean present = nonzero < cur.slots.length && cur.slots[nonzero] == s;
                int choice = present ? cur.choices[nonzero] : 0;
                if (choice + 1 < slots.get(s).size()) {
                    var next = cur.increment(s, nonzero, present);
                    if (visited.add(next)) heap.add(next);
                }
                if (present) nonzero++;
            }
        }
        return out;
    }

    /** Sparse nonzero indices: a frontier node no longer retains an array/string of every slot. */
    private record Indices(int[] slots, int[] choices, int rank) {
        Indices increment(int slot, int position, boolean present) {
            int[] nextSlots = slots;
            int[] nextChoices;
            if (present) {
                nextChoices = choices.clone();
                nextChoices[position]++;
            } else {
                nextSlots = new int[slots.length + 1];
                nextChoices = new int[choices.length + 1];
                System.arraycopy(slots, 0, nextSlots, 0, position);
                System.arraycopy(choices, 0, nextChoices, 0, position);
                nextSlots[position] = slot;
                nextChoices[position] = 1;
                System.arraycopy(slots, position, nextSlots, position + 1, slots.length - position);
                System.arraycopy(choices, position, nextChoices, position + 1, choices.length - position);
            }
            return new Indices(nextSlots, nextChoices, rank + 1);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Indices indices && Arrays.equals(slots, indices.slots)
                    && Arrays.equals(choices, indices.choices);
        }

        @Override public int hashCode() {
            return 31 * Arrays.hashCode(slots) + Arrays.hashCode(choices);
        }
    }
}
