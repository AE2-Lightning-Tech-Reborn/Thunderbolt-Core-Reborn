package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;

// AE2 classes have no obfuscation mappings in the Forge dev environment — remap must be off.
//
// Namespaced `thunderbolt$` even though GTLCore currently shadows these members under
// different names (`gtlcore$invokePostChange`, `@Shadow finishJob`). Mixin 0.8.5 merges
// a later accessor of the same name+descriptor by replacing the earlier one silently.
@Mixin(value = CraftingCpuLogic.class, remap = false)
public interface CraftingCpuLogicAccessor {
    @Accessor("job")
    @Nullable
    ExecutingCraftingJob thunderbolt$getJob();

    @Invoker("finishJob")
    void thunderbolt$invokeFinishJob(boolean success);

    @Invoker("postChange")
    void thunderbolt$invokePostChange(AEKey what);
}
