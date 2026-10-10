package com.moakiee.thunderbolt.mixin.platform.eject;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

// getCapability is declared on CapabilityProvider; the handler narrows to BlockEntity.
// Cancellable priority-2000 HEAD injection gives registered EJECT endpoints ownership.
// Forge's own method has no SRG mapping.
@Mixin(value = CapabilityProvider.class, priority = 2000, remap = false)
public abstract class EjectCapabilityMixin {
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "getCapability(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;", at = @At("HEAD"), cancellable = true)
    private <T> void thunderbolt$interceptEjectCapability(
            Capability<T> capability,
            Direction side,
            CallbackInfoReturnable<LazyOptional<T>> callback) {
        // 注入点位于父类 CapabilityProvider，所有方块实体都会经过 getCapability；
        // 先守卫收窄到 BlockEntity，非方块实体的 capability 查询直接放行。
        if (!((Object) this instanceof BlockEntity blockEntity)) return;
        // Registry-empty is the overwhelmingly common case on this global hot path; check it
        // before paying the ThreadLocal lookup.
        if (EjectEndpointIndex.INSTANCE.isEmpty()
                || THUNDERBOLT_PROXYING.get()
                || EjectEndpointIndex.INSTANCE.isBypassed()
                || !(blockEntity.getLevel() instanceof ServerLevel)
                || side == null) {
            return;
        }

        var entry = EjectEndpointIndex.INSTANCE.lookupByFace(
                blockEntity.getLevel().dimension(), blockEntity.getBlockPos().asLong(), side);
        if (entry == null) return;
        var host = EjectEndpointIndex.INSTANCE.resolveHost(entry);
        if (host == null) {
            if (entry.endpoint().offlinePolicy() == EjectOfflinePolicy.ABSENT) return;
            // Forge 1.20.1: ForgeCapabilities has no *_BLOCK variants (those are 1.20.2+);
            // ITEM_HANDLER/FLUID_HANDLER are the capability tokens for block entities here.
            if (capability == ForgeCapabilities.ITEM_HANDLER) {
                callback.setReturnValue(LazyOptional.of(() -> THUNDERBOLT_REJECTING_ITEM_HANDLER).cast());
            } else if (capability == ForgeCapabilities.FLUID_HANDLER) {
                callback.setReturnValue(LazyOptional.of(() -> THUNDERBOLT_REJECTING_FLUID_HANDLER).cast());
            }
            return;
        }

        var hostLevel = host.getLevel();
        if (hostLevel == null) return;
        var hostPos = host.getBlockPos();
        THUNDERBOLT_PROXYING.set(true);
        try {
            var result = host.getCapability((Capability) capability, side);
            if (result.isPresent()) callback.setReturnValue((LazyOptional<T>) result);
        } finally {
            THUNDERBOLT_PROXYING.remove();
        }
    }
}
