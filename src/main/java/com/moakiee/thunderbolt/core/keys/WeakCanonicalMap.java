package com.moakiee.thunderbolt.core.keys;

import java.lang.ref.WeakReference;
import java.lang.ref.Reference;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.Function;

/**
 * Identity aliases over a value-equality, weak-key/weak-value interner. Keys must be immutable and identify every
 * representation property that matters to the caller. Identity misses/evictions never decide equality.
 * Factories and validity checks run outside map locks. Equality in consumers must remain value-based:
 * clear(), bypassing this map, and changing validity can all produce distinct equivalent objects.
 * Keeping a value live preserves its canonical entry only if that value also keeps its content key
 * live. If it does not, collecting the key is allowed to evict the entry. K == V and cyclic K/V
 * references are supported: neither level owns either referent strongly.
 */
public final class WeakCanonicalMap<K, V> {
    private final IdentityTable<V> identities;
    private final Function<? super K, ? extends V> factory;
    private final Function<? super V, ?> identityState;

    /** Hash and equality of a transient query; implementations must not invoke hooks under map locks. */
    public interface Lookup<K, V> {
        int hash();
        boolean matches(K storedKey, V value);
    }
    private volatile State<K, V> state = new State<>();

    private static final class State<K, V> {
        final WeakOpenHashMap<K, V> values = new WeakOpenHashMap<>();
        volatile Alias<V> recent;
    }

    public WeakCanonicalMap(IdentityTable<V> identities) {
        this(identities, null);
    }

    /** Optional weak snapshot stamp invalidates identity aliases when a value's owned state changes. */
    public WeakCanonicalMap(IdentityTable<V> identities, Function<? super V, ?> identityState) {
        this.identities = Objects.requireNonNull(identities);
        this.factory = null;
        this.identityState = identityState;
    }

    /** Self-contained form for immutable values: new WeakCanonicalMap<>(key -> createValue(key)). */
    public WeakCanonicalMap(Function<? super K, ? extends V> factory) {
        this.identities = new IdentityTable<>(256);
        this.factory = Objects.requireNonNull(factory);
        this.identityState = null;
    }

    public V get(K key) {
        Objects.requireNonNull(key);
        return get(key, 0, () -> key);
    }

    /** Avoid building/hashing the content key when a stable identity token already hits L1. */
    public V get(Object identity, int variant, Supplier<? extends K> keyFactory) {
        if (factory == null) throw new IllegalStateException("this map needs a per-call creation function");
        var existing = findIdentity(identity, variant);
        return existing != null ? existing : intern(identity, variant, keyFactory.get(), factory, value -> true);
    }

    /** Cheap read only: callers validate any mutable/external metadata before accepting the hit. */
    public V findIdentity(Object identity, int variant) {
        var current = state;
        var recent = current.recent;
        var value = recent != null ? recent.get(current, identity, variant, identityState) : null;
        return value != null ? value : identities.get(current, identity, variant, identityState);
    }

    /** Optional fast path when a caller can prove that this exact value was already validated. */
    public V recentValue() {
        var recent = state.recent;
        return recent != null ? recent.validValue(identityState) : null;
    }

    /** Content lookup, lazy creation, atomic representative selection, then identity alias publication. */
    public V intern(Object identity, int variant, K key, Function<? super K, ? extends V> factory,
                    Predicate<? super V> valid) {
        Objects.requireNonNull(key);
        return intern(identity, variant, WeakOpenHashMap.equality(key), () -> factory.apply(key), value -> key, valid);
    }

