package com.moakiee.thunderbolt.api.crafting.batch;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

/**
 * Per-dispatch adapter for optional provider APIs.
 *
 * <p>Register shared integrations with {@link BatchProviderAdapters}, or pass a private adapter
 * explicitly to the executor. Omitting the argument or passing {@code null} uses the global
 * registry; a non-null adapter replaces global lookup for that call. Native providers take
 * precedence in either case. Returning null declines the provider without taking ownership.</p>
 *
 * <p>Pattern/job-dependent adapters are evaluated on every dispatch. Implement
 * {@link BatchProviderResolver} for context-free capability resolution that can be cached
 * within a CPU tick, or across ticks when explicitly opted in. Never cache a job-bound provider
 * wrapper across jobs.</p>
 */
@FunctionalInterface
public interface BatchProviderAdapter {
    @Nullable
    IBatchCraftingProvider adapt(ICraftingProvider provider, IPatternDetails pattern, BatchJobView job);
}
