package com.moakiee.thunderbolt.mixin.compat.ae2lt;

import java.math.BigInteger;

import appeng.menu.me.crafting.CraftAmountMenu;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderActionPacket;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderActions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional private wire adapter; AE2LT owns its quantity implementation and limits. */
@Pseudo
@Mixin(targets = "com.moakiee.ae2lt.network.tianshu.ConfirmBigAmountPacket", remap = false)
public abstract class TerminalOrderBigQuantityPacketMixin implements TerminalOrderActionPacket {
    @Unique private TerminalOrderActions.Stamp thunderbolt$stamp;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void thunderbolt$capture(int containerId, BigInteger amount, boolean missing, boolean autoStart, CallbackInfo ci) {
        thunderbolt$stamp = TerminalOrderActionPacket.captureClient();
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void thunderbolt$write(FriendlyByteBuf out, CallbackInfo ci) {
        if (thunderbolt$stamp != null) thunderbolt$stamp.write(out);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void thunderbolt$read(FriendlyByteBuf in, CallbackInfoReturnable<Object> cir) {
        ((TerminalOrderActionPacket) cir.getReturnValue()).thunderbolt$actionStamp(TerminalOrderActions.read(in));
    }

    @WrapMethod(method = "lambda$handle$0")
    private static void thunderbolt$dispatch(NetworkEvent.Context context, @Coerce Object packet, Operation<Void> original) {
        var player = context.getSender();
        var stamp = ((TerminalOrderActionPacket) packet).thunderbolt$actionStamp();
        // This lambda runs on the server thread, after the original handler enqueues it.
        if (player != null && player.containerMenu instanceof CraftAmountMenu menu && TerminalOrderActions.bound(menu))
            TerminalOrderActions.dispatch(menu, stamp, () -> original.call(context, packet));
        else if (stamp == null) original.call(context, packet);
    }

    @Override public TerminalOrderActions.Stamp thunderbolt$actionStamp() { return thunderbolt$stamp; }
    @Override public void thunderbolt$actionStamp(TerminalOrderActions.Stamp stamp) { thunderbolt$stamp = stamp; }
}
