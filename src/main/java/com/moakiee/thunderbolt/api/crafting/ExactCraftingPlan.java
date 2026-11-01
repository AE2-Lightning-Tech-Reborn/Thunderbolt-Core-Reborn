package com.moakiee.thunderbolt.api.crafting;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import net.minecraft.network.chat.Component;

/** An executable exact plan supplies its lossless presentation to the common confirmation menu. */
public interface ExactCraftingPlan extends ICraftingPlan {
    CraftingPlanSummary summary();

    default Component planningError() {
        return Component.empty();
    }
}
