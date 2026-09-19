package com.moakiee.thunderbolt.core.keys;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Test;

class WeakOpenHashMapTest {
    private record Key(int id) {
        @Override public int hashCode() { return id % 8; }
    }
    private record Value(Key key) {}

    @Test void collisionsDeletionAndResizePreserveAllSurvivors() {
        var map = new WeakOpenHashMap<Key, Value>(4);
        var held = new ArrayList<Value>();
        for (int i = 0; i < 600; i++) {
            var value = new Value(new Key(i));
            held.add(value);
            assertNull(map.putIfAbsent(value.key(), value));
        }
        for (int i = 0; i < held.size(); i += 2) map.remove(new Key(i), held.get(i));
        for (int i = 0; i < held.size(); i++) {
            assertSame(i % 2 == 0 ? null : held.get(i), map.get(new Key(i)));
        }
        for (int i = 0; i < held.size(); i += 2) {
            var replacement = new Value(new Key(i));
            assertNull(map.putIfAbsent(replacement.key(), replacement));
            held.set(i, replacement);
        }
        for (int i = 0; i < held.size(); i++) assertSame(held.get(i), map.get(new Key(i)));
    }

    @Test void removeUsesExpectedValueIdentity() {
        var map = new WeakOpenHashMap<Key, Value>();
        var first = new Value(new Key(1));
        var equal = new Value(new Key(1));
        assertNull(map.putIfAbsent(first.key(), first));
        assertSame(first, map.putIfAbsent(equal.key(), equal));
        map.remove(equal.key(), equal);
        assertSame(first, map.get(equal.key()));
        map.remove(equal.key(), first);
        assertNull(map.get(equal.key()));
    }

    @Test void delayedGcQueueEventsCannotDeleteANewEqualRepresentative() throws Exception {
        var map = new WeakOpenHashMap<Key, Value>(4);
        var old = new Value(new Key(1));
        map.putIfAbsent(old.key(), old);
        var oldReference = keyReference(map, old.key());
        oldReference.clear(); // Deterministic simulation of GC before queue delivery.
        var replacement = new Value(new Key(1));
        assertNull(map.putIfAbsent(replacement.key(), replacement));
        assertTrue(oldReference.enqueue());
        map.cleanUp();
        assertSame(replacement, map.get(new Key(1)));
    }

    @Test void everyQueuedReferenceIsEventuallyRemovedAcrossCleanupBatches() throws Exception {
        var map = new WeakOpenHashMap<Key, Value>();
        var held = new ArrayList<Value>();
        var queued = new ArrayList<Reference<?>>();
        for (int i = 0; i < 100; i++) {
            var value = new Value(new Key(i)); held.add(value);
            map.putIfAbsent(value.key(), value);
            queued.add(keyReference(map, value.key()));
        }
        for (var reference : queued) { reference.clear(); reference.enqueue(); }
        for (int i = 0; i < 4; i++) map.cleanUp();
        for (var node : slots(map)) assertFalse(queued.contains(node), "cleanup lost a polled queue event");
        Reference.reachabilityFence(held);
    }

    @Test void clearingValueAloneAllowsANewKeyWithoutRetainingTheOldKey() throws Exception {
        var map = new WeakOpenHashMap<Key, Value>();
        var old = new Value(new Key(1));
        map.putIfAbsent(old.key(), old);
        var keyReference = keyReference(map, old.key());
        var field = keyReference.getClass().getDeclaredField("value"); field.setAccessible(true);
        var valueReference = (Reference<?>) field.get(keyReference);
        valueReference.clear();
        var replacement = new Value(new Key(1));
        assertNull(map.putIfAbsent(replacement.key(), replacement));
        valueReference.enqueue(); map.cleanUp();
        assertSame(replacement, map.get(new Key(1)));
        assertSame(replacement.key(), keyReference(map, replacement.key()).get());
    }

    @Test void concurrentWritersPublishOneLiveRepresentative() throws Exception {
        var map = new WeakOpenHashMap<Key, Value>(4);
        var winners = new AtomicReferenceArray<Value>(256);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 4; thread++) tasks.add(workers.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                for (int i = 0; i < 1024; i++) {
                    int id = i % winners.length();
                    var candidate = new Value(new Key(id));
                    var previous = map.putIfAbsent(candidate.key(), candidate);
                    var winner = previous != null ? previous : candidate;
                    var stored = winners.compareAndExchange(id, null, winner);
                    assertTrue(stored == null || stored == winner, "two live representatives escaped");
                }
                return null;
            }));
            start.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        for (int i = 0; i < winners.length(); i++) assertSame(winners.get(i), map.get(new Key(i)));
    }

    @Test void readersNeverLoseHeldKeysWhileWritersResizeAndDeleteOtherKeys() throws Exception {
        var map = new WeakOpenHashMap<Key, Value>(4);
        var held = new ArrayList<Value>();
        for (int i = 0; i < 32; i++) {
            var value = new Value(new Key(i)); held.add(value);
            map.putIfAbsent(value.key(), value);
        }
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(3)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 2; thread++) tasks.add(workers.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                for (int i = 0; i < 100_000; i++) assertSame(held.get(i & 31), map.get(new Key(i & 31)));
                return null;
            }));
            tasks.add(workers.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                var retained = new ArrayList<Value>();
                for (int i = 32; i < 2000; i++) {
                    var value = new Value(new Key(i)); retained.add(value);
                    map.putIfAbsent(value.key(), value);
                    if (i % 3 == 0) map.remove(value.key(), value);
                }
                Reference.reachabilityFence(retained);
                return null;
            }));
            start.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
    }

    // Access the actual queue references only to deterministically exercise late GC notifications.
    private static Reference<?> keyReference(WeakOpenHashMap<?, ?> map, Object key) throws Exception {
        for (var node : slots(map)) if (node instanceof Reference<?> ref && ref.get() == key) return ref;
        throw new AssertionError("missing key reference");
    }

    private static Object[] slots(WeakOpenHashMap<?, ?> map) throws Exception {
        var table = WeakOpenHashMap.class.getDeclaredField("table"); table.setAccessible(true);
        var current = table.get(map);
        var slots = current.getClass().getDeclaredField("slots"); slots.setAccessible(true);
        return (Object[]) slots.get(current);
    }
}
