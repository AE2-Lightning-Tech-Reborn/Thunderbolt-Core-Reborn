package com.moakiee.thunderbolt.gametest;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.moakiee.thunderbolt.config.ThunderboltCommonConfig;
import com.moakiee.thunderbolt.core.nbt.NbtCopyOptions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Exercises the real Forge-transformed NBT classes with the optimization on and off. */
@GameTestHolder("thunderbolt")
@PrefixGameTestTemplate(false)
public final class NbtCopyTests {
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void loadedConfigPublishesCopyOption(GameTestHelper helper) {
        helper.assertTrue(ThunderboltCommonConfig.fastNbtCopies(), "fresh-world default was not enabled");
        helper.assertTrue(NbtCopyOptions.enabled(), "config loading did not publish the copy option");
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void nestedCopiesIsolateCompoundsListsAndArrays(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            for (boolean enabled : new boolean[] {false, true}) {
                NbtCopyOptions.configure(enabled);
                var row = new CompoundTag();
                row.putInt("value", 7);
                row.putByteArray("bytes", new byte[] {1, 2});
                row.putIntArray("ints", new int[] {3, 4});
                row.putLongArray("longs", new long[] {5, 6});
                var rows = new ListTag();
                rows.add(row);
                var root = new CompoundTag();
                root.put("rows", rows);
                var copy = root.copy();
                helper.assertTrue(copy.equals(root), "copy changed nested NBT values");
                var copiedRows = copy.getList("rows", Tag.TAG_COMPOUND);
                var copiedRow = copiedRows.getCompound(0);
                copiedRow.putInt("value", 99);
                copiedRow.getByteArray("bytes")[0] = 99;
                copiedRow.getIntArray("ints")[0] = 99;
                copiedRow.getLongArray("longs")[0] = 99;
                copiedRows.add(new CompoundTag());
                helper.assertTrue(row.getInt("value") == 7 && rows.size() == 1
                        && row.getByteArray("bytes")[0] == 1 && row.getIntArray("ints")[0] == 3
                        && row.getLongArray("longs")[0] == 5, "copy shared mutable NBT with its source");
                row.putString("later", "source only");
                helper.assertTrue(!copiedRow.contains("later"), "source mutation reached an earlier copy");
            }
        } finally {
            NbtCopyOptions.configure(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void valueAndEmptyListsKeepTheirVanillaBehavior(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            for (boolean enabled : new boolean[] {false, true}) {
                NbtCopyOptions.configure(enabled);
                var values = new ListTag();
                var immutable = IntTag.valueOf(37);
                values.add(immutable);
                values.add(IntTag.valueOf(41));
                var copy = values.copy();
                helper.assertTrue(copy.equals(values) && copy.getElementType() == Tag.TAG_INT
                        && copy.get(0) == immutable, "value-tag copy changed type, value or immutable identity");
                copy.remove(0);
                helper.assertTrue(values.size() == 2, "value list reused the source container");
                var empty = new ListTag().copy();
                helper.assertTrue(empty.isEmpty() && empty.getElementType() == Tag.TAG_END,
                        "empty list copy changed its element type");
                helper.assertTrue(new CompoundTag().copy().isEmpty(), "empty compound copy changed values");
            }
        } finally {
            NbtCopyOptions.configure(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void overriddenCopiesRunOncePerEntryAndFailuresStayLocal(GameTestHelper helper) {
        boolean previous = NbtCopyOptions.enabled();
        try {
            for (boolean enabled : new boolean[] {false, true}) {
                NbtCopyOptions.configure(enabled);
                var calls = new AtomicInteger();
                var fail = new AtomicBoolean(true);
                var failure = new IllegalStateException("expected NBT copy failure");
                var custom = new CompoundTag() {
                    @Override
                    public CompoundTag copy() {
                        calls.incrementAndGet();
                        if (fail.get()) throw failure;
                        return super.copy();
                    }
                };
                custom.putInt("value", 17);
                var list = new ListTag();
                list.add(custom);
                list.add(custom);
                var root = new CompoundTag();
                root.put("list", list);
                try {
                    root.copy();
                    helper.fail("copy swallowed a child-tag failure");
                } catch (IllegalStateException caught) {
                    helper.assertTrue(caught == failure, "copy replaced the child-tag exception");
                }
                helper.assertTrue(calls.get() == 1, "failed copy transformed more than the failing entry");
                fail.set(false);
                var copy = root.copy();
                helper.assertTrue(copy.equals(root) && calls.get() == 3,
                        "later copy reused partial state or transformed a child more than once");
                helper.assertTrue(copy.getList("list", Tag.TAG_COMPOUND).get(0)
                        != copy.getList("list", Tag.TAG_COMPOUND).get(1), "separate entries shared a mutable copy");
            }
        } finally {
            NbtCopyOptions.configure(previous);
        }
        helper.succeed();
    }
}
