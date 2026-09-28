package com.moakiee.thunderbolt.compat.neoeco;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;


import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;

/** Optional bridge for all crafting CPUs from its allocated batch contract to NeoECO's public FastPath API. */
public final class NeoEcoFastPathBatchAdapter implements BatchProviderResolver {
    @Override
    public boolean cacheResolutionAcrossTicks() { return true; }

    @Override
    public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        // prepareAllocated only accepts native providers. supports() also includes bridges
        // that require NeoECO's inventory-owned transaction and cannot use this contract.
        return NeoEcoAllocatedApi.supports(provider) ? new AdaptedProvider(provider) : null;
    }

    static final class AdaptedProvider implements IBatchCraftingProvider {
        private final ICraftingProvider delegate;
        // Capability identity is stable; ordinary fallback is only valid for its physical tick.
        private final Set<IPatternDetails> ordinaryPatterns =
                Collections.newSetFromMap(new IdentityHashMap<>());

        AdaptedProvider(ICraftingProvider delegate) {
            this.delegate = delegate;
        }

        private long dispatchTick = Long.MIN_VALUE;

        @Override
        public void beginDispatchTick(long tick) {
            if (dispatchTick == tick) return;
            dispatchTick = tick;
            ordinaryPatterns.clear();
        }

        @Override
        public java.util.List<IPatternDetails> getAvailablePatterns() {
            return delegate.getAvailablePatterns();
        }

        @Override
        public boolean isBusy() {
            return delegate.isBusy();
        }

        @Override
        public long getBatchCapacity(IPatternDetails details) {
            // NeoECO performs the authoritative recipe, cache, coolant and lane checks while
            // preparing the concrete allocated batch below.
            if (delegate.isBusy()) return 0L;
            return ordinaryPatterns.contains(details) ? 1L : Long.MAX_VALUE;
        }

        @Override
        public long pushBatch(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft) {
            // Allocated FastPath requires a current job. Leave ownership with context-free callers.
            return Math.max(0L, maxCraft);
        }

        @Override
        public long pushBatch(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft, BatchJobView job) {
            if (maxCraft <= 0L) {
                return 0L;
            }
            var prepared = NeoEcoAllocatedApi.prepareAllocated(
                    delegate, details, oneCopy, maxCraft, job.level(), job.craftingId());
            if (prepared == null) {
                return pushSingleCopy(details, oneCopy, maxCraft);
            }

            long offered = prepared.craftCount();
            try {
                boolean accepted = prepared.submit();
                if (accepted) {
                    return maxCraft - offered;
                }
            } catch (NeoEcoAllocatedApi.IndeterminateBatchException uncertain) {
                // Ownership may already have crossed the API boundary. Account the prepared part
                // as dispatched so BatchExecutor cannot refund it and duplicate the materials.
                com.mojang.logging.LogUtils.getLogger().error(
                        "[thunderbolt] NeoECO FastPath acceptance is indeterminate; retaining CPU output accounting for {} crafts.",
                        offered,
                        uncertain);
                return maxCraft - offered;
            }
            return pushSingleCopy(details, oneCopy, maxCraft);
        }

        private long pushSingleCopy(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft) {
            // Do not extract and refund a full batch again for every subsequent single copy.
            // Capacity 1 sends later visits through the CPU's ordinary bulk-extraction path.
            ordinaryPatterns.add(details);
            // A declined batch must still get the ordinary attempt before BatchExecutor
            // blocks this provider.
            // pushPattern takes ownership; the batch template itself is borrowed read-only.
            var inputs = new KeyCounter[oneCopy.length];
            for (int slot = 0; slot < oneCopy.length; slot++) {
                inputs[slot] = new KeyCounter();
                inputs[slot].addAll(oneCopy[slot]);
            }
            return delegate.pushPattern(details, inputs) ? maxCraft - 1L : maxCraft;
        }
    }
}
