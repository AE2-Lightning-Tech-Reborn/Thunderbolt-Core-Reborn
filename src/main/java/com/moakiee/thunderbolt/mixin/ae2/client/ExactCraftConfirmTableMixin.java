package com.moakiee.thunderbolt.mixin.ae2.client;

import java.util.List;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanPresentation;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CraftConfirmTableRenderer.class, remap = false)
public abstract class ExactCraftConfirmTableMixin {
    @WrapOperation(method = "getEntryDescription(Lappeng/menu/me/crafting/CraftingPlanSummaryEntry;)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"), require = 3)
    private boolean thunderbolt$describe(List<Component> lines, Object line, Operation<Boolean> original,
            @Local(argsOnly = true) CraftingPlanSummaryEntry entry) {
        if (ExactPlanPresentation.appendExactAmount(entry, (Component) line, false,
                exact -> original.call(lines, exact))) return true;
        return original.call(lines, line);
    }

    @WrapOperation(method = "getEntryTooltip(Lappeng/menu/me/crafting/CraftingPlanSummaryEntry;)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"), require = 3)
    private boolean thunderbolt$tooltip(List<Component> lines, Object line, Operation<Boolean> original,
            @Local(argsOnly = true) CraftingPlanSummaryEntry entry) {
        if (ExactPlanPresentation.appendExactAmount(entry, (Component) line, true,
                exact -> original.call(lines, exact))) return true;
        return original.call(lines, line);
    }
}
