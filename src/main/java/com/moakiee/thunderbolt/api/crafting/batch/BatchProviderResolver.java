package com.moakiee.thunderbolt.api.crafting.batch;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

/**
 * Resolves provider capability independently of a pattern or crafting job.
 *
 * <p>Resolved providers must not retain dispatch contexts. Receive the current job through
 * {@link IBatchCraftingProvider#pushBatch} instead. Capacity, recipe support and preparation
 * remain live checks, not resolution results. A null result means no batch capability.</p>
 */
@FunctionalInterface
public interface BatchProviderResolver extends BatchProviderAdapter {
    @Nullable
    IBatchCraftingProvider resolve(ICraftingProvider provider);

    /**
     * Allows both successful and unsuccessful resolutions to be reused within one CPU tick.
     * Return false if capability identity can change during a tick. Never cache by provider class.
     */
    default boolean cacheResolutionForTick() {
        return true;
    }

    /**
     * Opts into bounded cross-tick caching by provider identity. Both hits and misses must remain
     * valid until AE2 refreshes the provider set. Endpoints must not retain jobs; temporary dispatch
     * state is reset lazily through {@link IBatchCraftingProvider#beginDispatchTick(long)}.
     * Existing resolvers remain tick-scoped. Dynamic capabilities should leave this disabled.
     */
    default boolean cacheResolutionAcrossTicks() {
        return false;
    }

    @Override
    default @Nullable IBatchCraftingProvider adapt(
            ICraftingProvider provider, IPatternDetails pattern, BatchJobView job) {
        return resolve(provider);
    }
}
