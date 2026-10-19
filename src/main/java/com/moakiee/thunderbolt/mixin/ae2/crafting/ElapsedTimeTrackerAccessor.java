package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;
import com.moakiee.thunderbolt.core.crafting.batch.ContainerOutputTracker;

/** AE2 12 tracks raw amounts and predates container-output accounting. */
@Mixin(value = ElapsedTimeTracker.class, remap = false)
public abstract class ElapsedTimeTrackerAccessor implements ContainerOutputTracker {
    @Shadow @Final @Mutable private long startItemCount;
    @Shadow private long remainingItemCount;

    @Override
    public void thunderbolt$addMaxItems(long amount, AEKeyType type) {
        if (amount <= 0) return;
        startItemCount = startItemCount > Long.MAX_VALUE - amount ? Long.MAX_VALUE : startItemCount + amount;
        remainingItemCount = remainingItemCount > Long.MAX_VALUE - amount ? Long.MAX_VALUE : remainingItemCount + amount;
    }
}
