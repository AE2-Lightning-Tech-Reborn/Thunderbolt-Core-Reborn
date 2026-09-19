package com.moakiee.thunderbolt.core.keys;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WeakCanonicalMapTest {
    record Value(String content) {}

    @Test void selfContainedFactoryAndLazyContentKeyOnlyRunOnMisses() {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var map = new WeakCanonicalMap<String, Value>(key -> { count.incrementAndGet(); return new Value(key); });
        var key = new String("same");
        var first = map.get(key);
        assertSame(first, map.get(new String("same")));
        assertSame(first, map.get(key, 0, () -> fail("identity hit built a content key")));
        assertEquals(1, count.get());
        map.clear();
        assertNotSame(first, map.get(key));
        assertEquals(2, count.get());
    }

    @Test void equalContentAndDifferentIdentityConvergeWithoutConstructingAgain() {
        var map = new WeakCanonicalMap<String, Value>(new WeakCanonicalMap.IdentityTable<>(16));
        var firstIdentity = new Object(); var secondIdentity = new Object();
        var first = map.intern(firstIdentity, 0, "same", key -> new Value("same"), v -> true);
        assertSame(first, map.findIdentity(firstIdentity, 0));
        var second = map.intern(secondIdentity, 0, new String("same"), key -> fail("unnecessary creation"), v -> true);
        assertSame(first, second);
        assertSame(first, map.findIdentity(secondIdentity, 0));
        assertNull(map.findIdentity(secondIdentity, 1));
    }

    @Test void l1EvictionAndSharedTablesDoNotChangeContentCanonicalization() {
        var table = new WeakCanonicalMap.IdentityTable<Value>(1);
        var a = new WeakCanonicalMap<String, Value>(table);
        var b = new WeakCanonicalMap<String, Value>(table);
        var identity = new Object();
        var first = a.intern(identity, 0, "0", key -> new Value("a"), v -> true);
        var foreign = b.intern(identity, 0, "0", key -> new Value("b"), v -> true);
        assertSame(foreign, b.findIdentity(identity, 0));
        var held = new ArrayList<Value>();
        for (int i = 1; i < 30; i++) {
            var text = Integer.toString(i);
            held.add(a.intern(new Object(), 0, text, key -> new Value(text), v -> true));
        }
        assertSame(first, a.intern(identity, 0, "0", key -> fail("eviction lost live L2 value"), v -> true));
        assertEquals(29, held.size());
    }

    @Test void racingFactoriesChooseOneLiveValueWithoutHoldingACacheLock() throws Exception {
        var map = new WeakCanonicalMap<String, Value>(new WeakCanonicalMap.IdentityTable<>(16));
        var barrier = new CyclicBarrier(2);
        java.util.function.Supplier<Value> create = () -> {
            try { barrier.await(5, TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError(e); }
            return new Value("same");
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> map.intern(new Object(), 0, "same", key -> create.get(), v -> true));
            var b = executor.submit(() -> map.intern(new Object(), 0, "same", key -> create.get(), v -> true));
            assertSame(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    @Test void clearDuringCreationCannotRepopulateTheNewGeneration() {
        var map = new WeakCanonicalMap<String, Value>(new WeakCanonicalMap.IdentityTable<>(16));
        var identity = new Object();
        var first = map.intern(identity, 0, "same", key -> { map.clear(); return new Value("same"); }, v -> true);
        assertNull(map.findIdentity(identity, 0));
        var next = map.intern(identity, 0, "same", key -> new Value("same"), v -> true);
        assertNotSame(first, next);
        assertEquals(first, next);
    }

    @Test void invalidRepresentativesAreReplacedAndInvalidNewValuesAreNotPublished() {
        var map = new WeakCanonicalMap<String, Value>(new WeakCanonicalMap.IdentityTable<>(16));
        var identity = new Object();
        var first = map.intern(identity, 0, "key", key -> new Value("old"), v -> true);
        var next = map.intern(identity, 0, "key", key -> new Value("new"), v -> v.content().equals("new"));
        assertNotSame(first, next);
        assertEquals("new", next.content());
        var rejectedIdentity = new Object();
        map.intern(rejectedIdentity, 0, "other", key -> new Value("invalid"), v -> false);
        assertNull(map.findIdentity(rejectedIdentity, 0));
    }

    @Test void neitherIdentityNorValueIsPinnedByEitherCache() throws Exception {
        var map = new WeakCanonicalMap<String, Value>(new WeakCanonicalMap.IdentityTable<>(16));
        var references = populateWeakly(map);
        for (int i = 0; i < 100 && (references.get(0).get() != null || references.get(1).get() != null); i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(references.get(0).get(), "identity was retained strongly");
        assertNull(references.get(1).get(), "value was retained strongly");
        assertNotNull(map.intern(new Object(), 0, "same", key -> new Value("same"), v -> true));
    }

    private static java.util.List<WeakReference<?>> populateWeakly(WeakCanonicalMap<String, Value> map) {
        var identity = new Object();
        var value = map.intern(identity, 0, "same", key -> new Value("same"), v -> true);
        return java.util.List.of(new WeakReference<>(identity), new WeakReference<>(value));
    }

    @Test void idleCleanupDoesNotRetainTheContentKeyOrValue() throws Exception {
        var map = new WeakCanonicalMap<String, Value>(Value::new);
        var refs = populateWithIndependentContent(map);
        for (int i = 0; i < 100 && (refs.get(0).get() != null || refs.get(1).get() != null); i++) {
            System.gc();
            map.cleanUp();
            Thread.sleep(10);
        }
        assertNull(refs.get(1).get(), "value retained");
        assertNull(refs.get(0).get(), "idle content key retained after cleanup");
    }

    private static java.util.List<WeakReference<?>> populateWithIndependentContent(WeakCanonicalMap<String, Value> map) {
        var key = new String("content owned by this invocation");
        var value = map.get(key);
        return java.util.List.of(new WeakReference<>(key), new WeakReference<>(value));
    }

    @Test void keyEqualsValueDoesNotKeepItselfAlive() throws Exception {
        var map = new WeakCanonicalMap<Value, Value>(java.util.function.Function.identity());
        var reference = putSelf(map);
        collect(reference, map::cleanUp);
        assertNull(reference.get(), "K == V was pinned by a strong content key");
        var next = new Value("self");
        assertSame(next, map.get(next));
    }

    private static WeakReference<Value> putSelf(WeakCanonicalMap<Value, Value> map) {
        var self = new Value("self");
        assertSame(self, map.get(self));
        assertSame(self, map.get(new Value("self")));
        return new WeakReference<>(self);
    }

    private static final class Cycle {
        Cycle peer;
    }

    @Test void mutuallyReferencingKeyAndValueAreCollectible() throws Exception {
        var map = new WeakCanonicalMap<Cycle, Cycle>(key -> key.peer);
        var references = putCycle(map);
        collect(references.get(0), map::cleanUp);
        collect(references.get(1), map::cleanUp);
        assertNull(references.get(0).get(), "cyclic key was retained");
        assertNull(references.get(1).get(), "cyclic value was retained");
    }

    private static java.util.List<WeakReference<Cycle>> putCycle(WeakCanonicalMap<Cycle, Cycle> map) {
        var key = new Cycle(); var value = new Cycle(); key.peer = value; value.peer = key;
        assertSame(value, map.get(key));
        return java.util.List.of(new WeakReference<>(key), new WeakReference<>(value));
    }

    @Test void selfContainedValueKeepsItsEqualKeyCanonicalAcrossGc() {
        var map = new WeakCanonicalMap<String, Value>(Value::new);
        var originalKey = new String("retained key");
        var value = map.get(originalKey);
        System.gc();
        map.cleanUp();
        assertSame(value, map.get(new String("retained key")));
        assertSame(originalKey, value.content());
    }

    private static final class ProjectedValue {
        final String content;
        Object snapshot = new Object();
        ProjectedValue(String content) { this.content = content; }
    }

    private record ContentLookup(String content) implements WeakCanonicalMap.Lookup<Object, ProjectedValue> {
        @Override public int hash() { return content.hashCode(); }
        @Override public boolean matches(Object key, ProjectedValue value) {
            return key == value.snapshot && content.equals(value.content);
        }
    }

    private static WeakCanonicalMap<Object, ProjectedValue> projectedMap() {
        return new WeakCanonicalMap<>(new WeakCanonicalMap.IdentityTable<>(16), value -> value.snapshot);
    }

    private record ProjectionWitness(ProjectedValue value, WeakReference<?> probe, WeakReference<?> identity) {}

    private static ProjectionWitness populateProjection(WeakCanonicalMap<Object, ProjectedValue> map) {
        var lookup = new ContentLookup(new String("projected"));
        var identity = new Object();
        var value = map.intern(identity, 0, lookup, () -> new ProjectedValue(new String("projected")),
                created -> created.snapshot, v -> true);
        return new ProjectionWitness(value, new WeakReference<>(lookup), new WeakReference<>(identity));
    }

    @Test void liveValuePreservesProjectedEntryAfterOriginalQueryAndIdentityAreCollected() throws Exception {
        var map = projectedMap();
        var witness = populateProjection(map);
        collect(witness.probe(), map::cleanUp);
        collect(witness.identity(), map::cleanUp);
        assertNull(witness.probe().get(), "transient query retained");
        assertNull(witness.identity().get(), "identity retained");
        var identity = new Object();
        assertSame(witness.value(), map.intern(identity, 0, new ContentLookup("projected"),
                () -> fail("live value lost canonicality after GC"), v -> v.snapshot, v -> true));
        assertSame(witness.value(), map.findIdentity(identity, 0));
    }

    @Test void projectedValueAndItsOwnedSnapshotAreBothCollectible() throws Exception {
        var map = projectedMap();
        var references = populateProjectedWeakly(map);
        collect(references.get(0), map::cleanUp);
        collect(references.get(1), map::cleanUp);
        assertNull(references.get(0).get(), "projected value retained");
        assertNull(references.get(1).get(), "owned snapshot retained");
    }

    private static java.util.List<WeakReference<?>> populateProjectedWeakly(WeakCanonicalMap<Object, ProjectedValue> map) {
        var value = populateProjection(map).value();
        return java.util.List.of(new WeakReference<>(value), new WeakReference<>(value.snapshot));
    }

    @Test void changedOwnedSnapshotInvalidatesAliasesAndContentEntries() {
        var map = projectedMap();
        var alias = new Object();
        var lookup = new ContentLookup("same");
        var old = map.intern(alias, 0, lookup, () -> new ProjectedValue("same"), v -> v.snapshot, v -> true);
        var oldSnapshot = old.snapshot;
        assertSame(old, map.findIdentity(alias, 0));
        old.snapshot = new Object();
        assertNull(map.findIdentity(alias, 0), "stale identity alias survived");
        assertNull(map.recentValue(), "recent shortcut ignored the snapshot stamp");
        var next = map.intern(alias, 0, lookup, () -> new ProjectedValue("same"), v -> v.snapshot, v -> true);
        assertNotSame(old, next, "content lookup accepted a stale representative");
        assertSame(next, map.findIdentity(alias, 0));
        java.lang.ref.Reference.reachabilityFence(oldSnapshot);
    }

    @Test void projectedConcurrentCreationChoosesOneRepresentative() throws Exception {
        var map = projectedMap();
        var barrier = new CyclicBarrier(2);
        java.util.function.Supplier<ProjectedValue> create = () -> {
            try { barrier.await(5, TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError(e); }
            return new ProjectedValue("racing");
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> map.intern(new Object(), 0, new ContentLookup("racing"), create,
                    v -> v.snapshot, v -> true));
            var b = executor.submit(() -> map.intern(new Object(), 0, new ContentLookup("racing"), create,
                    v -> v.snapshot, v -> true));
            assertSame(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    @Test void projectedConstructorCannotPublishIntoAClearedGeneration() {
        var map = projectedMap();
        var alias = new Object();
        var old = map.intern(alias, 0, new ContentLookup("clear"), () -> {
            map.clear(); return new ProjectedValue("clear");
        }, v -> v.snapshot, v -> true);
        assertNull(map.findIdentity(alias, 0));
        var next = map.intern(alias, 0, new ContentLookup("clear"), () -> new ProjectedValue("clear"),
                v -> v.snapshot, v -> true);
        assertNotSame(old, next);
    }

    private static void collect(WeakReference<?> reference, Runnable cleanup) throws Exception {
        for (int i = 0; i < 100 && reference.get() != null; i++) {
            System.gc(); cleanup.run(); Thread.sleep(10);
        }
    }
}
