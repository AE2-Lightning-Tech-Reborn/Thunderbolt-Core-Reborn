package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;

// AE2 classes have no obfuscation mappings in the Forge dev environment — remap must be off.
//
// Namespaced `thunderbolt$` to avoid colliding with GTLCore's ElapsedTimeTrackerAccessor, which
// defines invokeAddMaxItems/invokeDecrementItems with identical descriptors. Mixin 0.8.5 resolves
// such a duplicate by replacing the first definition without a warning, which would otherwise make
// ownership order-dependent. The `@Invoker` value names the target method; the Java method name is
// free form.
@Mixin(value = ElapsedTimeTracker.class, remap = false)
public interface ElapsedTimeTrackerAccessor {
    @Invoker("decrementItems")
    void thunderbolt$invokeDecrementItems(long amount, AEKeyType keyType);

    @Invoker("addMaxItems")
    void thunderbolt$invokeAddMaxItems(long amount, AEKeyType keyType);
}
