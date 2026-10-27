package com.moakiee.thunderbolt.gametest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.moakiee.thunderbolt.core.nbt.NbtCopyOptions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Actual Radium overwrite and Core wrappers, with both runtime flag values. */
@GameTestHolder("thunderbolt_radium")
@PrefixGameTestTemplate(false)
public final class RadiumNbtCopyGameTests {
    private static final StackWalker WALKER = StackWalker.getInstance();

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void radiumMapAndCoreListKeepTheirCopyPaths(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            check(previous, "Core NBT optimization must retain its default enabled setting");
            var mapLeaf = new ObservedTag();
            var source = new CompoundTag(); source.put("leaf", mapLeaf);
            var listLeaf = new ObservedTag();
            var list = new ListTag(); list.add(listLeaf);
            check(container(source, Map.class).getClass().getName().equals(
                    "it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap"), "Actual Radium allocation mixin is not active");
            check(java.util.Arrays.stream(CompoundTag.class.getDeclaredMethods())
                    .anyMatch(method -> method.getName().contains("thunderbolt$fuseTransform")),
                    "Core wrapper must coexist with the Radium overwrite");
            for (boolean enabled : List.of(false, true)) {
                NbtCopyOptions.configure(enabled);
                var copy = source.copy(); list.copy();
                check(copy.equals(source), "Radium compound copy changed content");
                check(container(copy, Map.class).getClass() == container(source, Map.class).getClass(),
                        "Core must preserve Radium's native map collector");
            }
            check(mapLeaf.copies == 2 && listLeaf.copies == 2, "Each path must copy every tag exactly once");
            check(mapLeaf.fusedMapCopies == 0, "Radium map must keep its own collector");
            check(listLeaf.fusedListCopies == 1, "Core list optimization must remain active only when enabled");
            helper.succeed();
        } finally { NbtCopyOptions.configure(previous); }
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void nestedMutableTagsStayDetachedWithBothFlags(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            for (boolean enabled : List.of(false, true)) {
                NbtCopyOptions.configure(enabled);
                var source = row(7);
                var children = new ListTag(); children.add(row(8)); source.put("children", children);
                source.putByteArray("bytes", new byte[]{1, 2});
                source.putIntArray("ints", new int[]{3, 4});
                source.putLongArray("longs", new long[]{5, 6});
                var copy = source.copy();
                check(copy.equals(source), "Nested copy changed content");
                var copiedChildren = (ListTag) copy.get("children");
                check(container(copiedChildren, List.class).getClass() == ArrayList.class, "List collector changed");
                check(copy.get("value") == source.get("value"), "Immutable tag sharing changed");
                ((CompoundTag) copiedChildren.get(0)).putInt("value", 99);
                copy.getByteArray("bytes")[0] = 9;
                copy.getIntArray("ints")[0] = 9;
                copy.getLongArray("longs")[0] = 9;
                check(((CompoundTag) children.get(0)).getInt("value") == 8, "Nested compound aliases original");
                check(source.getByteArray("bytes")[0] == 1 && source.getIntArray("ints")[0] == 3
                        && source.getLongArray("longs")[0] == 5, "Mutable arrays alias original");
                children.add(row(10));
                check(copiedChildren.size() == 1, "Source mutation leaked into copy");
                check(new CompoundTag().copy().isEmpty() && new ListTag().copy().isEmpty(), "Empty copy changed");
            }
            helper.succeed();
        } finally { NbtCopyOptions.configure(previous); }
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void customContainersRetainCopySemantics(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            NbtCopyOptions.configure(true);
            var mapLeaf = new ObservedTag();
            var source = new CompoundTag();
            var map = new LinkedHashMap<String, Tag>(); map.put("leaf", mapLeaf);
            containerField(source, Map.class).set(source, map);
            var listLeaf = new ObservedTag();
            var list = new ListTag(); list.add(listLeaf);
            containerField(list, List.class).set(list, new LinkedList<>(List.of(listLeaf)));
            check(source.equals(source.copy()) && list.equals(list.copy()), "Custom container copy changed");
            check(mapLeaf.copies == 1 && listLeaf.copies == 1, "Custom tag copy was skipped or repeated");
            check(mapLeaf.fusedMapCopies == 0 && listLeaf.fusedListCopies == 0, "Custom container entered Core collector");
            helper.succeed();
        } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
        finally { NbtCopyOptions.configure(previous); }
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void copyExceptionsPropagateWithoutPoisoningLaterCopies(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            for (boolean enabled : List.of(false, true)) {
                NbtCopyOptions.configure(enabled);
                var leaf = new ObservedTag(); leaf.fail = true;
                var source = new CompoundTag(); source.put("leaf", leaf);
                var list = new ListTag(); list.add(leaf);
                expectFailure(source::copy, leaf.failure); expectFailure(list::copy, leaf.failure);
                leaf.fail = false;
                check(source.equals(source.copy()) && list.equals(list.copy()), "Failed operation left copy state behind");
                check(leaf.copies == 4, "Retry skipped or repeated a tag");
            }
            helper.succeed();
        } finally { NbtCopyOptions.configure(previous); }
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void seededCopiesMatchAndCompressedNbtRemainsDetached(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            var random = new Random(0x52414449554dL);
            for (int sample = 0; sample < 128; sample++) {
                var source = generated(random, 3);
                NbtCopyOptions.configure(false); var baseline = source.copy();
                NbtCopyOptions.configure(true); var candidate = source.copy();
                check(candidate.equals(baseline) && candidate.hashCode() == baseline.hashCode(), "Seeded copy mismatch " + sample);
                candidate.putInt("added-after-copy", sample);
                check(!source.contains("added-after-copy") && !baseline.contains("added-after-copy"), "Seeded alias " + sample);
            }
            var original = row(123);
            original.putByteArray("bytes", new byte[]{4, 5});
            var file = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("radium-nbt-copy.dat").toFile();
            NbtIo.writeCompressed(original.copy(), file);
            var loaded = NbtIo.readCompressed(file);
            check(original.equals(loaded), "Compressed NBT round trip changed values");
            var copied = loaded.copy(); copied.getByteArray("bytes")[0] = 9;
            check(loaded.getByteArray("bytes")[0] == 4 && NbtIo.readCompressed(file).getByteArray("bytes")[0] == 4,
                    "Loaded copy aliases memory or persisted data");
            helper.succeed();
        } catch (IOException failure) { throw new AssertionError(failure); }
        finally { NbtCopyOptions.configure(previous); }
    }

    private static CompoundTag row(int value) { var tag = new CompoundTag(); tag.putInt("value", value); return tag; }

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
        throw new AssertionError("Container field missing: " + type);
    }

    private static Object container(Object tag, Class<?> type) {
        try { return containerField(tag, type).get(tag); }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }

    private static void expectFailure(Runnable action, RuntimeException expected) {
        try { action.run(); throw new AssertionError("Expected Tag.copy failure"); }
        catch (RuntimeException actual) { check(actual == expected, "Exception identity changed"); }
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
