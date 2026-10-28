package com.moakiee.thunderbolt.gametest;

import com.moakiee.thunderbolt.core.nbt.NbtCopyOptions;
import com.moakiee.thunderbolt.mixin.ThunderboltMixinConfigPlugin;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Checks the actual GTO container implementation when LeanObject owns NBT copying. */
@GameTestHolder("thunderbolt_leanobject")
@PrefixGameTestTemplate(false)
public final class NbtCopyCompatibilityGameTests {
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void leanObjectOwnsCopyingWhileNestedCopiesRemainIndependent(GameTestHelper helper) {
        helper.assertTrue(net.minecraftforge.fml.ModList.get().isLoaded("leanobject"), "LeanObject fixture is missing");
        var plugin = new ThunderboltMixinConfigPlugin();
        for (String name : new String[]{"CompoundTag", "ListTag"}) {
            helper.assertTrue(!plugin.shouldApplyMixin("net.minecraft.nbt." + name,
                    "com.moakiee.thunderbolt.mixin.platform.objects." + name + "CopyMixin"),
                    "Thunderbolt must defer NBT copying to LeanObject");
        }
        boolean previous = NbtCopyOptions.enabled();
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                NbtCopyOptions.configure(enabled);
                var source = new CompoundTag();
                var child = new CompoundTag(); child.putInt("value", 7);
                var list = new ListTag(); list.add(child); source.put("children", list);
                source.putByteArray("bytes", new byte[]{1, 2});
                source.putIntArray("ints", new int[]{3, 4});
                source.putLongArray("longs", new long[]{5, 6});
                var copy = source.copy();
                helper.assertTrue(source.equals(copy), "copy changed serialized contents");
                copy.getList("children", 10).getCompound(0).putInt("value", 99);
                copy.getByteArray("bytes")[0] = 9;
                copy.getIntArray("ints")[0] = 9;
                copy.getLongArray("longs")[0] = 9;
                helper.assertTrue(child.getInt("value") == 7, "nested copy aliased its source");
                helper.assertTrue(source.getByteArray("bytes")[0] == 1
                        && source.getIntArray("ints")[0] == 3
                        && source.getLongArray("longs")[0] == 5, "array copy aliased its source");
                list.add(new CompoundTag());
                helper.assertTrue(copy.getList("children", 10).size() == 1, "source mutation changed the copy");
            }
        } finally { NbtCopyOptions.configure(previous); }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void leanObjectCopiesMatchSeededContentWithBothThunderboltFlags(GameTestHelper helper) {
        NbtCopyGameTests.seededNestedGraphsMatchDisabledCopies(helper);
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void leanObjectCopyExceptionsDoNotLeakIntoTheNextOperation(GameTestHelper helper) {
        NbtCopyGameTests.throwingTagCopyLeavesNoStateForTheNextOperation(helper);
    }
}
