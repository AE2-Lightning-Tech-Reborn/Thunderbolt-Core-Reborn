package com.moakiee.thunderbolt.compat.neoeco;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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
        // Allocated dispatch requires a native provider, not an inventory-owned bridge.
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
        public List<IPatternDetails> getAvailablePatterns() {
            return delegate.getAvailablePatterns();
        }

        @Override
        public boolean isBusy() {
            return delegate.isBusy();
        }

        @Override
        public long getBatchCapacity(IPatternDetails details) {
            // Concrete allocation performs recipe, coolant and lane checks.
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
            var prepared = prepareBatch(details, oneCopy, maxCraft, job);
            return prepared == null ? Math.max(0L, maxCraft) : prepared.push(maxCraft);
        }

        @Override
        public com.moakiee.thunderbolt.api.crafting.batch.PreparedBatch prepareBatch(
                IPatternDetails details, KeyCounter[] oneCopy, long maxCraft, BatchJobView job) {
            if (maxCraft <= 0L || job == null) return null;
            var template = copyInputs(oneCopy);
            var prepared = NeoEcoAllocatedApi.prepareAllocated(
                    delegate, details, copyInputs(template), maxCraft, job.level(), job.craftingId());
            long capacity = prepared == null ? 1L : prepared.craftCount();
            return new com.moakiee.thunderbolt.api.crafting.batch.PreparedBatch() {
                private boolean used;
                public long capacity() { return capacity; }
                public long push(long offered) {
                    if (used) throw new IllegalStateException("Batch already submitted");
                    used = true;
                    if (offered <= 0L) return 0L;
                    if (offered > maxCraft) throw new IllegalArgumentException("Offer exceeds preparation");
                    var current = offered < capacity ? NeoEcoAllocatedApi.prepareAllocated(
                            delegate, details, copyInputs(template), offered, job.level(), job.craftingId()) : prepared;
                    if (current != null && current.submit()) return offered - current.craftCount();
                    return pushSingleCopy(details, template, offered, job.craftingId());
                }
            };
        }

        private static KeyCounter[] copyInputs(KeyCounter[] source) {
            var result = new KeyCounter[source.length];
            for (int i = 0; i < source.length; i++) {
                result[i] = new KeyCounter();
                result[i].addAll(source[i]);
            }
            return result;
        }

        private long pushSingleCopy(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft,
                java.util.UUID craftingId) {
            // Later visits use ordinary extraction instead of repeatedly refunding a full batch.
            ordinaryPatterns.add(details);
            // Try ordinary dispatch before blocking; pushPattern needs an owned input copy.
            var inputs = new KeyCounter[oneCopy.length];
            for (int slot = 0; slot < oneCopy.length; slot++) {
                inputs[slot] = new KeyCounter();
                inputs[slot].addAll(oneCopy[slot]);
            }
            return NeoEcoFastPathCompat.pushPattern(delegate, details, inputs, craftingId)
                    ? maxCraft - 1L : maxCraft;
        }
    }
}
