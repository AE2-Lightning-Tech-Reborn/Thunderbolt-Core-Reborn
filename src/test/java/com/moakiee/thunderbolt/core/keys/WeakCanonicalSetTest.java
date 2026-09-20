package com.moakiee.thunderbolt.core.keys;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Test;

class WeakCanonicalSetTest {
    private record Value(int id) {}
    private static final WeakCanonicalSet.Matcher<Value, Integer, Void> MATCH = (value, id, unused) -> value.id == id;
    private static WeakCanonicalSet<Value> set() { return new WeakCanonicalSet<>(4, Value::equals); }
    private static Value get(WeakCanonicalSet<Value> set, int id) { return set.get(id % 8, id, null, MATCH); }
    private static Value intern(WeakCanonicalSet<Value> set, Value value) { return set.intern(value, value.id % 8); }

    @Test void shortcutCollisionsAndResizeKeepEveryLiveRepresentative() {
        var set = set();
        var held = new ArrayList<Value>();
        for (int i = 0; i < 600; i++) {
            var value = new Value(i); held.add(value);
            assertSame(value, intern(set, value));
        }
        for (var value : held) {
            assertSame(value, get(set, value.id));
            assertSame(value, intern(set, new Value(value.id)));
        }
        assertNull(get(set, 700));
    }

    @Test void shortcutReusesTheExactSingleWeakTableNode() throws Exception {
        var set = set();
        var value = intern(set, new Value(7));
        var node = reference(set, value);
        var field = WeakCanonicalSet.class.getDeclaredField("shortcuts"); field.setAccessible(true);
        var shortcuts = (Object[]) field.get(set);
        assertTrue(java.util.Arrays.asList(shortcuts).contains(node));
        Reference.reachabilityFence(value);
    }

    @Test void secondaryIndexAdmissionDoesNotDeferCanonicalityAndSurvivesShortcutEviction() {
        var set = set();
        var value = intern(set, new Value(7));
        assertFalse(set.seenForAlias(value, 7));
        var collision = intern(set, new Value(15));
        assertSame(value, intern(set, new Value(7)));
        assertTrue(set.seenForAlias(value, 7));
        assertFalse(set.seenForAlias(collision, 7));
        assertTrue(set.seenForAlias(collision, 7));
    }

    @Test void delayedGcNotificationCannotDeleteAnEqualReplacement() throws Exception {
        var set = set();
        var old = intern(set, new Value(5));
        var reference = reference(set, old);
        reference.clear();
        var replacement = intern(set, new Value(5));
        reference.enqueue(); set.cleanUp();
        assertSame(replacement, get(set, 5));
    }

    @Test void boundedCleanupDrainsAllEventsAcrossCalls() throws Exception {
        var set = set();
        var held = new ArrayList<Value>();
        var references = new ArrayList<Reference<?>>();
        for (int i = 0; i < 100; i++) {
            var value = intern(set, new Value(i)); held.add(value); references.add(reference(set, value));
        }
        for (var ref : references) { ref.clear(); ref.enqueue(); }
        for (int i = 0; i < 4; i++) set.cleanUp();
        for (var node : slots(set)) assertFalse(references.contains(node));
        Reference.reachabilityFence(held);
    }

    private static WeakReference<Value> unused(WeakCanonicalSet<Value> set) {
        return new WeakReference<>(intern(set, new Value(23)));
    }

    @Test void neitherTheTableNorShortcutRetainsAnUnusedValue() throws Exception {
        var set = set();
        var ref = unused(set);
        for (int i = 0; i < 100 && ref.get() != null; i++) { System.gc(); set.cleanUp(); Thread.sleep(10); }
        assertNull(ref.get());
        assertNull(get(set, 23));
    }

    @Test void concurrentWritersChooseOneRepresentative() throws Exception {
        var set = set();
        var winners = new AtomicReferenceArray<Value>(256);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 4; t++) tasks.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                for (int i = 0; i < 1024; i++) {
                    int id = i % winners.length();
                    var result = intern(set, new Value(id));
                    var previous = winners.compareAndExchange(id, null, result);
                    assertTrue(previous == null || previous == result);
                }
                return null;
            }));
            start.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        for (int i = 0; i < winners.length(); i++) assertSame(winners.get(i), get(set, i));
    }

    @Test void readersKeepFindingLiveValuesDuringConcurrentResizeAndDeletion() throws Exception {
        var set = set();
        var held = new ArrayList<Value>();
        for (int i = 0; i < 32; i++) held.add(intern(set, new Value(i)));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 2; t++) tasks.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                for (int i = 0; i < 100_000; i++) assertSame(held.get(i & 31), get(set, i & 31));
                return null;
            }));
            tasks.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                var retained = new ArrayList<Value>();
                for (int i = 32; i < 1200; i++) {
                    var value = intern(set, new Value(i)); retained.add(value);
                    if (i % 3 == 0) { var ref = reference(set, value); ref.clear(); ref.enqueue(); set.cleanUp(); }
                }
                Reference.reachabilityFence(retained);
                return null;
            }));
            start.countDown();
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
    }

    private static Reference<?> reference(WeakCanonicalSet<?> set, Object value) throws Exception {
        for (var node : slots(set)) if (node instanceof Reference<?> ref && ref.get() == value) return ref;
        throw new AssertionError("missing node");
    }

    private static Object[] slots(WeakCanonicalSet<?> set) throws Exception {
        var field = WeakCanonicalSet.class.getDeclaredField("table"); field.setAccessible(true);
        var table = field.get(set);
        field = table.getClass().getDeclaredField("slots"); field.setAccessible(true);
        return (Object[]) field.get(table);
    }
}
