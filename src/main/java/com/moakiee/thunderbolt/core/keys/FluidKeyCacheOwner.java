package com.moakiee.thunderbolt.core.keys;

import appeng.api.stacks.AEFluidKey;

/** Per-Fluid ordinary key, explicitly detached when its cache generation ends. */
public interface FluidKeyCacheOwner {
    AEFluidKey thunderbolt$plainFluidKey();
    void thunderbolt$publishFluidKey(PlainFluidKeyCache expected, AEFluidKey key);
    void thunderbolt$clearFluidKey(PlainFluidKeyCache expected);
}
