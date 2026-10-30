package com.moakiee.thunderbolt.mixin.compatfixture;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.menu.me.crafting.CraftingPlanSummary;
import com.moakiee.thunderbolt.gametest.SummaryCompatibilityTests;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Represents an addon that recognizes its own plan type and returns a custom summary. */
@Mixin(value = CraftingPlanSummary.class, remap = false)
public abstract class CustomSummaryMixin {
    @Inject(method = "fromJob", at = @At("HEAD"), cancellable = true)
    private static void custom(IGrid grid, IActionSource source, ICraftingPlan plan,
            CallbackInfoReturnable<CraftingPlanSummary> cir) {
        if (plan instanceof SummaryCompatibilityTests.CustomPlan custom) cir.setReturnValue(custom.summary());
    }
}
