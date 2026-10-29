package com.moakiee.thunderbolt.compat.neoeco;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.api.crafting.batch.PreparedBatch;

/** CPU-owned allocation and settlement through NeoECO's public facade and ordinary parallel contract. */
public final class NeoEcoFastPathBatchAdapter implements BatchProviderResolver {
    private final @Nullable NeoEcoAllocatedApi api;

    public NeoEcoFastPathBatchAdapter() { this(null); }
    NeoEcoFastPathBatchAdapter(@Nullable NeoEcoAllocatedApi api) { this.api = api; }

    @Override
    public boolean cacheResolutionAcrossTicks() { return false; }

    @Override
    public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        var resolved = api == null ? NeoEcoAllocatedApi.forProvider(provider) : api;
        if (resolved == null) return null;
        if (resolved.supports(provider)) return new AdaptedProvider(provider, resolved);
        try { return resolved.parallel(provider) == null ? null : new AdaptedProvider(provider, resolved); }
        catch (RuntimeException unavailable) { return null; }
    }

    static final class AdaptedProvider implements IBatchCraftingProvider {
        private final ICraftingProvider delegate;
        private final NeoEcoAllocatedApi api;
        private final Set<IPatternDetails> ordinaryPatterns =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private long dispatchTick = Long.MIN_VALUE;

        AdaptedProvider(ICraftingProvider delegate, NeoEcoAllocatedApi api) {
            this.delegate = delegate;
            this.api = api;
        }

        @Override
        public void beginDispatchTick(long tick) {
            if (dispatchTick == tick) return;
            dispatchTick = tick;
            ordinaryPatterns.clear();
        }

        @Override public List<IPatternDetails> getAvailablePatterns() { return delegate.getAvailablePatterns(); }
        @Override public boolean isBusy() { return delegate.isBusy(); }

        @Override
        public long getBatchCapacity(IPatternDetails details) {
            if (delegate.isBusy()) return 0L;
            return ordinaryPatterns.contains(details) ? 1L : Long.MAX_VALUE;
        }

        @Override
        public @Nullable PreparedBatch prepareBatch(IPatternDetails details, KeyCounter[] oneCopy,
                long maxCraft, @Nullable BatchJobView job) {
            if (maxCraft <= 0 || job == null || delegate.isBusy()) return prepared(0, offered -> offered);
            // The live job view is borrowed. Deferred submission captures only its values.
            var level = job.level();
            var craftingId = job.craftingId();
            var inputs = NeoEcoBatchApi.cloneInputs(oneCopy, 1);
            if (!ordinaryPatterns.contains(details)) {
                var allocation = api.prepareAllocated(delegate, details, inputs, maxCraft, level, craftingId);
                if (allocation != null) {
                    long capacity = validCount(allocation, maxCraft);
                    return prepared(capacity, offered -> {
                        // Energy/provider balancing can reduce the offer after admission.
                        NeoEcoAllocatedApi.Prepared bounded = allocation;
                        long copies = capacity;
                        if (offered != capacity) {
                            try {
                                bounded = api.prepareAllocated(delegate, details, inputs, offered, level, craftingId);
                                if (bounded == null) return offered;
                                copies = validCount(bounded, offered);
                            } catch (RuntimeException unavailable) {
                                return offered; // Preparation has no ownership.
                            }
                        }
                        try {
                            if (bounded.submit()) return offered - copies;
                        } catch (NeoEcoAllocatedApi.RejectedBatchException rejected) {
                            return offered; // The CPU still owns every copy after facade rollback.
                        }
                        // Uncertainty propagates to BatchExecutor: retain inputs and stop the job.
                        return pushSingleCopy(details, inputs, offered, craftingId);
                    });
                }
                var parallel = api.parallel(delegate);
                if (parallel != null && details.supportsPushInputsToExternalInventory()) {
                    long capacity = Math.min(maxCraft, api.parallelCapacity(parallel));
                    if (capacity > 0) return prepared(capacity, offered -> {
                        long copies;
                        try { copies = Math.min(offered, api.parallelCapacity(parallel)); }
                        catch (RuntimeException unavailable) { return offered; }
                        if (copies <= 0) return offered;
                        return api.pushParallel(parallel, details, inputs, copies, craftingId)
                                ? offered - copies : offered;
                    });
                }
            }
            ordinaryPatterns.add(details);
            return prepared(1, offered -> delegate.isBusy() ? offered
                    : pushSingleCopy(details, inputs, offered, craftingId));
        }

        @Override
        public long pushBatch(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft) {
            // Allocated FastPath needs a live job; retain ownership for context-free callers.
            return Math.max(0, maxCraft);
        }

        @Override
        public long pushBatch(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft, @Nullable BatchJobView job) {
            if (maxCraft <= 0) return 0;
            if (job == null) return maxCraft;
            var batch = prepareBatch(details, oneCopy, maxCraft, job);
            long offered = Math.min(maxCraft, batch.capacity());
            return offered <= 0 ? maxCraft : maxCraft - offered + batch.push(offered);
        }

        private long pushSingleCopy(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft,
                @Nullable UUID craftingId) {
            ordinaryPatterns.add(details);
            return NeoEcoFastPathCompat.pushPattern(delegate, details, NeoEcoBatchApi.cloneInputs(oneCopy, 1), craftingId)
                    ? maxCraft - 1 : maxCraft;
        }

        private static long validCount(NeoEcoAllocatedApi.Prepared allocation, long offered) {
            long count = allocation.craftCount();
            if (count <= 0 || count > offered) throw new IllegalArgumentException("Invalid NeoECO allocated count");
            return count;
        }
    }

    private static PreparedBatch prepared(long capacity, java.util.function.LongUnaryOperator dispatch) {
        return new PreparedBatch() {
            private boolean used;
            @Override public long capacity() { return capacity; }
            @Override public long push(long offered) {
                if (used || offered <= 0 || offered > capacity) throw new IllegalStateException("Invalid prepared NeoECO batch");
                used = true;
                return dispatch.applyAsLong(offered);
            }
        };
    }
}
