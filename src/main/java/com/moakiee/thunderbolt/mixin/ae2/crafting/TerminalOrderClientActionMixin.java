package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.function.Consumer;

import appeng.core.sync.packets.GuiDataSyncPacket;
import appeng.menu.AEBaseMenu;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderActions;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = AEBaseMenu.class, remap = false)
public abstract class TerminalOrderClientActionMixin {
    @ModifyArg(method = "sendClientAction(Ljava/lang/String;Ljava/lang/Object;)V", at = @At(value = "INVOKE",
            target = "Lappeng/core/sync/packets/GuiDataSyncPacket;<init>(ILjava/util/function/Consumer;)V"), index = 1)
    private Consumer<FriendlyByteBuf> thunderbolt$stampAction(Consumer<FriendlyByteBuf> nativeData) {
        var stamp = TerminalOrderActions.capture((AEBaseMenu) (Object) this);
        if (stamp == null) return nativeData;
        return out -> { stamp.write(out); nativeData.accept(out); };
    }

    @WrapMethod(method = "receiveClientAction")
    private void thunderbolt$receiveAction(GuiDataSyncPacket packet, Operation<Void> original) {
        var menu = (AEBaseMenu) (Object) this;
        var stamp = TerminalOrderActions.read(packet.getData());
        if (TerminalOrderActions.bound(menu)) {
            TerminalOrderActions.dispatch(menu, stamp, () -> original.call(packet));
        } else if (stamp == null) original.call(packet);
    }
}
