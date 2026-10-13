package com.moakiee.thunderbolt.api.crafting.batch;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

/**
 * Per-dispatch adaptation; null declines without transferring ownership. Native providers win.
 * Use {@link BatchProviderAdapters} for global registration; an explicit non-null adapter replaces
 * global lookup for that call. Implement {@link BatchProviderResolver} only for context-free,
 * cacheable resolution. Never cache a job-bound wrapper across jobs.
 */
@FunctionalInterface
public interface BatchProviderAdapter {
    @Nullable
    IBatchCraftingProvider adapt(ICraftingProvider provider, IPatternDetails pattern, BatchJobView job);
}
