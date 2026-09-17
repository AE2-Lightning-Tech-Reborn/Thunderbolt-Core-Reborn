package com.moakiee.thunderbolt.mixin.ae2.client;

import java.util.List;
import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanPresentation;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftConfirmTableRenderer.class, remap = false)
public abstract class ExactCraftConfirmTableMixin {
    @Inject(method = "getEntryDescription(Lappeng/menu/me/crafting/CraftingPlanSummaryEntry;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    private void thunderbolt$describe(CraftingPlanSummaryEntry entry, CallbackInfoReturnable<List<Component>> cir) {
        var lines = ExactPlanPresentation.describe(entry, false);
        if (lines != null) cir.setReturnValue(lines);
    }

    @Inject(method = "getEntryTooltip(Lappeng/menu/me/crafting/CraftingPlanSummaryEntry;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    private void thunderbolt$tooltip(CraftingPlanSummaryEntry entry, CallbackInfoReturnable<List<Component>> cir) {
        var lines = ExactPlanPresentation.describe(entry, true);
        if (lines != null) cir.setReturnValue(lines);
    }
}
