package com.moakiee.thunderbolt.core.keys;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Content-equality weak keys and weak values with allocation-free, lock-free reads. Writers serialize
 * publication, deletion and resize; callers construct values outside this table. Immutable entries
 * are release-published into open-addressed arrays. Deletion leaves tombstones so readers never miss
 * a displaced live entry. Old arrays remain valid for readers already using them during a resize.
 * Keys must have stable hash/equals. A live value retains its entry only if it also retains its key.
 */
final class WeakOpenHashMap<K, V> {
    private static final Object DELETED = new Object();
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);
    private final ReferenceQueue<K> keyQueue = new ReferenceQueue<>();
    private final ReferenceQueue<V> valueQueue = new ReferenceQueue<>();
    private volatile Table table;

    private static final class Table {
        final Object[] slots;
        final int mask;
        int size, used; // Only accessed while holding the writer monitor.

        Table(int capacity) { slots = new Object[capacity]; mask = capacity - 1; }
    }

    private static final class Entry<K, V> extends WeakReference<K> {
        final int hash;
        final ValueReference<K, V> value;

        Entry(K key, V value, int hash, ReferenceQueue<K> keys, ReferenceQueue<V> values) {
            super(key, keys);
            this.hash = hash;
            this.value = new ValueReference<>(value, values, this);
        }
    }

    private static final class ValueReference<K, V> extends WeakReference<V> {
        final Entry<K, V> owner;

        ValueReference(V value, ReferenceQueue<V> queue, Entry<K, V> owner) {
            super(value, queue);
            this.owner = owner;
        }
    }

    WeakOpenHashMap() { this(16); }

    WeakOpenHashMap(int capacity) {
        if (capacity < 4 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("capacity must be a power of two >= 4");
        table = new Table(capacity);
    }

    private static int hash(int hash) {
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        return hash ^ (hash >>> 15);
    }

    /** Direct equality lookup avoids a temporary probe when the JIT cannot eliminate it. */
    @SuppressWarnings("unchecked")
    V get(K key) {
        Objects.requireNonNull(key);
        int hash = hash(key.hashCode());
        var current = table;
        try {
            for (int slot = hash & current.mask;; slot = (slot + 1) & current.mask) {
                var node = SLOT.getAcquire(current.slots, slot);
                if (node == null) return null;
                if (node == DELETED) continue;
                var entry = (Entry<K, V>) node;
                if (entry.hash != hash) continue;
                var stored = entry.get();
                var value = entry.value.get();
                if (stored != null && value != null && (key == stored || key.equals(stored))) return value;
            }
        } finally { Reference.reachabilityFence(key); }
    }

    /** A transient probe can compare against both stored referents without becoming a retained key. */
    @SuppressWarnings("unchecked")
    V get(WeakCanonicalMap.Lookup<K, V> lookup) {
        int hash = hash(lookup.hash());
        var current = table;
        try {
            for (int slot = hash & current.mask;; slot = (slot + 1) & current.mask) {
                var node = SLOT.getAcquire(current.slots, slot);
                if (node == null) return null;
                if (node == DELETED) continue;
                var entry = (Entry<K, V>) node;
                if (entry.hash != hash) continue;
                var stored = entry.get();
                var value = entry.value.get();
                if (stored != null && value != null && lookup.matches(stored, value)) return value;
            }
        } finally { Reference.reachabilityFence(lookup); }
    }

    V putIfAbsent(K key, V value) { return putIfAbsent(key, value, equality(key)); }

    @SuppressWarnings("unchecked")
    synchronized V putIfAbsent(K key, V value, WeakCanonicalMap.Lookup<K, V> lookup) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        int hash = hash(lookup.hash());
        var current = table;
        int vacant = -1;
        for (int slot = hash & current.mask;; slot = (slot + 1) & current.mask) {
            var node = SLOT.getAcquire(current.slots, slot);
            if (node == null) {
                if (vacant < 0) vacant = slot;
                break;
            }
            if (node == DELETED) {
                if (vacant < 0) vacant = slot;
                continue;
            }
            var entry = (Entry<K, V>) node;
            var stored = entry.get();
            var existing = entry.value.get();
            if (stored == null || existing == null) {
                SLOT.setRelease(current.slots, slot, DELETED);
                current.size--;
                if (vacant < 0) vacant = slot;
            } else if (entry.hash == hash && lookup.matches(stored, existing)) {
                return existing;
            }
        }
        // Keep at least one never-used null slot, also in retired arrays, so readers terminate.
        if ((current.used + 1L) * 3 >= current.slots.length * 2L) {
            int capacity = current.size * 3L < current.slots.length ? current.slots.length
                    : Math.multiplyExact(current.slots.length, 2);
            current = rebuild(current, capacity);
            vacant = emptySlot(current, hash);
        }
        var fresh = new Entry<>(key, value, hash, keyQueue, valueQueue);
        if (SLOT.getAcquire(current.slots, vacant) == null) current.used++;
        current.size++;
        SLOT.setRelease(current.slots, vacant, fresh);
        Reference.reachabilityFence(key);
        Reference.reachabilityFence(value);
        return null;
    }

    void remove(K key, V expected) { remove(equality(key), expected); }

    @SuppressWarnings("unchecked")
    synchronized void remove(WeakCanonicalMap.Lookup<K, V> lookup, V expected) {
        int hash = hash(lookup.hash());
        var current = table;
        for (int slot = hash & current.mask;; slot = (slot + 1) & current.mask) {
            var node = SLOT.getAcquire(current.slots, slot);
            if (node == null) return;
            if (node == DELETED) continue;
            var entry = (Entry<K, V>) node;
            if (entry.hash != hash || entry.value.get() != expected) continue;
            var stored = entry.get();
            if (stored != null && lookup.matches(stored, expected)) {
                SLOT.setRelease(current.slots, slot, DELETED);
                current.size--;
                return;
            }
        }
    }

    static <K, V> WeakCanonicalMap.Lookup<K, V> equality(K key) {
        Objects.requireNonNull(key);
        return new WeakCanonicalMap.Lookup<>() {
            @Override public int hash() { return key.hashCode(); }
            @Override public boolean matches(K stored, V value) { return key == stored || key.equals(stored); }
        };
    }

    /** No monitor acquisition in the common case where both GC queues are empty. */
    void cleanUp() {
        var key = keyQueue.poll();
        var value = valueQueue.poll();
        if (key == null && value == null) return;
        synchronized (this) {
            for (int i = 0; i < 32; i++) {
                var reference = i == 0 ? key : keyQueue.poll();
                if (reference == null) break;
                removeReference(reference);
            }
            for (int i = 0; i < 32; i++) {
                @SuppressWarnings("unchecked") var reference = (ValueReference<K, V>) (i == 0 ? value : valueQueue.poll());
                if (reference == null) break;
                removeReference(reference.owner);
            }
        }
    }

    private void removeReference(Reference<?> reference) {
        var entry = (Entry<?, ?>) reference;
        var current = table;
        for (int slot = entry.hash & current.mask;; slot = (slot + 1) & current.mask) {
            var node = SLOT.getAcquire(current.slots, slot);
            if (node == null) return;
            // A delayed queue event must never delete an equal, newly installed representative.
            if (node == entry) {
                SLOT.setRelease(current.slots, slot, DELETED);
                current.size--;
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Table rebuild(Table previous, int capacity) {
        var replacement = new Table(capacity);
        for (var node : previous.slots) {
            if (node == null || node == DELETED) continue;
            var entry = (Entry<K, V>) node;
            if (entry.get() == null || entry.value.get() == null) continue;
            replacement.slots[emptySlot(replacement, entry.hash)] = entry;
            replacement.size++;
        }
        replacement.used = replacement.size;
        table = replacement; // Volatile publication includes the fully populated replacement.
        return replacement;
    }

    private static int emptySlot(Table table, int hash) {
        int slot = hash & table.mask;
        while (table.slots[slot] != null) slot = (slot + 1) & table.mask;
        return slot;
    }
}
