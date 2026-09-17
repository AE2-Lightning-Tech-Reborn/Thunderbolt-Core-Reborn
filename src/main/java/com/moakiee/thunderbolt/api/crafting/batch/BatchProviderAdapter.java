package com.moakiee.thunderbolt.api.crafting.batch;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

/**
 * Per-dispatch adapter for optional provider APIs.
 *
 * <p>The adapter belongs to the CPU integration that invokes the batch executor. It does not add
 * an interface to the provider and is never consulted by callers that do not pass it explicitly.
 * This keeps provider-owned protocols independent from Thunderbolt's native batch contract.</p>
 *
 * <p>Pattern/job-dependent adapters are evaluated on every dispatch. Implement
 * {@link BatchProviderResolver} for context-free capability resolution that can be cached
 * within a CPU tick. Never cache a job-bound provider wrapper across jobs.</p>
 */
@FunctionalInterface
public interface BatchProviderAdapter {
    @Nullable
    IBatchCraftingProvider adapt(ICraftingProvider provider, IPatternDetails pattern, BatchJobView job);
}
