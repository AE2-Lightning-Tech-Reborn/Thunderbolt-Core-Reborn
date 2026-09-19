package com.moakiee.thunderbolt.core.keys;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.common.base.Function;
import com.google.common.collect.Maps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class NbtCopyViewsTest {
    private static CompoundTag row(int value) {
        var row = new CompoundTag(); row.putInt("value", value); return row;
    }

    @Test void mapIsLazyAndHonorsTheSuppliedTransformExactlyOncePerCopiedEntry() {
        var source = new LinkedHashMap<String, Tag>();
        source.put("a", row(1)); source.put("b", row(2));
        var calls = new ArrayList<Tag>();
        Function<Tag, Tag> transform = value -> { calls.add(value); return value.copy(); };
        var view = new NbtCopyViews.TransformMap(source, transform);
        assertTrue(calls.isEmpty());
        source.put("c", row(3));
        var copied = view.copy();
        assertEquals(HashMap.class, copied.getClass());
        assertEquals(List.copyOf(source.values()), calls);
        assertEquals(source, copied);
        ((CompoundTag) copied.get("a")).putInt("value", 9);
        assertEquals(1, ((CompoundTag) source.get("a")).getInt("value"));
        var custom = new NbtCopyViews.TransformMap(source, value -> IntTag.valueOf(42));
        assertEquals(Map.of("a", IntTag.valueOf(42), "b", IntTag.valueOf(42), "c", IntTag.valueOf(42)), custom.copy());
    }

    @Test void mapViewsAndMutatorsKeepGuavaSemantics() {
        var a = new HashMap<String, Tag>(); a.put("a", row(1)); a.put("b", row(2));
        var b = new HashMap<>(a);
        Function<Tag, Tag> fn = value -> value == null ? null : value.copy();
        var nativeView = Maps.transformValues(a, fn);
        var view = new NbtCopyViews.TransformMap(b, fn);
        assertEquals(nativeView, view);
        assertEquals(nativeView.hashCode(), view.hashCode());
        assertEquals(nativeView.toString(), view.toString());
        assertThrows(UnsupportedOperationException.class, () -> view.put("c", row(3)));
        assertThrows(UnsupportedOperationException.class, () -> view.entrySet().iterator().next().setValue(row(4)));
        assertEquals(nativeView.remove("a"), view.remove("a"));
        a.put("null", null); b.put("null", null);
        assertEquals(nativeView, view);
        var collected = new HashMap<String, Tag>(); view.forEach(collected::put);
        assertEquals(nativeView, collected);
        var keys = view.keySet(); b.put("late", row(5));
        assertTrue(keys.contains("late"));
        keys.remove("late"); assertFalse(b.containsKey("late"));
        var iterator = view.entrySet().iterator(); iterator.next(); iterator.remove();
        assertEquals(1, b.size());
        view.clear(); assertTrue(b.isEmpty());
        assertTrue(view.copy().isEmpty());
    }

    @Test void iterableCopiesKeepOrderLazinessRemovalAndCapacityHints() {
        var source = new ArrayList<Tag>(List.of(row(1), row(2)));
        var calls = new AtomicInteger();
        var view = new NbtCopyViews.TransformIterable(source, value -> {
            calls.incrementAndGet(); return value.copy();
        });
        assertEquals(0, calls.get());
        source.add(row(3));
        var copied = view.copy(1); // Size is a hint, never a truncation bound.
        assertEquals(ArrayList.class, copied.getClass());
        assertEquals(source, copied);
        assertEquals(3, calls.get());
        ((CompoundTag) copied.get(0)).putInt("value", 7);
        assertEquals(1, ((CompoundTag) source.get(0)).getInt("value"));
        var iterator = view.iterator(); iterator.next(); iterator.remove();
        assertEquals(2, source.size());
        var throughCallback = new ArrayList<Tag>(); view.forEach(throughCallback::add);
        assertEquals(source, throughCallback);
        assertEquals(source.toString(), view.toString());
    }

    @Test void exceptionalTransformsDoNotLeaveStateForTheNextCopy() {
        var failure = new IllegalStateException("copy failed");
        var source = new HashMap<String, Tag>(); source.put("one", row(1));
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        Function<Tag, Tag> transform = value -> { if (fail.get()) throw failure; return value.copy(); };
        var map = new NbtCopyViews.TransformMap(source, transform);
        var list = new NbtCopyViews.TransformIterable(new ArrayList<>(source.values()), transform);
        assertSame(failure, assertThrows(IllegalStateException.class, map::copy));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> list.copy(1)));
        fail.set(false);
        assertEquals(source, map.copy());
        assertEquals(List.copyOf(source.values()), list.copy(0));
    }
}
