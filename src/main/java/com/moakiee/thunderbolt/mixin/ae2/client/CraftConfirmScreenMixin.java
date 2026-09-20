package com.moakiee.thunderbolt.mixin.ae2.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import appeng.client.gui.me.crafting.CraftConfirmScreen;
import net.minecraft.network.chat.Component;

import com.moakiee.thunderbolt.core.crafting.algorithm.menu.CraftingAlgorithmNameMenu;

/** Adds the selected algorithm name to AE2's native crafting-plan title. */
@Mixin(value = CraftConfirmScreen.class, remap = false)
public abstract class CraftConfirmScreenMixin {
    @ModifyArg(
            method = "updateBeforeRender",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/client/gui/me/crafting/CraftConfirmScreen;"
                            + "setTextContent(Ljava/lang/String;"
                            + "Lnet/minecraft/network/chat/Component;)V",
                    ordinal = 0),
            index = 1)
    private Component thunderbolt$appendAlgorithmName(Component title) {
        var menu = ((CraftConfirmScreen) (Object) this).getMenu();
        var report = com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.get(menu.getPlan());
        if (report != null) {
            return appeng.core.localization.GuiText.BytesUsed.text(
                    com.moakiee.thunderbolt.ae2.crafting.ExactAmountFormatter.compact(report.bytes(), 1));
        }
        if (menu instanceof CraftingAlgorithmNameMenu extension) {
            var name = extension.thunderbolt$getCraftingAlgorithmName();
            if (!name.getString().isEmpty()) {
                return Component.translatable(
                        "gui.thunderbolt.crafting_plan.algorithm", title, name);
            }
        }
        return title;
    }

    @ModifyArg(method = "updateBeforeRender", at = @At(value = "INVOKE",
            target = "Lappeng/client/gui/me/crafting/CraftConfirmScreen;"
                    + "setTextContent(Ljava/lang/String;Lnet/minecraft/network/chat/Component;)V", ordinal = 1), index = 1)
    private Component thunderbolt$previewStatus(Component status) {
        var menu = ((CraftConfirmScreen) (Object) this).getMenu();
        return com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.isPreview(menu.getPlan())
                ? Component.translatable("gui.thunderbolt.exact_plan.preview") : status;
    }
}
