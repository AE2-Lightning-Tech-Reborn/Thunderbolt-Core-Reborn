package com.moakiee.thunderbolt.mixin.ae2.crafting;

import appeng.menu.AEBaseMenu;
import appeng.menu.me.crafting.CraftAmountMenu;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderBinding;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftAmountMenu.class, remap = false, priority = 1100)
public abstract class TerminalOrderAmountMixin {
    @Inject(method = {"confirm", "gtlcore$confirmLongAmount"}, at = @At("HEAD"), cancellable = true)
    private void thunderbolt$validateQuantitySource(CallbackInfo ci) {
        var menu = (AEBaseMenu) (Object) this;
        var state = (TerminalOrderMenu) this;
        if (menu.isClientSide()) return;
        if (state.thunderbolt$orderRetired()) {
            ci.cancel();
            return;
        }
        var binding = state.thunderbolt$orderBinding();
        if (binding != null && !binding.valid(menu)) {
            TerminalOrderBinding.reject(menu);
            ci.cancel();
        }
    }
}
