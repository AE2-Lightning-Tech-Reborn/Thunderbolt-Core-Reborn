package com.moakiee.thunderbolt.core.crafting.support;

/** Monotonic provider-set revision, including multiple mutations within one server tick. */
public interface CraftingProviderRevision {
    long thunderbolt$getCraftingProviderRevision();
}
