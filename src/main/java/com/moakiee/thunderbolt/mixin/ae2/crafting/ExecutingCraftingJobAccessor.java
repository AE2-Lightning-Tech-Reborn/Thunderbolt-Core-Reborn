package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;

// AE2 members have no SRG mappings. Namespace generated methods to avoid addon collisions.
@Mixin(value = ExecutingCraftingJob.class, remap = false)
public interface ExecutingCraftingJobAccessor {
    @Accessor("waitingFor")
    ListCraftingInventory thunderbolt$getWaitingFor();

    @Accessor("timeTracker")
    ElapsedTimeTracker thunderbolt$getTimeTracker();

    @Accessor("finalOutput")
    GenericStack thunderbolt$getFinalOutput();

    @Accessor("remainingAmount")
    long thunderbolt$getRemainingAmount();

    @Accessor("remainingAmount")
    void thunderbolt$setRemainingAmount(long remainingAmount);

    @Accessor("link")
    CraftingLink thunderbolt$getLink();

    @Accessor("tasks")
    Map<IPatternDetails, ?> thunderbolt$getTasks();
}
