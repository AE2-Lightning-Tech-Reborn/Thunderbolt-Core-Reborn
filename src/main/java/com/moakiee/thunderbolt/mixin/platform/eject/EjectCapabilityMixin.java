package com.moakiee.thunderbolt.mixin.platform.eject;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.EmptyResourceHandler;

import com.moakiee.thunderbolt.api.eject.EjectOfflinePolicy;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;

// Run before third-party capability interceptors. Once a registered Thunderbolt EJECT endpoint
// supplies a result, the cancellable HEAD injection returns from getCapability immediately and
// lower-priority interceptors cannot replace ownership of that endpoint.
@Mixin(value = BlockCapability.class, priority = 2000)
public abstract class EjectCapabilityMixin<T, C> {
    @Unique
    private static final ThreadLocal<Boolean> THUNDERBOLT_PROXYING =
            ThreadLocal.withInitial(() -> false);

    @SuppressWarnings("unchecked")
    @Inject(method = "getCapability", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$interceptEjectCapability(
            Level level,
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            C context,
            CallbackInfoReturnable<T> callback) {
        // Registry-empty is the overwhelmingly common case on this global hot path; check it
        // before paying the ThreadLocal lookup.
        if (EjectEndpointIndex.INSTANCE.isEmpty()
                || THUNDERBOLT_PROXYING.get()
                || EjectEndpointIndex.INSTANCE.isBypassed()
                || !(level instanceof ServerLevel)
                || !(context instanceof Direction face)) {
            return;
        }

        var entry = EjectEndpointIndex.INSTANCE.lookupByFace(level.dimension(), pos.asLong(), face);
        if (entry == null) return;
        var host = EjectEndpointIndex.INSTANCE.resolveHost(entry);
        if (host == null) {
            if (entry.endpoint().offlinePolicy() == EjectOfflinePolicy.ABSENT) return;
            var capability = (BlockCapability<T, C>) (Object) this;
            if (capability == Capabilities.Item.BLOCK || capability == Capabilities.Fluid.BLOCK) {
                callback.setReturnValue((T) EmptyResourceHandler.instance());
            }
            return;
        }

        var hostLevel = host.getLevel();
        if (hostLevel == null) return;
        var hostPos = host.getBlockPos();
        THUNDERBOLT_PROXYING.set(true);
        try {
            var capability = (BlockCapability<T, C>) (Object) this;
            var result = capability.getCapability(
                    hostLevel,
                    hostPos,
                    hostLevel.getBlockState(hostPos),
                    host,
                    context);
            if (result != null) callback.setReturnValue(result);
        } finally {
            THUNDERBOLT_PROXYING.remove();
        }
    }
}
