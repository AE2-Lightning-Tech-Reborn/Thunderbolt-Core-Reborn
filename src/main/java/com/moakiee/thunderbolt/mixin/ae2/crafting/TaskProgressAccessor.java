package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Namespaced `thunderbolt$` to avoid colliding with GTLCore's
// ExecutingCraftingJobTaskProgressAccessor, which defines getValue/setValue with identical
// descriptors on this inner class. See ExecutingCraftingJobAccessor for the full rationale.
@Mixin(targets = "appeng.crafting.execution.ExecutingCraftingJob$TaskProgress", remap = false)
public interface TaskProgressAccessor {
    @Accessor("value")
    long thunderbolt$getValue();

    @Accessor("value")
    void thunderbolt$setValue(long value);
}
