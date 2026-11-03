package com.moakiee.thunderbolt.gametest;

import java.util.function.Consumer;

import com.moakiee.thunderbolt.api.eject.EjectEndpoint;
import com.moakiee.thunderbolt.api.eject.EjectOfflinePolicy;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;

/** Exercises the merged BlockEntity override and native Forge capability dispatch after Mixin. */
@GameTestHolder("thunderbolt")
@PrefixGameTestTemplate(false)
public final class EjectCapabilityGameTests {
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void registeredFaceForwardsItemsAndFluidsWhileOtherFacesStayNative(GameTestHelper helper) {
        var nativeItems = new ItemStackHandler(1);
        var nativeFluids = new FluidTank(1_000);
        var target = blockEntity(helper, new BlockPos(1, 1, 1), nativeItems, nativeFluids);
        var hostItems = new ItemStackHandler(2);
        var hostFluids = new FluidTank(2_000);
        var host = blockEntity(helper, new BlockPos(2, 1, 1), hostItems, hostFluids);
        try (var ignored = EjectEndpointIndex.INSTANCE.register(endpoint(helper, target, host, EjectOfflinePolicy.REJECT),
                (server, value) -> host)) {
            helper.assertTrue(target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(
                    IllegalStateException::new) == hostItems, "EJECT did not own the registered item face");
            helper.assertTrue(target.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH).orElseThrow(
                    IllegalStateException::new) == hostFluids, "EJECT did not own the registered fluid face");
            helper.assertTrue(target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.SOUTH).orElseThrow(
                    IllegalStateException::new) == nativeItems, "unregistered face lost its native provider");
            helper.assertTrue(target.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElseThrow(
                    IllegalStateException::new) == nativeItems, "unsided capability no longer falls through");
        }
        helper.assertTrue(target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElseThrow(
                IllegalStateException::new) == nativeItems, "closed registration did not restore native capabilities");
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void offlineRejectNeverMutatesNativeInventoryOrFluidTank(GameTestHelper helper) {
        var items = new ItemStackHandler(1);
        var fluids = new FluidTank(1_000);
        var target = blockEntity(helper, new BlockPos(1, 1, 1), items, fluids);
        try (var ignored = EjectEndpointIndex.INSTANCE.register(endpoint(helper, target, target, EjectOfflinePolicy.REJECT),
                (server, value) -> null)) {
            var rejectedItems = target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH)
                    .orElseThrow(IllegalStateException::new);
            var rejectedFluids = target.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH)
                    .orElseThrow(IllegalStateException::new);
            var input = new ItemStack(Items.IRON_INGOT, 7);
            helper.assertTrue(rejectedItems.insertItem(0, input, false).getCount() == 7,
                    "offline EJECT accepted item ownership");
            helper.assertTrue(rejectedItems.extractItem(0, 7, false).isEmpty(), "offline EJECT produced items");
            helper.assertTrue(rejectedFluids.fill(new FluidStack(Fluids.WATER, 700), IFluidHandler.FluidAction.EXECUTE) == 0,
                    "offline EJECT accepted fluid ownership");
            helper.assertTrue(rejectedFluids.drain(700, IFluidHandler.FluidAction.EXECUTE).isEmpty(),
                    "offline EJECT produced fluid");
            helper.assertTrue(items.getStackInSlot(0).isEmpty() && fluids.isEmpty(), "offline query changed native storage");
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void absentOfflineAndNestedBypassKeepNativeCapabilityOwnership(GameTestHelper helper) {
        var index = EjectEndpointIndex.INSTANCE;
        var items = new ItemStackHandler(1);
        var target = blockEntity(helper, new BlockPos(1, 1, 1), items, new FluidTank(1_000));
        try (var ignored = index.register(endpoint(helper, target, target, EjectOfflinePolicy.ABSENT),
                (server, value) -> null)) {
            helper.assertTrue(target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH)
                    .orElseThrow(IllegalStateException::new) == items, "ABSENT must preserve the native provider");
        }
        try (var ignored = index.register(endpoint(helper, target, target, EjectOfflinePolicy.REJECT),
                (server, value) -> null)) {
            index.setBypass(true);
            try {
                index.setBypass(true);
                try {
                    helper.assertTrue(target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH)
                            .orElseThrow(IllegalStateException::new) == items, "bypass lost native provider");
                } finally { index.setBypass(false); }
                helper.assertTrue(index.isBypassed(), "inner scope ended the outer bypass");
            } finally { index.setBypass(false); }
            helper.assertTrue(target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH)
                    .orElseThrow(IllegalStateException::new) != items, "bypass state leaked after its scope");
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void selfProjectionCannotRecurseAndGuardClearsAfterFailure(GameTestHelper helper) {
        var items = new ItemStackHandler(1);
        var target = blockEntity(helper, new BlockPos(1, 1, 1), items, new FluidTank(1_000));
        try (var ignored = EjectEndpointIndex.INSTANCE.register(endpoint(helper, target, target, EjectOfflinePolicy.REJECT),
                (server, value) -> target)) {
            helper.assertTrue(target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH)
                    .orElseThrow(IllegalStateException::new) == items, "self projection must reach native provider once");
        }
        var host = new NativeBlockEntity(helper.absolutePos(new BlockPos(2, 1, 1))) {
            @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                throw new IllegalStateException("expected host failure");
            }
        };
        host.setLevel(helper.getLevel());
        try (var ignored = EjectEndpointIndex.INSTANCE.register(endpoint(helper, target, host, EjectOfflinePolicy.REJECT),
                (server, value) -> host)) {
            boolean threw = false;
            try { target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH); }
            catch (IllegalStateException expected) { threw = expected.getMessage().equals("expected host failure"); }
            helper.assertTrue(threw, "host failure was swallowed");
        }
        try (var ignored = EjectEndpointIndex.INSTANCE.register(endpoint(helper, target, target, EjectOfflinePolicy.REJECT),
                (server, value) -> null)) {
            helper.assertTrue(target.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH)
                    .orElseThrow(IllegalStateException::new) != items, "proxy recursion guard leaked after failure");
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void itemStackInterfaceSuperEnchantmentCallsRemainValid(GameTestHelper helper) {
        var stack = new ItemStack(Items.IRON_SWORD);
        helper.assertTrue(stack.getEnchantmentLevel(Enchantments.SHARPNESS) == 0,
                "unenchantened ItemStack returned an enchantment");
        stack.enchant(Enchantments.SHARPNESS, 3);
        helper.assertTrue(stack.getEnchantmentLevel(Enchantments.SHARPNESS) == 3,
                "ItemStack enchantment-level interface call failed");
        helper.assertTrue(stack.getAllEnchantments().getOrDefault(Enchantments.SHARPNESS, 0) == 3,
                "ItemStack enchantment-map interface call failed");
        helper.succeed();
    }

    private static EjectEndpoint endpoint(GameTestHelper helper, BlockEntity target, BlockEntity host,
            EjectOfflinePolicy policy) {
        var dimension = helper.getLevel().dimension();
        return new EjectEndpoint(dimension, target.getBlockPos(), Direction.NORTH,
                dimension, host.getBlockPos(), policy);
    }

    private static NativeBlockEntity blockEntity(GameTestHelper helper, BlockPos relative,
            ItemStackHandler items, FluidTank fluids) {
        var itemCapability = LazyOptional.of(() -> items);
        var fluidCapability = LazyOptional.of(() -> fluids);
        Consumer<AttachCapabilitiesEvent<BlockEntity>> listener = event -> {
            if (!(event.getObject() instanceof NativeBlockEntity)) return;
            event.addCapability(new ResourceLocation("thunderbolt", "eject_test_native"), new ICapabilityProvider() {
                @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    if (capability == ForgeCapabilities.ITEM_HANDLER) return itemCapability.cast();
                    if (capability == ForgeCapabilities.FLUID_HANDLER) return fluidCapability.cast();
                    return LazyOptional.empty();
                }
            });
        };
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity.class, listener);
        try {
            var result = new NativeBlockEntity(helper.absolutePos(relative));
            result.setLevel(helper.getLevel());
            return result;
        } finally { MinecraftForge.EVENT_BUS.unregister(listener); }
    }

    /** Native provider attaches through Forge; this class inherits the actual transformed override. */
    private static class NativeBlockEntity extends BlockEntity {
        NativeBlockEntity(BlockPos pos) { super(BlockEntityType.COMPARATOR, pos, Blocks.COMPARATOR.defaultBlockState()); }
    }
}
