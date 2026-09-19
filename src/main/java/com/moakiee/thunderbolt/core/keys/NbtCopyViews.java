package com.moakiee.thunderbolt.core.keys;

import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.google.common.base.Function;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Iterators;
import com.google.common.collect.Iterables;
import net.minecraft.nbt.Tag;

/**
 * Short-lived, lazy copy views for native HashMap/ArrayList inputs. Keep the supplied transform;
 * never assume it is Tag::copy. The output is still a normal HashMap/ArrayList, and a replaced
 * view falls back to the general collector. No source or result is cached between copies.
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
        @Override public void forEach(Consumer<? super Tag> action) {
            java.util.Objects.requireNonNull(action);
            Iterable.super.forEach(action);
        }
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
            var result = HashMap.<String, Tag>newHashMap(source.size());
            source.forEach((key, value) -> result.put(key, transform.apply(value)));
            return result;
        }
    }
}
