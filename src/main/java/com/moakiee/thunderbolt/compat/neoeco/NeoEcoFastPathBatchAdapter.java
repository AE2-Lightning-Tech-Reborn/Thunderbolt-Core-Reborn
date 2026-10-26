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
import java.util.UUID;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** CPU owns extraction, energy and output accounting; ECO owns accepted work. */
final class NeoEcoFastPathBatchAdapter implements BatchProviderResolver {
    private final NeoEcoAllocatedApi api;

    NeoEcoFastPathBatchAdapter(NeoEcoAllocatedApi api) { this.api = api; }

    @Override public boolean cacheResolutionAcrossTicks() { return true; }
    @Override public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        // In particular, never replace ECO's native large-workstation implementation.
        if (provider instanceof IBatchCraftingProvider nativeBatch) return nativeBatch;
        return api.supports(provider) ? new Endpoint(provider) : null;
    }

    private final class Endpoint implements IBatchCraftingProvider {
        private final ICraftingProvider delegate;
        private final Set<IPatternDetails> ordinary = Collections.newSetFromMap(new IdentityHashMap<>());
        private long tick = Long.MIN_VALUE;

        Endpoint(ICraftingProvider delegate) { this.delegate = delegate; }
        @Override public List<IPatternDetails> getAvailablePatterns() { return delegate.getAvailablePatterns(); }
        @Override public boolean isBusy() { return delegate.isBusy(); }
        @Override public void beginDispatchTick(long now) {
            if (tick == now) return;
            tick = now;
            ordinary.clear();
        }
        @Override public long getBatchCapacity(IPatternDetails pattern) {
            return delegate.isBusy() ? 0 : ordinary.contains(pattern) ? 1 : Long.MAX_VALUE;
        }
        @Override public long pushBatch(IPatternDetails pattern, KeyCounter[] inputs, long requested) {
            return requested; // No borrowed job context means no ownership transfer.
        }
        @Override public long pushBatch(IPatternDetails pattern, KeyCounter[] inputs, long requested, BatchJobView job) {
            if (requested <= 0) return requested;
            var prepared = prepareBatch(pattern, inputs, requested, job);
            long offered = Math.min(requested, prepared.capacity());
            return offered <= 0 ? requested : requested - offered + prepared.push(offered);
        }

        @Override public PreparedBatch prepareBatch(IPatternDetails pattern, KeyCounter[] inputs,
                long requested, BatchJobView job) {
            // A preparation outlives the borrowed view: capture ownership and private inputs now.
            Level level = job.level();
            UUID jobId = job.craftingId();
            KeyCounter[] owned = NeoEcoAllocatedApi.copyInputs(inputs);
            boolean unavailable = requested <= 0 || delegate.isBusy();
            NeoEcoAllocatedApi.Admission admission = unavailable || ordinary.contains(pattern)
                    ? null : api.prepare(delegate, pattern, owned, requested, level, jobId);
            if (!unavailable && admission == null) ordinary.add(pattern);
            long capacity = unavailable ? 0 : admission == null ? 1 : admission.capacity();
            return new PreparedBatch() {
                private boolean used;
                @Override public long capacity() { return capacity; }
                @Override public long push(long offered) {
                    if (used) throw new IllegalStateException("ECO preparation already submitted");
                    if (offered <= 0 || offered > capacity) throw new IllegalArgumentException("Invalid ECO offer");
                    used = true;
                    var current = admission;
                    if (current != null && offered != current.capacity()) {
                        current = api.prepare(delegate, pattern, owned, offered, level, jobId);
                    }
                    if (current != null && current.submit().getAsBoolean()) return offered - current.capacity();
                    // Null preparation or explicit false leaves all inputs CPU-owned. Exceptions
                    // propagate to BatchExecutor's ambiguous-ownership stop; never retry them here.
                    ordinary.add(pattern);
                    return api.pushOrdinary(delegate, pattern, NeoEcoAllocatedApi.copyInputs(owned), jobId)
                            ? offered - 1 : offered;
                }
            };
        }
    }
}
