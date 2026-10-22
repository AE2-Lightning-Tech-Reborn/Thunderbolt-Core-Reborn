package com.moakiee.thunderbolt.gametest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.moakiee.thunderbolt.core.nbt.NbtCopyOptions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Runs against transformed Minecraft classes; plain JUnit alone cannot prove injection. */
@GameTestHolder("thunderbolt_nbt")
@PrefixGameTestTemplate(false)
public final class NbtCopyGameTests {
    private static final StackWalker WALKER = StackWalker.getInstance();

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void enabledAndDisabledFactoriesUseTheExpectedPaths(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            check(previous, "common config did not enable NBT optimization");
            var mapLeaf = new ObservedTag();
            var source = new CompoundTag(); source.put("leaf", mapLeaf);
            var listLeaf = new ObservedTag();
            var list = new ListTag(); list.add(listLeaf);
            NbtCopyOptions.configure(false);
            source.copy(); list.copy();
            check(mapLeaf.copies == 1 && listLeaf.copies == 1, "disabled path changed Tag.copy dispatch");
            check(mapLeaf.fusedMapCopies == 0 && listLeaf.fusedListCopies == 0, "disabled flag still fused copies");
            NbtCopyOptions.configure(true);
            source.copy(); list.copy();
            check(mapLeaf.copies == 2 && listLeaf.copies == 2, "enabled path copied an element more than once");
            check(mapLeaf.fusedMapCopies == 1, "CompoundTag Mixin did not reach fused collector");
            check(listLeaf.fusedListCopies == 1, "ListTag Mixin did not reach fused collector");
            helper.succeed();
        } finally { NbtCopyOptions.configure(previous); }
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void nestedCopiesSeparateMutableDescendantsAndKeepNativeContainers(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            for (boolean enabled : List.of(false, true)) {
                NbtCopyOptions.configure(enabled);
                var source = row(7);
                var children = new ListTag(); children.add(row(8)); source.put("children", children);
                var values = new ListTag(); values.add(IntTag.valueOf(4)); source.put("values", values);
                source.putByteArray("bytes", new byte[]{1, 2});
                source.putIntArray("ints", new int[]{3, 4});
                source.putLongArray("longs", new long[]{5, 6});
                var copied = source.copy();
                check(source.equals(copied), "copy changed serialized content");
                check(container(copied, Map.class).getClass() == HashMap.class, "copy replaced HashMap");
                var copiedChildren = (ListTag) copied.get("children");
                check(container(copiedChildren, List.class).getClass() == ArrayList.class, "copy replaced ArrayList");
                check(copied.get("value") == source.get("value"), "immutable tag was unnecessarily replaced");
                check(((ListTag) copied.get("values")).get(0) == values.get(0), "value-tag list sharing changed");
                ((CompoundTag) copiedChildren.get(0)).putInt("value", 99);
                copied.getByteArray("bytes")[0] = 9;
                copied.getIntArray("ints")[0] = 9;
                copied.getLongArray("longs")[0] = 9;
                check(((CompoundTag) children.get(0)).getInt("value") == 8, "nested compound leaked mutation");
                check(source.getByteArray("bytes")[0] == 1 && source.getIntArray("ints")[0] == 3
                        && source.getLongArray("longs")[0] == 5, "array mutation leaked into original");
                children.add(row(10));
                check(copiedChildren.size() == 1, "original list mutation leaked into copied list");
                check(new CompoundTag().copy().isEmpty() && new ListTag().copy().isEmpty(), "empty copy changed");
            }
            helper.succeed();
        } finally { NbtCopyOptions.configure(previous); }
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void customContainersKeepTheOriginalCollector(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            NbtCopyOptions.configure(true);
            var mapLeaf = new ObservedTag();
            var source = new CompoundTag();
            var customMap = new LinkedHashMap<String, Tag>(); customMap.put("leaf", mapLeaf);
            replaceContainer(source, Map.class, customMap);
            var listLeaf = new ObservedTag();
            var list = new ListTag(); list.add(listLeaf);
            replaceContainer(list, List.class, new LinkedList<>(List.of(listLeaf)));
            check(source.equals(source.copy()) && list.equals(list.copy()), "custom container copy changed");
            check(mapLeaf.copies == 1 && listLeaf.copies == 1, "custom tag copy was skipped or repeated");
            check(mapLeaf.fusedMapCopies == 0 && listLeaf.fusedListCopies == 0, "custom container entered native fast path");
            helper.succeed();
        } finally { NbtCopyOptions.configure(previous); }
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void throwingTagCopyLeavesNoStateForTheNextOperation(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            NbtCopyOptions.configure(true);
            var leaf = new ObservedTag(); leaf.fail = true;
            var source = new CompoundTag(); source.put("leaf", leaf);
            var list = new ListTag(); list.add(leaf);
            expectFailure(source::copy, leaf.failure); expectFailure(list::copy, leaf.failure);
            leaf.fail = false;
            check(source.equals(source.copy()) && list.equals(list.copy()), "retry retained failed-copy state");
            check(leaf.copies == 4, "retry skipped or repeated a tag");
            helper.succeed();
        } finally { NbtCopyOptions.configure(previous); }
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void seededNestedGraphsMatchDisabledCopies(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            var random = new Random(0x4e425443L);
            for (int sample = 0; sample < 128; sample++) {
                var source = generated(random, 3);
                NbtCopyOptions.configure(false); var baseline = source.copy();
                NbtCopyOptions.configure(true); var candidate = source.copy();
                check(candidate.equals(baseline) && candidate.hashCode() == baseline.hashCode(), "seeded copy mismatch " + sample);
                candidate.putInt("added-after-copy", sample);
                check(!source.contains("added-after-copy") && !baseline.contains("added-after-copy"), "seeded copy alias " + sample);
            }
            helper.succeed();
        } finally { NbtCopyOptions.configure(previous); }
    }

    private static CompoundTag row(int value) { var row = new CompoundTag(); row.putInt("value", value); return row; }

    private static CompoundTag generated(Random random, int depth) {
        var result = new CompoundTag();
        for (int i = 0, count = random.nextInt(7); i < count; i++) {
            String key = "k" + i;
            if (depth > 0 && random.nextBoolean()) {
                if (random.nextBoolean()) result.put(key, generated(random, depth - 1));
                else {
                    var list = new ListTag();
                    for (int j = 0, size = random.nextInt(5); j < size; j++) list.add(generated(random, depth - 1));
                    result.put(key, list);
                }
            } else if (random.nextBoolean()) result.putInt(key, random.nextInt());
            else result.putByteArray(key, new byte[]{(byte) random.nextInt(), (byte) random.nextInt()});
        }
        return result;
    }

    private static Field containerField(Object tag, Class<?> type) {
        for (var field : tag.getClass().getDeclaredFields()) if (field.getType() == type) {
            field.setAccessible(true); return field;
        }
        throw new AssertionError("container field missing: " + type);
    }

    private static Object container(Object tag, Class<?> type) {
        try { return containerField(tag, type).get(tag); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void replaceContainer(Object tag, Class<?> type, Object value) {
        try { containerField(tag, type).set(tag, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void expectFailure(Runnable copy, RuntimeException expected) {
        try { copy.run(); throw new AssertionError("expected Tag.copy failure"); }
        catch (RuntimeException actual) { check(actual == expected, "copy replaced exception"); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class ObservedTag extends CompoundTag {
        int copies, fusedMapCopies, fusedListCopies;
        boolean fail;
        final RuntimeException failure = new IllegalStateException("test copy failure");
        ObservedTag() { putInt("value", 42); }
        @Override public CompoundTag copy() {
            copies++;
            WALKER.forEach(frame -> {
                if (frame.getClassName().endsWith("NbtCopyViews$TransformMap") && frame.getMethodName().equals("copy")) fusedMapCopies++;
                if (frame.getClassName().endsWith("NbtCopyViews$TransformIterable") && frame.getMethodName().equals("copy")) fusedListCopies++;
            });
            if (fail) throw failure;
            return row(42);
        }
    }
}
