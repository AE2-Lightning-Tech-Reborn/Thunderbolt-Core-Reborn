package com.moakiee.thunderbolt.mixin.ae2.crafting;

import appeng.core.sync.packets.ConfirmAutoCraftPacket;
import appeng.menu.me.crafting.CraftAmountMenu;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderActionPacket;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderActions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ConfirmAutoCraftPacket.class, remap = false)
public abstract class TerminalOrderQuantityPacketMixin implements TerminalOrderActionPacket {
    @Unique private TerminalOrderActions.Stamp thunderbolt$stamp;

    @WrapOperation(method = "<init>(IZZ)V", at = @At(value = "INVOKE",
            target = "Lappeng/core/sync/packets/ConfirmAutoCraftPacket;configureWrite(Lnet/minecraft/network/FriendlyByteBuf;)V"))
    private void thunderbolt$writeStamp(ConfirmAutoCraftPacket packet, FriendlyByteBuf out, Operation<Void> original) {
        thunderbolt$stamp = TerminalOrderActionPacket.captureClient();
        if (thunderbolt$stamp != null) thunderbolt$stamp.write(out);
        original.call(packet, out);
    }

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void thunderbolt$readStamp(FriendlyByteBuf in, CallbackInfo ci) {
        thunderbolt$stamp = TerminalOrderActions.read(in);
    }

    @WrapMethod(method = "serverPacketData")
    private void thunderbolt$dispatch(ServerPlayer player, Operation<Void> original) {
        if (player.containerMenu instanceof CraftAmountMenu menu && TerminalOrderActions.bound(menu))
            TerminalOrderActions.dispatch(menu, thunderbolt$stamp, () -> original.call(player));
        else if (thunderbolt$stamp == null) original.call(player);
    }

    @Override public TerminalOrderActions.Stamp thunderbolt$actionStamp() { return thunderbolt$stamp; }
    @Override public void thunderbolt$actionStamp(TerminalOrderActions.Stamp stamp) { thunderbolt$stamp = stamp; }
}
