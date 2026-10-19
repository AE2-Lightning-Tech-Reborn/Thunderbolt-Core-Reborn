package com.moakiee.thunderbolt.mixin.compatfixture;
import java.util.concurrent.Future;
import appeng.api.networking.crafting.*;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.moakiee.thunderbolt.gametest.CompatTests;
import org.spongepowered.asm.mixin.*;
@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class LongAmountMenuMixin {
    @Shadow private Future<ICraftingPlan> job;
    public boolean gtlcore$planLongAmountJob(AEKey key, long amount, CalculationStrategy strategy) {
        if (job != null) job.cancel(true);
        job = CompatTests.service.beginCraftingCalculation(null, CompatTests.requester, key, amount, strategy);
        return true;
    }
}
