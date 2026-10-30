package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.menu.me.crafting.CraftingPlanSummary;

import com.moakiee.thunderbolt.ae2.crafting.CraftingPlanSummaryAdapter;
import com.moakiee.thunderbolt.ae2.crafting.Ae2CraftingTreeSummaryBridge;

/**
 * Adapts only the native summary's tail for Crafting Tree. Third-party HEAD factories keep the
 * original plan type and receive a tree payload after their summary has been built.
 */
@Mixin(value = CraftingPlanSummary.class, priority = 2000, remap = false)
public abstract class Ae2CraftingTreeCompatibilityMixin {

    // Stay before the constructor invocation: AFTER would share the following ARETURN with
    // Crafting Tree's cancellable TAIL callback and could execute too late on Mixin 0.8.5.
    @ModifyVariable(method = "fromJob", at = @At(value = "INVOKE",
            target = "Lappeng/menu/me/crafting/CraftingPlanSummary;<init>(JZLjava/util/List;)V"),
            argsOnly = true, index = 2)
    private static ICraftingPlan thunderbolt$adaptPlanForAe2CraftingTree(ICraftingPlan job) {
        return CraftingPlanSummaryAdapter.adapt(job);
    }

    @WrapMethod(method = "fromJob")
    private static CraftingPlanSummary thunderbolt$attachTreeToCustomSummary(
            IGrid grid, IActionSource source, ICraftingPlan job,
            Operation<CraftingPlanSummary> original) {
        var summary = original.call(grid, source, job);
        Ae2CraftingTreeSummaryBridge.attach(summary, job);
        return summary;
    }
}
