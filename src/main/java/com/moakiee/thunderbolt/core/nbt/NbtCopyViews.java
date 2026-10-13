package com.moakiee.thunderbolt.core.nbt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;

import com.google.common.base.Function;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import net.minecraft.nbt.Tag;

/**
 * Lazy copy views retain the supplied transform and native output containers; nothing is cached.
 * Adapted for Java 17 from Thunderbolt-Core-Reborn's object-reuse experiment
 * dc4d9bc919edd26ef737c5cd7b1ef5fbae76439e, LGPL-3.0.
 */
public final class NbtCopyViews {
    private NbtCopyViews() {}

    public static final class TransformIterable implements Iterable<Tag> {
        private final Iterable<Tag> source;
        private final Function<? super Tag, Tag> transform;

        public TransformIterable(Iterable<Tag> source, Function<? super Tag, Tag> transform) {
            this.source = java.util.Objects.requireNonNull(source);
            this.transform = java.util.Objects.requireNonNull(transform);
        }

        @Override public Iterator<Tag> iterator() { return Iterators.transform(source.iterator(), transform); }
        @Override public String toString() { return Iterables.toString(this); }

        public ArrayList<Tag> copy(int expectedSize) {
            var result = new ArrayList<Tag>(expectedSize);
            for (var value : source) result.add(transform.apply(value));
            return result;
        }
    }

    public static final class TransformMap extends ForwardingMap<String, Tag> {
        private final Map<String, Tag> source;
        private final Function<? super Tag, Tag> transform;

        public TransformMap(Map<String, Tag> source, Function<? super Tag, Tag> transform) {
            this.source = java.util.Objects.requireNonNull(source);
            this.transform = java.util.Objects.requireNonNull(transform);
        }

        @Override protected Map<String, Tag> delegate() { return Maps.transformValues(source, transform); }
        @Override public int size() { return source.size(); }
        @Override public boolean isEmpty() { return source.isEmpty(); }

        @Override public void forEach(BiConsumer<? super String, ? super Tag> action) {
            java.util.Objects.requireNonNull(action);
            source.forEach((key, value) -> action.accept(key, transform.apply(value)));
        }

        public HashMap<String, Tag> copy() {
            var result = Maps.<String, Tag>newHashMapWithExpectedSize(source.size());
            source.forEach((key, value) -> result.put(key, transform.apply(value)));
            return result;
        }
    }
}
