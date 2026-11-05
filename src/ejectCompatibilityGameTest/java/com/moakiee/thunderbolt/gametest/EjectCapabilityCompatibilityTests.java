package com.moakiee.thunderbolt.gametest;

import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.api.eject.EjectEndpoint;
import com.moakiee.thunderbolt.api.eject.EjectOfflinePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("thunderbolt_eject")
@PrefixGameTestTemplate(false)
public final class EjectCapabilityCompatibilityTests {
    private static final BlockPos FIRST = new BlockPos(1, 1, 1);
    private static final BlockPos SECOND = new BlockPos(2, 1, 1);
    private static final BlockPos HOST = new BlockPos(1, 1, 2);

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void interfaceSuperEnchantmentsAndNativeCapabilitiesRemainValid(GameTestHelper helper) {
        var sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(Enchantments.SHARPNESS, 3);
        helper.assertTrue(sword.getEnchantmentLevel(Enchantments.SHARPNESS) == 3,
                "interface-super single-enchantment call lost its vanilla result");
        helper.assertTrue(sword.getAllEnchantments().get(Enchantments.SHARPNESS) == 3,
                "interface-super enchantment-map call lost its vanilla result");
        helper.setBlock(FIRST, Blocks.CHEST);
        var chest = helper.getBlockEntity(FIRST);
        var inventory = chest.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(AssertionError::new);
        helper.assertTrue(inventory.insertItem(0, new ItemStack(Items.STONE, 7), false).isEmpty(),
                "native chest capability no longer accepts items");
        helper.assertTrue(inventory.getStackInSlot(0).getCount() == 7, "native chest capability lost inventory");
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void onlineProjectionAndRecursiveFallbackKeepOwnership(GameTestHelper helper) {
        var first = sign(helper, FIRST);
        sign(helper, SECOND);
        helper.setBlock(HOST, Blocks.CHEST);
        var host = helper.getBlockEntity(HOST);
        var inventory = host.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(AssertionError::new);
        inventory.insertItem(0, new ItemStack(Items.GOLD_INGOT, 11), false);
        var online = endpoint(helper, FIRST, HOST, EjectOfflinePolicy.REJECT);
        try (var registration = EjectCapabilityRegistry.register(online)) {
            helper.assertTrue(first.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(AssertionError::new)
                    == inventory, "EJECT did not retain the host capability identity");
            helper.assertTrue(!first.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.SOUTH).isPresent(),
                    "EJECT leaked to an unregistered face");
        }
        helper.assertTrue(!first.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).isPresent(),
                "closed EJECT registration kept intercepting");
        try (var one = EjectCapabilityRegistry.register(endpoint(helper, FIRST, SECOND, EjectOfflinePolicy.REJECT));
                var two = EjectCapabilityRegistry.register(endpoint(helper, SECOND, FIRST, EjectOfflinePolicy.REJECT))) {
            helper.assertTrue(!first.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).isPresent(),
                    "recursive EJECT projection did not fall back");
        }
        try (var registration = EjectCapabilityRegistry.register(online)) {
            helper.assertTrue(first.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(AssertionError::new)
                    == inventory, "recursive fallback leaked its thread-local guard");
            helper.assertTrue(inventory.getStackInSlot(0).getCount() == 11, "capability lookup changed host inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void offlinePoliciesPreserveItemAndFluidRejection(GameTestHelper helper) {
        var target = sign(helper, FIRST);
        try (var registration = EjectCapabilityRegistry.register(
                endpoint(helper, FIRST, SECOND, EjectOfflinePolicy.REJECT), (server, endpoint) -> null)) {
            var items = target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(AssertionError::new);
            var offered = new ItemStack(Items.DIAMOND, 4);
            helper.assertTrue(items.insertItem(0, offered, false) == offered && offered.getCount() == 4,
                    "offline REJECT consumed offered items");
            helper.assertTrue(items.extractItem(0, 1, false).isEmpty() && items.getSlotLimit(0) == 0,
                    "offline REJECT exposed an inventory");
            var fluids = target.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH).orElseThrow(AssertionError::new);
            helper.assertTrue(fluids.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE) == 0
                    && fluids.drain(1000, IFluidHandler.FluidAction.EXECUTE).isEmpty(),
                    "offline REJECT changed fluid ownership");
            helper.assertTrue(!target.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent(),
                    "unsided lookup unexpectedly used a sided endpoint");
        }
        try (var registration = EjectCapabilityRegistry.register(
                endpoint(helper, FIRST, SECOND, EjectOfflinePolicy.ABSENT), (server, endpoint) -> null)) {
            helper.assertTrue(!target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).isPresent()
                    && !target.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH).isPresent(),
                    "offline ABSENT did not preserve native capability absence");
        }
        helper.succeed();
    }

    private static BlockEntity sign(GameTestHelper helper, BlockPos position) {
        helper.setBlock(position.below(), Blocks.STONE);
        helper.setBlock(position, Blocks.OAK_SIGN);
        return helper.getBlockEntity(position);
    }

    private static EjectEndpoint endpoint(GameTestHelper helper, BlockPos intercept, BlockPos host,
            EjectOfflinePolicy policy) {
        var dimension = helper.getLevel().dimension();
        return new EjectEndpoint(dimension, helper.absolutePos(intercept), Direction.NORTH,
                dimension, helper.absolutePos(host), policy);
    }
}
