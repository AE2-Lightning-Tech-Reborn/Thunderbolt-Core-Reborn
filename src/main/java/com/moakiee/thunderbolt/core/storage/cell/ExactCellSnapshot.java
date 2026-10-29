package com.moakiee.thunderbolt.core.storage.cell;

import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

import appeng.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * Detached exact quantities indexed by a clone of the engine's key-to-ID map.
 * Uses fastutil's public clone/lookup/iteration contracts; no hash-table layout is reproduced.
 * The caller transfers ownership of a newly allocated amounts array and wraps this map read-only.
 */
final class ExactCellSnapshot extends AbstractMap<AEKey, BigInteger> {
    private final Object2IntOpenHashMap<AEKey> ids;
    private final BigInteger[] amounts;

    ExactCellSnapshot(Object2IntOpenHashMap<AEKey> source, BigInteger[] amounts) {
        this.ids = source.clone();
        this.amounts = amounts;
    }

    @Override public int size() { return ids.size(); }
    @Override public boolean containsKey(Object key) { return ids.containsKey(key); }

    @Override
    public BigInteger get(Object key) {
        int id = ids.getInt(key);
        return id < 0 ? null : amounts[id];
    }

    @Override
    public void forEach(BiConsumer<? super AEKey, ? super BigInteger> action) {
        Objects.requireNonNull(action);
        var entries = Object2IntMaps.fastIterator(ids);
        while (entries.hasNext()) {
            var entry = entries.next();
            action.accept(entry.getKey(), amounts[entry.getIntValue()]);
        }
    }

    @Override
    public Set<Map.Entry<AEKey, BigInteger>> entrySet() {
        return new AbstractSet<>() {
            @Override public int size() { return ids.size(); }
            @Override public boolean contains(Object candidate) {
                return candidate instanceof Map.Entry<?, ?> entry && entry.getValue() != null
                        && entry.getValue().equals(get(entry.getKey()));
            }
            @Override public Iterator<Map.Entry<AEKey, BigInteger>> iterator() {
                var entries = Object2IntMaps.fastIterator(ids);
                return new Iterator<>() {
                    public boolean hasNext() { return entries.hasNext(); }
                    public Map.Entry<AEKey, BigInteger> next() {
                        var entry = entries.next();
                        return Map.entry(entry.getKey(), amounts[entry.getIntValue()]);
                    }
                };
            }
        };
    }
}
