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

// AE2 classes have no obfuscation mappings in the Forge dev environment — remap must be off.
//
// Injected members are namespaced with `thunderbolt$`: other AE2 addons bind the same AE2 fields
// (GTLCore's ExecutingCraftingJobAccessor and AE2LT's ae2lt$-prefixed accessor are both on this
// class). Mixin 0.8.5 merges a second mixin's method with an identical name+descriptor by
// *replacing* the first definition silently, so an unqualified name would make ownership depend on
// mixin application order. The `@Accessor` value carries the field name; the method name is free
// form (getter/setter is derived from the descriptor).
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
