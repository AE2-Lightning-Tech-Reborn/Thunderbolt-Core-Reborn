package com.moakiee.thunderbolt.mixin.ae2.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.client.gui.widgets.CPUSelectionList;
import appeng.menu.me.crafting.CraftingStatusMenu;

import com.moakiee.thunderbolt.core.storage.InfiniteCpuStorageFormat;

// GTLCore also injects this HEAD (unconditionally, priority 1000). Higher priority is
// applied later, so this callback runs after GTLCore and can restore ∞ for Long.MAX_VALUE
// without replacing GTLCore's compact formatting of finite sizes.
@Mixin(value = CPUSelectionList.class, priority = 1100, remap = false)
public abstract class CPUSelectionListStorageMixin {
    @Inject(method = "formatStorage", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$formatInfiniteStorage(
            CraftingStatusMenu.CraftingCpuListEntry cpu,
            CallbackInfoReturnable<String> cir) {
        String formatted = InfiniteCpuStorageFormat.format(cpu.storage());
        if (formatted != null) {
            cir.setReturnValue(formatted);
        }
    }
}
