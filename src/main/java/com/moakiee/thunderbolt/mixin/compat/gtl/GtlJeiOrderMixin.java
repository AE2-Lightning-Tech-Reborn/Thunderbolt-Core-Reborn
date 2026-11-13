package com.moakiee.thunderbolt.mixin.compat.gtl;

import appeng.api.stacks.AEKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.integration.jei.TerminalJeiOrderClient;
import net.minecraftforge.client.event.ScreenEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep GTLCore's HIGHEST-priority hover/edit/cheat filtering and single cancellation. */
@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.client.forge.ForgeClientEventListener", remap = false)
public abstract class GtlJeiOrderMixin {
    @Inject(method = "onJeiWirelessOrderMousePressed", at = @At("HEAD"), cancellable = true)
    private static void thunderbolt$reserveTerminalModifiers(ScreenEvent.MouseButtonPressed.Pre event, CallbackInfo ci) {
        if (event.getButton() == 2 && TerminalJeiOrderClient.reservesModifiers()) ci.cancel();
    }

    @WrapOperation(method = "lambda$onJeiWirelessOrderMousePressed$1",
            at = @At(value = "INVOKE", target = "Lorg/gtlcore/gtlcore/integration/ae2/wireless/JeiWirelessTerminalOrderPackets;sendRequest(Lappeng/api/stacks/AEKey;)V"))
    private static void thunderbolt$orderFromCurrentMenu(AEKey key, Operation<Void> original) {
        if (!TerminalJeiOrderClient.route(key)) original.call(key);
    }
}
