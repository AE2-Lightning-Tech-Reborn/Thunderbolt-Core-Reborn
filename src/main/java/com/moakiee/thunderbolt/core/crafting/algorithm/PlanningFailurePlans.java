package com.moakiee.thunderbolt.core.crafting.algorithm;

import java.util.Map;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;

/** Fail-closed plan returned when every configured planning candidate failed. */
public final class PlanningFailurePlans {
    private PlanningFailurePlans() {
    }

    public static CraftingPlan allFailed(AEKey output, long amount) {
        return new CraftingPlan(
                new GenericStack(output, amount),
                0L,
                true,
                false,
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter(),
                it.unimi.dsi.fastutil.objects.Object2LongMaps.emptyMap());
    }
}
