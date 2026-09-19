package com.moakiee.thunderbolt.core.keys;

/** Generation token for publication/teardown; never read on ordinary key hits. */
public final class PlainFluidKeyCache {
    final Object generation;
    final FluidKeyCacheOwner owner;

    PlainFluidKeyCache(Object generation, FluidKeyCacheOwner owner) {
        this.generation = generation;
        this.owner = owner;
    }
}
