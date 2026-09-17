package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.IdentityHashMap;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;

/** CPU-owned, tick-scoped capability cache. Never stores job or pattern contexts. */
final class BatchProviderResolutionCache {
    private final IdentityHashMap<BatchProviderResolver,
            IdentityHashMap<ICraftingProvider, IBatchCraftingProvider>> resolved = new IdentityHashMap<>();

    @Nullable
    IBatchCraftingProvider resolve(ICraftingProvider provider, IPatternDetails pattern,
                                  BatchJobView job, @Nullable BatchProviderAdapter adapter) {
        if (provider instanceof IBatchCraftingProvider nativeBatch) return nativeBatch;
        if (adapter == null) return null;
        // Existing pattern/job-aware integrations retain their per-dispatch semantics.
        if (!(adapter instanceof BatchProviderResolver resolver)) {
            return adapter.adapt(provider, pattern, job);
        }
        if (!resolver.cacheResolutionForTick()) return resolver.resolve(provider);
        var providers = resolved.computeIfAbsent(resolver, ignored -> new IdentityHashMap<>());
        // containsKey is intentional: unsupported providers are cached too.
        if (!providers.containsKey(provider)) {
            providers.put(provider, resolver.resolve(provider));
        }
        return providers.get(provider);
    }

    void clear() {
        resolved.clear();
    }
}
