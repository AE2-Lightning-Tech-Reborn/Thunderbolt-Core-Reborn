package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IdentityCandidateQueueMutationTest {
    @Test
    void nullsAndEqualButDistinctCandidatesKeepIdentitySemantics() {
        var first = new EqualValue();
        var second = new EqualValue();
        var unknown = new EqualValue();
        var queue = new IdentityCandidateQueue<>(Arrays.asList(null, first, second, first, null));
        assertOrder(List.of(first, second), queue);
        queue.block(unknown);
        queue.block(null);
        queue.block(unknown);
        queue.markSuccess(unknown);
        assertEquals(2, queue.blockedCount());
        assertEquals(2, queue.activeCount());
        assertOrder(List.of(first, second), queue);
        queue.restoreBlocked();
        assertEquals(0, queue.blockedCount());
        assertFalse(queue.isBlocked(null));
        assertFalse(queue.isBlocked(unknown));
        assertOrder(List.of(first, second), queue);
    }

    @Test
    void oldIteratorsKeepTheirPreferredCandidateAndSkipConsecutiveRemovedNodes() {
        var values = values(5);
        var queue = new IdentityCandidateQueue<>(values);
        queue.markSuccess(values.get(3));
        var old = queue.iterator();
        queue.markSuccess(values.get(4));
        assertSame(values.get(3), old.next());
        for (int i = 0; i < 3; i++) queue.block(values.get(i));
        assertSame(values.get(4), old.next());
        assertFalse(old.hasNext());
        assertThrows(NoSuchElementException.class, old::next);
        queue.restoreBlocked();
        assertOrder(List.of(values.get(4), values.get(3), values.get(0), values.get(1), values.get(2)), queue);
    }

    @Test
    void aCandidateAlreadyPrefetchedByHasNextIsStillReturnedAfterItIsBlocked() {
        var values = values(3);
        var queue = new IdentityCandidateQueue<>(values);
        queue.markSuccess(values.get(2));
        var iterator = queue.iterator();
        assertTrue(iterator.hasNext());
        queue.block(values.get(2));
        assertSame(values.get(2), iterator.next());
        assertTrue(iterator.hasNext());
        queue.block(values.get(0));
        assertSame(values.get(0), iterator.next());
        assertSame(values.get(1), iterator.next());
        assertFalse(iterator.hasNext());
        queue.restoreBlocked();
        assertOrder(List.of(values.get(1), values.get(2), values.get(0)), queue);
    }

    @Test
    void randomBlockSuccessAndTickRestoreSequencesMatchAnIndependentListModel() {
        var initial = values(64);
        var choices = new ArrayList<>(initial);
        choices.add(new EqualValue());
        choices.add(null);
        var queue = new IdentityCandidateQueue<>(initial);
        var model = new ListModel(initial);
        var random = new Random(0xCADD1DA7EL);
        for (int step = 0; step < 5_000; step++) {
            var candidate = choices.get(random.nextInt(choices.size()));
            switch (random.nextInt(4)) {
                case 0, 1 -> { queue.block(candidate); model.block(candidate); }
                case 2 -> { queue.markSuccess(candidate); model.markSuccess(candidate); }
                default -> { queue.restoreBlocked(); model.restore(); }
            }
            assertEquals(model.active.size(), queue.activeCount(), "active count at " + step);
            assertEquals(model.blocked.size(), queue.blockedCount(), "blocked count at " + step);
            for (var value : choices) {
                assertEquals(indexOf(model.blocked, value) >= 0, queue.isBlocked(value));
            }
            assertOrder(model.ordered(), queue);
        }
    }

    @Test
    void randomInFlightRemovalsAndSuccessChangesMatchSnapshotTraversal() {
        var random = new Random(0x11FE17L);
        for (int run = 0; run < 500; run++) {
            var values = values(32);
            var queue = new IdentityCandidateQueue<>(values);
            var model = new ListModel(values);
            var preferred = values.get(random.nextInt(values.size()));
            queue.markSuccess(preferred);
            model.markSuccess(preferred);
            var order = model.ordered();
            var iterator = queue.iterator();
            int position = 0;
            while (true) {
                // Failures can remove the iterator's cursor or several of its following nodes.
                for (int i = 0, n = random.nextInt(5); i < n; i++) {
                    var failed = values.get(random.nextInt(values.size()));
                    queue.block(failed);
                    model.block(failed);
                }
                var success = values.get(random.nextInt(values.size()));
                queue.markSuccess(success);
                model.markSuccess(success);
                while (position < order.size() && indexOf(model.active, order.get(position)) < 0) position++;
                boolean expected = position < order.size();
                assertEquals(expected, iterator.hasNext());
                assertEquals(expected, iterator.hasNext());
                if (!expected) break;
                var next = order.get(position++);
                if (random.nextBoolean()) { // Preserve the existing hasNext/next buffering contract.
                    queue.block(next);
                    model.block(next);
                }
                assertSame(next, iterator.next());
            }
            queue.restoreBlocked();
            model.restore();
            assertOrder(model.ordered(), queue);
        }
    }

    private static List<EqualValue> values(int count) {
        var values = new ArrayList<EqualValue>();
        for (int i = 0; i < count; i++) values.add(new EqualValue());
        return values;
    }

    private static void assertOrder(List<EqualValue> expected, Iterable<EqualValue> actual) {
        var iterator = actual.iterator();
        for (var value : expected) {
            assertTrue(iterator.hasNext());
            assertSame(value, iterator.next());
        }
        assertFalse(iterator.hasNext());
    }

    private static int indexOf(List<EqualValue> values, EqualValue value) {
        for (int i = 0; i < values.size(); i++) if (values.get(i) == value) return i;
        return -1;
    }

    // The oracle uses only identity comparisons and ordinary lists, with no queue links or maps.
    private static final class ListModel {
        final List<EqualValue> original;
        final ArrayList<EqualValue> active;
        final ArrayList<EqualValue> blocked = new ArrayList<>();
        EqualValue preferred;

        ListModel(List<EqualValue> original) {
            this.original = original;
            this.active = new ArrayList<>(original);
        }

        void block(EqualValue value) {
            if (indexOf(blocked, value) >= 0) return;
            blocked.add(value);
            int position = indexOf(active, value);
            if (position >= 0) active.remove(position);
            if (preferred == value) preferred = null;
        }

        void markSuccess(EqualValue value) {
            if (indexOf(active, value) >= 0) preferred = value;
        }

        void restore() {
            for (var value : blocked) if (indexOf(original, value) >= 0) active.add(value);
            blocked.clear();
        }

        List<EqualValue> ordered() {
            var result = new ArrayList<EqualValue>();
            if (preferred != null) result.add(preferred);
            for (var value : active) if (value != preferred) result.add(value);
            return result;
        }
    }

    private static final class EqualValue {
        @Override public boolean equals(Object other) { return other instanceof EqualValue; }
        @Override public int hashCode() { return 1; }
    }
}
