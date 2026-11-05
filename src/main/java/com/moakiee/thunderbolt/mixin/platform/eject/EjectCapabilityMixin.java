package com.moakiee.thunderbolt.mixin.platform.eject;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import com.moakiee.thunderbolt.api.eject.EjectOfflinePolicy;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;

// Forge 1.20.1 port: intercept BlockEntity#getCapability(Capability, Direction).
//
// getCapability is declared by Forge's CapabilityProvider, which is also the superclass of
// ItemStack, Entity, Level and LevelChunk. Mixing into CapabilityProvider marks every one of those
// subclasses as "inherits from mixin", and Mixin 0.8.5 then rebinds INVOKESPECIAL interface-super
// calls in *other* mods' mixins on them (SpongePowered/Mixin#504). ElementalCraft's MixinItemStack
// uses IForgeItemStack.super.getEnchantmentLevel/getAllEnchantments, which got rewritten to an
// InterfaceMethodref owned by ItemStack and failed with
// "IncompatibleClassChangeError: ... must be Methodref constant" during world load.
//
// So this mixin targets BlockEntity only and merges an override of getCapability into it
// (BlockEntity does not declare one). Every BlockEntity subclass that delegates to
// super.getCapability now reaches this override before CapabilityProvider, which is the same
// coverage the old HEAD injection had, and the EJECT result still wins over any interceptor
// injected into CapabilityProvider#getCapability. No Thunderbolt mixin touches CapabilityProvider
// any more. getCapability is a Forge method without SRG names, hence remap = false.
@Mixin(value = BlockEntity.class, priority = 2000, remap = false)
public abstract class EjectCapabilityMixin extends CapabilityProvider<BlockEntity> {
    private EjectCapabilityMixin(Class<BlockEntity> baseClass) {
        super(baseClass);
    }

    @Unique
    private static final ThreadLocal<Boolean> THUNDERBOLT_PROXYING =
            ThreadLocal.withInitial(() -> false);

    @Unique
    private static final IItemHandler THUNDERBOLT_REJECTING_ITEM_HANDLER = new IItemHandler() {
        @Override public int getSlots() { return 1; }
        @Override public ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return 0; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    };

    @Unique
    private static final IFluidHandler THUNDERBOLT_REJECTING_FLUID_HANDLER = new IFluidHandler() {
        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
        @Override public int getTankCapacity(int tank) { return 0; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return false; }
        @Override public int fill(FluidStack resource, FluidAction action) { return 0; }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
        @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
    };

    @Override
    @NotNull
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        var intercepted = thunderbolt$interceptEjectCapability(capability, side);
        return intercepted != null ? intercepted : super.getCapability(capability, side);
    }

    /** Returns the EJECT-owned capability for this face, or {@code null} to fall through. */
    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    private <T> LazyOptional<T> thunderbolt$interceptEjectCapability(
            Capability<T> capability, @Nullable Direction side) {
        var blockEntity = (BlockEntity) (Object) this;
        // Registry-empty is the overwhelmingly common case on this global hot path; check it
        // before paying the ThreadLocal lookup.
        if (EjectEndpointIndex.INSTANCE.isEmpty()
                || THUNDERBOLT_PROXYING.get()
                || EjectEndpointIndex.INSTANCE.isBypassed()
                || !(blockEntity.getLevel() instanceof ServerLevel)
                || side == null) {
            return null;
        }

        var entry = EjectEndpointIndex.INSTANCE.lookupByFace(
                blockEntity.getLevel().dimension(), blockEntity.getBlockPos().asLong(), side);
        if (entry == null) return null;
        var host = EjectEndpointIndex.INSTANCE.resolveHost(entry);
        if (host == null) {
            if (entry.endpoint().offlinePolicy() == EjectOfflinePolicy.ABSENT) return null;
            // Forge 1.20.1: ForgeCapabilities has no *_BLOCK variants (those are 1.20.2+);
            // ITEM_HANDLER/FLUID_HANDLER are the capability tokens for block entities here.
            if (capability == ForgeCapabilities.ITEM_HANDLER) {
                return LazyOptional.of(() -> THUNDERBOLT_REJECTING_ITEM_HANDLER).cast();
            } else if (capability == ForgeCapabilities.FLUID_HANDLER) {
                return LazyOptional.of(() -> THUNDERBOLT_REJECTING_FLUID_HANDLER).cast();
            }
            return null;
        }

        if (host.getLevel() == null) return null;
        THUNDERBOLT_PROXYING.set(true);
        try {
            var result = host.getCapability((Capability) capability, side);
            return result.isPresent() ? (LazyOptional<T>) result : null;
        } finally {
            THUNDERBOLT_PROXYING.remove();
        }
    }
}
