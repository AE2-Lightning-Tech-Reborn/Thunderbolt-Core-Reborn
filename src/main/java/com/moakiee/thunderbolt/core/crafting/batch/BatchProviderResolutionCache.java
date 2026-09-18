package com.moakiee.thunderbolt.core.crafting.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import org.jetbrains.annotations.Nullable;

/** CPU-owned, bounded identity cache. It never retains a job or a pattern. */
final class BatchProviderResolutionCache {
    static final int MAX_RESOLVERS = 8;
    static final int MAX_PROVIDERS_PER_RESOLVER = 16_384;
    private final Reference2ObjectLinkedOpenHashMap<BatchProviderResolver,
            Reference2ObjectLinkedOpenHashMap<ICraftingProvider, Entry>> resolved =
            new Reference2ObjectLinkedOpenHashMap<>();
    private long tick = Long.MIN_VALUE;

    void beginTick(long currentTick) {
        if (tick == currentTick) return;
        tick = currentTick;
        resolved.keySet().removeIf(resolver -> !resolver.cacheResolutionAcrossTicks());
    }

    @Nullable
    IBatchCraftingProvider resolve(ICraftingProvider provider, IPatternDetails pattern,
                                  BatchJobView job, @Nullable BatchProviderAdapter adapter) {
        if (provider instanceof IBatchCraftingProvider nativeBatch) return nativeBatch;
        if (adapter == null) return null;
        if (!(adapter instanceof BatchProviderResolver resolver)) return adapter.adapt(provider, pattern, job);
        if (!resolver.cacheResolutionForTick()) return resolver.resolve(provider);
        var providers = resolved.getAndMoveToLast(resolver);
        if (providers == null) {
            if (resolved.size() >= MAX_RESOLVERS) resolved.removeFirst();
            providers = new Reference2ObjectLinkedOpenHashMap<>();
            resolved.put(resolver, providers);
        }
        var entry = providers.getAndMoveToLast(provider);
        if (entry == null) {
            // A non-null entry also represents a cached miss.
            entry = new Entry(resolver.resolve(provider));
            if (providers.size() >= MAX_PROVIDERS_PER_RESOLVER) providers.removeFirst();
            providers.put(provider, entry);
        }
        if (!entry.initialized || entry.tick != tick) {
            if (entry.endpoint != null) entry.endpoint.beginDispatchTick(tick);
            entry.tick = tick;
            entry.initialized = true;
        }
        return entry.endpoint;
    }

    void clear() { resolved.clear(); }

    private static final class Entry {
        private final IBatchCraftingProvider endpoint;
        private long tick;
        private boolean initialized;
        private Entry(@Nullable IBatchCraftingProvider endpoint) { this.endpoint = endpoint; }
    }
}
