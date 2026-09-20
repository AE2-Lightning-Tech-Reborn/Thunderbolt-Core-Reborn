package com.moakiee.thunderbolt.core.keys;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Weak interner for the self-keyed case: one reference is both the table node and shortcut.
 * Reads are lock-free. Insertion/resize/GC deletion share one writer monitor; factories run outside
 * this class. Hashes and equality must describe the immutable value, including projected lookups.
 */
final class WeakCanonicalSet<V> {
    @FunctionalInterface
    interface Matcher<V, A, B> { boolean matches(V stored, A first, B second); }

    private static final Object DELETED = new Object();
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);
    private final ReferenceQueue<V> queue = new ReferenceQueue<>();
    private final Object[] shortcuts;
    private final BiPredicate<V, V> equality;
    private volatile Table table = new Table(16);

    private static final class Table {
        final Object[] slots;
        final int mask;
        int size, used; // Writer monitor only.
        Table(int capacity) { slots = new Object[capacity]; mask = capacity - 1; }
    }

    private static final class Entry<V> extends WeakReference<V> {
        final int hash;
        volatile boolean aliasSeen;
        Entry(V value, int hash, ReferenceQueue<V> queue) { super(value, queue); this.hash = hash; }
    }

    /**
     * Admit an optional secondary index only after another observation of the live representative.
     * Concurrent first observations may both return false; this only postpones the optimization.
     * The canonical entry itself is installed on first construction, regardless of this hint.
     */
    @SuppressWarnings("unchecked")
    boolean seenForAlias(V value, int hash) {
        var entry = (Entry<V>) SLOT.getAcquire(shortcuts, shortcutSlot(hash));
        if (entry != null && entry.hash == hash && entry.get() == value) return markSeen(entry);
        var current = table;
        for (int slot = spread(hash) & current.mask;; slot = (slot + 1) & current.mask) {
            var node = SLOT.getAcquire(current.slots, slot);
            if (node == null) return false;
            if (node == DELETED) continue;
            entry = (Entry<V>) node;
            if (entry.hash == hash && entry.get() == value) return markSeen(entry);
        }
    }

    private static boolean markSeen(Entry<?> entry) {
        boolean seen = entry.aliasSeen;
        if (!seen) entry.aliasSeen = true;
        return seen;
    }

    WeakCanonicalSet(int shortcutCapacity, BiPredicate<V, V> equality) {
        if (shortcutCapacity < 1 || Integer.bitCount(shortcutCapacity) != 1)
            throw new IllegalArgumentException("shortcut capacity must be a positive power of two");
        shortcuts = new Object[shortcutCapacity];
        this.equality = Objects.requireNonNull(equality);
    }

    private static int spread(int hash) {
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        return hash ^ (hash >>> 15);
    }

    private int shortcutSlot(int hash) { return (hash ^ (hash >>> 16)) & (shortcuts.length - 1); }

    /** Separate projection arguments avoid allocating a tuple for namespace/path-style lookups. */
    @SuppressWarnings("unchecked")
    <A, B> V get(int hash, A first, B second, Matcher<V, A, B> matcher) {
        int shortcut = shortcutSlot(hash);
        var entry = (Entry<V>) SLOT.getAcquire(shortcuts, shortcut);
        var cached = entry == null || entry.hash != hash ? null : entry.get();
        if (cached != null && matcher.matches(cached, first, second)) return cached;
        var current = table;
        for (int slot = spread(hash) & current.mask;; slot = (slot + 1) & current.mask) {
            var node = SLOT.getAcquire(current.slots, slot);
            if (node == null) return null;
            if (node == DELETED) continue;
            entry = (Entry<V>) node;
            if (entry.hash != hash) continue;
            var value = entry.get();
            if (value != null && matcher.matches(value, first, second)) {
                SLOT.setRelease(shortcuts, shortcut, entry);
                return value;
            }
        }
    }

    /** Recheck under the writer lock after outside construction, then publish one live winner. */
    @SuppressWarnings("unchecked")
    synchronized V intern(V candidate, int hash) {
        Objects.requireNonNull(candidate);
        drain(queue.poll());
        var current = table;
        int vacant = -1;
        for (int slot = spread(hash) & current.mask;; slot = (slot + 1) & current.mask) {
            var node = SLOT.getAcquire(current.slots, slot);
            if (node == null) {
                if (vacant < 0) vacant = slot;
                break;
            }
            if (node == DELETED) {
                if (vacant < 0) vacant = slot;
                continue;
            }
            var entry = (Entry<V>) node;
            var stored = entry.get();
            if (stored == null) {
                SLOT.setRelease(current.slots, slot, DELETED);
                current.size--;
                if (vacant < 0) vacant = slot;
            } else if (entry.hash == hash && equality.test(stored, candidate)) {
                SLOT.setRelease(shortcuts, shortcutSlot(hash), entry);
                return stored;
            }
        }
        // Old arrays retain a never-used null slot too, so concurrent readers always terminate.
        if ((current.used + 1L) * 3 >= current.slots.length * 2L) {
            int capacity = current.size * 3L < current.slots.length ? current.slots.length
                    : Math.multiplyExact(current.slots.length, 2);
            current = rebuild(current, capacity);
            vacant = emptySlot(current, hash);
        }
        var entry = new Entry<>(candidate, hash, queue);
        if (SLOT.getAcquire(current.slots, vacant) == null) current.used++;
        current.size++;
        SLOT.setRelease(current.slots, vacant, entry);
        SLOT.setRelease(shortcuts, shortcutSlot(hash), entry);
        return candidate;
    }

    void cleanUp() {
        var first = queue.poll();
        if (first == null) return;
        synchronized (this) { drain(first); }
    }

    /** A queue event belongs to its exact node, never to a subsequently inserted equal value. */
    private void drain(Reference<? extends V> first) {
        for (int i = 0; i < 32; i++) {
            var reference = i == 0 ? first : queue.poll();
            if (reference == null) break;
            var entry = (Entry<?>) reference;
            var current = table;
            for (int slot = spread(entry.hash) & current.mask;; slot = (slot + 1) & current.mask) {
                var node = SLOT.getAcquire(current.slots, slot);
                if (node == null) break;
                if (node == entry) {
                    SLOT.setRelease(current.slots, slot, DELETED);
                    current.size--;
                    break;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Table rebuild(Table previous, int capacity) {
        var replacement = new Table(capacity);
        for (var node : previous.slots) {
            if (node == null || node == DELETED) continue;
            var entry = (Entry<V>) node;
            if (entry.get() == null) continue;
            replacement.slots[emptySlot(replacement, entry.hash)] = entry;
            replacement.size++;
        }
        replacement.used = replacement.size;
        table = replacement;
        return replacement;
    }

    private static int emptySlot(Table table, int hash) {
        int slot = spread(hash) & table.mask;
        while (table.slots[slot] != null) slot = (slot + 1) & table.mask;
        return slot;
    }
}