    /**
     * Probe by projected content but retain only weak references to a key already owned by the value.
     * The transient lookup is never stored. Its hash/equality must describe the selected representative.
     * Creation, key extraction and validity hooks run outside the writer monitor; lookup.matches must
     * only inspect stable data. This also supports using the value itself as its stored key.
     */
    public V intern(Object identity, int variant, Lookup<K, V> lookup, Supplier<? extends V> create,
                    Function<? super V, ? extends K> storedKey, Predicate<? super V> valid) {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(lookup);
        var current = state;
        current.values.cleanUp();
        try {
            var value = current.values.get(lookup);
            if (value != null && valid.test(value)) {
                remember(current, identity, variant, value);
                return value;
            }
            if (value != null) current.values.remove(lookup, value);
            var created = Objects.requireNonNull(create.get());
            if (!valid.test(created)) return created;
            var key = Objects.requireNonNull(storedKey.apply(created));
            value = created;
            // Bound retries if third-party state repeatedly invalidates representatives. Returning an
            // uncached, valid value is preferable to spinning; consumers retain normal value equality.
            for (int attempt = 0; attempt < 2; attempt++) {
                var previous = current.values.putIfAbsent(key, created, lookup);
                if (previous == null) break;
                if (valid.test(previous)) { value = previous; break; }
                current.values.remove(lookup, previous);
            }
            remember(current, identity, variant, value);
            Reference.reachabilityFence(key);
            return value;
        } finally {
            Reference.reachabilityFence(lookup);
        }
    }

    public void clear() { state = new State<>(); }

    /** Remove queued weak-key/value nodes in bounded batches, including during idle periods. */
    public void cleanUp() { state.values.cleanUp(); }

    private void remember(State<K, V> current, Object identity, int variant, V value) {
        var token = new WeakReference<Object>(identity);
        var stamp = identityState != null ? identityState.apply(value) : null;
        var snapshot = stamp == identity ? token : stamp != null ? new WeakReference<>(stamp) : null;
        var alias = new Alias<>(new WeakReference<>(current), token, new WeakReference<>(value), snapshot, variant);
        identities.put(current, identity, variant, alias);
        current.recent = alias;
    }

    private record Alias<V>(WeakReference<Object> scope, WeakReference<Object> identity,
                            WeakReference<V> value, WeakReference<?> snapshot, int variant) {
        V get(Object owner, Object token, int discriminator, Function<? super V, ?> state) {
            return variant == discriminator && scope.get() == owner && identity.get() == token
                    ? validValue(state) : null;
        }

        V validValue(Function<? super V, ?> state) {
            var live = value.get();
            if (live == null || state == null) return live;
            var expected = snapshot != null ? snapshot.get() : null;
            return expected != null && expected == state.apply(live) ? live : null;
        }
    }

    /** Shared, bounded two-choice L1 with weak scope, identity and value; collisions only cause misses. */
    public static final class IdentityTable<V> {
        private final AtomicReferenceArray<Alias<V>> first;
        private final AtomicReferenceArray<Alias<V>> second;

        public IdentityTable(int capacity) {
            if (capacity <= 0 || Integer.bitCount(capacity) != 1)
                throw new IllegalArgumentException("capacity must be a positive power of two");
            first = new AtomicReferenceArray<>(capacity);
            second = new AtomicReferenceArray<>(capacity);
        }

        private static int hash(Object scope, Object token, int variant) {
            return 31 * (31 * System.identityHashCode(scope) + System.identityHashCode(token)) + variant;
        }

        private int firstSlot(int hash) { return (hash ^ (hash >>> 16)) & (first.length() - 1); }

        private int secondSlot(int hash) {
            // A different full-width mix gives primary collisions independent fallback locations.
            // Two ways at the same index repeatedly evict each other on a third colliding token.
            hash ^= hash >>> 16;
            hash *= 0x7feb352d;
            hash ^= hash >>> 15;
            hash *= 0x846ca68b;
            return (hash ^ (hash >>> 16)) & (second.length() - 1);
        }

        private V get(Object scope, Object token, int variant, Function<? super V, ?> state) {
            int hash = hash(scope, token, variant);
            var alias = first.get(firstSlot(hash));
            var value = alias != null ? alias.get(scope, token, variant, state) : null;
            if (value != null) return value;
            alias = second.get(secondSlot(hash));
            return alias != null ? alias.get(scope, token, variant, state) : null;
        }

        private void put(Object scope, Object token, int variant, Alias<V> alias) {
            int hash = hash(scope, token, variant);
            int slot = firstSlot(hash);
            var existing = first.get(slot);
            if (existing == null || existing.value.get() == null || existing.scope.get() == null
                    || existing.identity.get() == null
                    || (existing.variant == variant && existing.scope.get() == scope && existing.identity.get() == token)) {
                first.set(slot, alias);
            }
            else second.set(secondSlot(hash), alias);
        }
    }
}
