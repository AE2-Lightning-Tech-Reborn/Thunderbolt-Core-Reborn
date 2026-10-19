package com.moakiee.thunderbolt.mixin.ae2.channel;

import com.moakiee.thunderbolt.core.channel.BorrowedCapacityCalculator;
import com.moakiee.thunderbolt.core.channel.HighCapacityChannelSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.pathing.ChannelMode;
import appeng.me.GridConnection;

// AE2 classes have no obfuscation mappings in the Forge dev environment — remap must be off.
@Mixin(value = GridConnection.class, remap = false)
public abstract class GridConnectionMaxChannelsMixin implements com.moakiee.thunderbolt.core.channel.PendingChannelConnection {

    @Shadow int lastUsedChannels;

    @Override public void thunderbolt$setPendingChannels(int channels) { lastUsedChannels = channels; }

    @Inject(method = "getMaxChannels", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$use128ChannelsForOwnConnections(CallbackInfoReturnable<Integer> cir) {
        var self = (GridConnection) (Object) this;

        var ownerA = HighCapacityChannelSupport.tryGetOwner(self.a());
        var ownerB = HighCapacityChannelSupport.tryGetOwner(self.b());

        if (!HighCapacityChannelSupport.is128ChannelConnection(ownerA, ownerB)) {
            return;
        }

        var channelMode = self.b().getGrid().getPathingService().getChannelMode();
        if (channelMode == ChannelMode.INFINITE) {
            return;
        }

        cir.setReturnValue(Integer.MAX_VALUE / 2);
    }

}
