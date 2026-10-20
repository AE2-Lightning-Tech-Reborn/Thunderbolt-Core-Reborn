package com.moakiee.thunderbolt.compat.neoeco;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.api.crafting.batch.PreparedBatch;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Forge NeoECO's native capacity and ordinary-parallel protocols, with CPU-owned extraction. */
final class NeoEcoBatchAdapter implements BatchProviderResolver {
    private final NeoEcoBatchApi api;

    NeoEcoBatchAdapter(NeoEcoBatchApi api) { this.api = api; }

    @Override
    public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        if (api.hasCapacity(provider)) return new Endpoint(provider);
        try { return api.parallel(provider) != null ? new Endpoint(provider) : null; }
        catch (RuntimeException unavailable) { return null; }
    }

    // Parallel registrations may change without an AE2 provider-set refresh; resolution is tick-local.
    private final class Endpoint implements IBatchCraftingProvider {
        private final ICraftingProvider delegate;
        private final Set<IPatternDetails> ordinary = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<IPatternDetails> stopped = Collections.newSetFromMap(new IdentityHashMap<>());
        private long tick = Long.MIN_VALUE;

        private Endpoint(ICraftingProvider delegate) { this.delegate = delegate; }
        @Override public List<IPatternDetails> getAvailablePatterns() { return delegate.getAvailablePatterns(); }
        @Override public boolean isBusy() { return delegate.isBusy(); }
        @Override public void beginDispatchTick(long now) {
            if (tick == now) return;
            tick = now;
            ordinary.clear();
            stopped.clear();
        }
        @Override public long getBatchCapacity(IPatternDetails details) {
            return stopped.contains(details) || delegate.isBusy() ? 0 : ordinary.contains(details) ? 1 : Long.MAX_VALUE;
        }

        @Override public PreparedBatch prepareBatch(IPatternDetails pattern, KeyCounter[] inputs,
                long maxCraft, BatchJobView job) {
            if (maxCraft <= 0 || stopped.contains(pattern) || delegate.isBusy()) return prepared(0, count -> count);
            try {
                if (!ordinary.contains(pattern)) {
                    var context = api.context(pattern, inputs, job.level(), job.craftingId());
                    if (context != null) {
                        if (api.hasCapacity(delegate)) {
                            long count = Math.min(Math.min(maxCraft, context.limit()), Math.max(0, api.capacity(delegate, context.value())));
                            if (count > 0) return capacityBatch(pattern, context, count);
                        }
                        var parallel = api.parallel(delegate);
                        if (parallel != null && pattern.supportsPushInputsToExternalInventory()) {
                            long count = Math.min(Math.min(maxCraft, context.limit()), Math.max(0, api.parallelCapacity(parallel)));
                            if (count > 0) return prepared(count, offered -> {
                                long live;
                                try { live = Math.min(offered, Math.max(0, api.parallelCapacity(parallel))); }
                                catch (RuntimeException unavailable) { return offered; }
                                if (live <= 0) return offered;
                                return settle(pattern, offered, api.pushParallel(parallel, pattern,
                                        context.inputs(), live, job.craftingId()));
                            });
                        }
                    }
                }
            } catch (RuntimeException unavailable) {
                // Preparation has no input ownership; retain the ordinary single-copy route.
            }
            ordinary.add(pattern);
            var owned = NeoEcoBatchApi.cloneInputs(inputs, 1);
            var craftingId = job.craftingId();
            return prepared(1, offered -> delegate.isBusy() ? offered
                    : NeoEcoFastPathCompat.pushPattern(delegate, pattern, owned, craftingId) ? offered - 1 : offered);
        }

        private PreparedBatch capacityBatch(IPatternDetails pattern, NeoEcoBatchApi.Context context, long count) {
            return prepared(count, offered -> {
                long live;
                try { live = Math.min(offered, Math.max(0, api.capacity(delegate, context.value()))); }
                catch (RuntimeException unavailable) { return offered; }
                if (live <= 0) return offered;
                return settle(pattern, offered, api.push(delegate, context.value(), live));
            });
        }

        private long settle(IPatternDetails pattern, long offered, NeoEcoBatchApi.Receipt receipt) {
            if (!receipt.canContinue()) stopped.add(pattern);
            return offered - receipt.accepted();
        }

        @Override public long pushBatch(IPatternDetails pattern, KeyCounter[] inputs, long count) {
            // Without a live level/job, retain the ordinary provider contract and input ownership.
            if (count <= 0 || stopped.contains(pattern) || delegate.isBusy()) return Math.max(0, count);
            return delegate.pushPattern(pattern, NeoEcoBatchApi.cloneInputs(inputs, 1)) ? count - 1 : count;
        }
        @Override public long pushBatch(IPatternDetails pattern, KeyCounter[] inputs, long count, BatchJobView job) {
            if (count <= 0) return 0;
            var batch = prepareBatch(pattern, inputs, count, job);
            long offered = Math.min(count, batch.capacity());
            return offered <= 0 ? count : count - offered + batch.push(offered);
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
