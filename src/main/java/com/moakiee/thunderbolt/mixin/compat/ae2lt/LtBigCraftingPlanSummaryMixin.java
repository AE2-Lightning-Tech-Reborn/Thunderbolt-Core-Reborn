package com.moakiee.thunderbolt.mixin.compat.ae2lt;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import com.moakiee.thunderbolt.ae2.crafting.Ae2CraftingTreeSummaryBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** LT constructs exact summaries directly, bypassing AE2's fromJob extension hook. */
@Pseudo
@Mixin(targets = "com.moakiee.ae2lt.crafting.big.BigCraftingPlan", remap = false)
public abstract class LtBigCraftingPlanSummaryMixin {
    @Inject(method = "summary", at = @At("RETURN"), require = 1)
    private void thunderbolt$attachCraftingTree(CallbackInfoReturnable<CraftingPlanSummary> callback) {
        Ae2CraftingTreeSummaryBridge.attach(callback.getReturnValue(), (ICraftingPlan) (Object) this);
    }
}
