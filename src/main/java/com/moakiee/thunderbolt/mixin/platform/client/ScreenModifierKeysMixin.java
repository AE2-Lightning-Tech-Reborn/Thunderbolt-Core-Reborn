package com.moakiee.thunderbolt.mixin.platform.client;

import com.moakiee.thunderbolt.compat.gto.ClientModifierKeys;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Also covers tooltip listeners that query Screen directly, such as MoreMoreLang. */
@Mixin(Screen.class)
public abstract class ScreenModifierKeysMixin {
    @Inject(method = {"hasShiftDown", "hasControlDown", "hasAltDown"},
            at = @At("HEAD"), cancellable = true, require = 3)
    private static void thunderbolt$guardNativeModifierKeys(CallbackInfoReturnable<Boolean> callback) {
        if (!ClientModifierKeys.canPoll()) callback.setReturnValue(false);
    }
}
