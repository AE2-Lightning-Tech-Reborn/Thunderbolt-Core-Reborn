package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;

// AE2 members have no SRG mappings. Namespace generated methods to avoid addon collisions.
@Mixin(value = ElapsedTimeTracker.class, remap = false)
public interface ElapsedTimeTrackerAccessor {
    @Invoker("decrementItems")
    void thunderbolt$invokeDecrementItems(long amount, AEKeyType keyType);

    @Invoker("addMaxItems")
    void thunderbolt$invokeAddMaxItems(long amount, AEKeyType keyType);
}
