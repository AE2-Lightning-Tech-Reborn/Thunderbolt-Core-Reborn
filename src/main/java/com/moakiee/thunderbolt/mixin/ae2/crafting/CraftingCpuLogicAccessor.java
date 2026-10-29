package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;

// AE2 members have no SRG mappings. Namespace generated methods to avoid addon collisions.
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
