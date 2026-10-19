package com.moakiee.thunderbolt.mixin.ae2.channel;

import com.moakiee.thunderbolt.core.channel.BorrowedCapacityCalculator;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.core.channel.HighCapacityChannelSupport;
import com.moakiee.thunderbolt.core.channel.HighCapacitySubtreeNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.GridNode;
import appeng.me.pathfinding.IPathItem;

// AE2 classes have no obfuscation mappings in the Forge dev environment — remap must be off.
@Mixin(value = GridNode.class, remap = false)
public abstract class GridNodeMaxChannelsMixin implements HighCapacitySubtreeNode {

    // AE2 12 nodes accumulate in usedChannels; connections accumulate in lastUsedChannels.
    @Shadow int usedChannels;

    @Unique
    private boolean thunderbolt$inHighCapacitySubtree;

    @Override
    public boolean thunderbolt$isInHighCapacitySubtree() {
        return thunderbolt$inHighCapacitySubtree;
    }

    @Override
    public void thunderbolt$setUsedChannels(int channels) {
        this.usedChannels = channels;
    }

    /**
     * Propagate the high-capacity-subtree flag down the BFS tree.
     */
    @Inject(method = "setControllerRoute", at = @At("HEAD"))
    private void thunderbolt$propagateSubtreeFlag(IPathItem route, CallbackInfo ci) {
        var parentPathItem = route == null ? null : route.getControllerRoute();
        if (!(parentPathItem instanceof GridNode parent)) {
            thunderbolt$inHighCapacitySubtree = false;
            return;
        }

        var parentOwner = parent.getOwner();
        if (parentOwner instanceof ControllerBlockEntity) {
            thunderbolt$inHighCapacitySubtree = ChannelSourceRegistry.isChannelSource(parentOwner);
        } else if (parent instanceof HighCapacitySubtreeNode marker) {
            thunderbolt$inHighCapacitySubtree = marker.thunderbolt$isInHighCapacitySubtree();
        } else {
            thunderbolt$inHighCapacitySubtree = false;
        }
    }

    @Inject(method = "getMaxChannels", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$capacitySourcesChannelCapacity(CallbackInfoReturnable<Integer> cir) {
        var self = (GridNode) (Object) this;
        var owner = HighCapacityChannelSupport.tryGetOwner(self);

        if (!HighCapacityChannelSupport.is128ChannelOwner(owner)) {
            return;
        }

        if (self.hasFlag(GridFlags.CANNOT_CARRY)) {
            return;
        }

        var channelMode = self.getGrid().getPathingService().getChannelMode();
        if (channelMode == ChannelMode.INFINITE) {
            return;
        }

        if (ChannelSourceRegistry.isChannelSource(owner) || thunderbolt$inHighCapacitySubtree) {
            cir.setReturnValue(Integer.MAX_VALUE / 2);
        }
    }
}
