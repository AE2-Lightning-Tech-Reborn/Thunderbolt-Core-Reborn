package com.moakiee.thunderbolt.mixin.ae2.key;

import appeng.api.stacks.AEFluidKey;
import com.moakiee.thunderbolt.core.keys.FluidKeyCacheOwner;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import com.moakiee.thunderbolt.core.keys.PlainFluidKeyCache;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Fluid.class)
abstract class FluidKeyCacheMixin implements FluidKeyCacheOwner {
    @Unique private PlainFluidKeyCache thunderbolt$fluidGeneration;
    @Unique private volatile AEFluidKey thunderbolt$plainFluidKey;

    @Override public AEFluidKey thunderbolt$plainFluidKey() { return thunderbolt$plainFluidKey; }

    @Override public synchronized void thunderbolt$publishFluidKey(PlainFluidKeyCache expected, AEFluidKey key) {
        // A native constructor may finish after reset/disable. Never revive the old generation.
        if (!KeyConstructionCache.isCurrent(expected)) return;
        thunderbolt$fluidGeneration = expected;
        thunderbolt$plainFluidKey = key;
    }

    @Override public synchronized void thunderbolt$clearFluidKey(PlainFluidKeyCache expected) {
        if (thunderbolt$fluidGeneration == expected) {
            thunderbolt$plainFluidKey = null;
            thunderbolt$fluidGeneration = null;
        }
    }
}
