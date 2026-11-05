package com.moakiee.thunderbolt.compat.useless;

import java.math.BigInteger;
import java.util.List;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.PreparedBatch;
import org.jetbrains.annotations.Nullable;

/** Bridges already allocated CPU batches to Useless's public furnace API. */
public final class UselessBatchAdapter implements BatchProviderResolver {
    private final UselessBatchApi api;

    UselessBatchAdapter(UselessBatchApi api) {
        this.api = api;
    }

    @Override
    public boolean cacheResolutionAcrossTicks() { return true; }

    @Override
    public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        return api.providerType.isInstance(provider) ? new AdaptedProvider(provider, api) : null;
    }

    static final class AdaptedProvider implements IBatchCraftingProvider {
        private final ICraftingProvider delegate;
        private final UselessBatchApi api;

        AdaptedProvider(ICraftingProvider delegate, UselessBatchApi api) {
            this.delegate = delegate;
            this.api = api;
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
            if (isBusy()) return 0L;
            // Discovery only: exact admission needs the CPU's concrete inputs, including fuzzy
            // alternatives. prepareBatch supplies the real bound before bulk extraction.
            return UselessBatchApi.invoke(api.target, delegate) == null ? 1L : Long.MAX_VALUE;
        }

        @Override
        public long pushBatch(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft) {
            if (maxCraft <= 0L) return 0L;
            return prepareBatch(details, oneCopy, maxCraft, null).push(maxCraft);
        }

        @Override
        public PreparedBatch prepareBatch(IPatternDetails details, KeyCounter[] oneCopy,
                                           long maxCraft, BatchJobView job) {
            if (maxCraft <= 0L || isBusy()) return rejected();
            // This preparation lives only inside the current executor call. Never retain a live
            // target or its admission token in the cached provider endpoint.
            var target = UselessBatchApi.invoke(api.target, delegate);
            var prototype = copyInputs(oneCopy);
            if (target == null) return ordinary(details, prototype, 1L);
            var requested = BigInteger.valueOf(maxCraft);
            var capacity = UselessBatchApi.invoke(api.capacity, target, details, prototype, requested);
            var capacityCount = (BigInteger) UselessBatchApi.invoke(api.accepted, capacity);
            if (capacityCount.signum() <= 0) return ordinary(details, prototype, 0L);
            // admit recalculates capacity, including the throttle. Pass the original request and
            // reuse its fixed-count token at commit so the preflight does not apply the scale again.
            var batch = UselessBatchApi.invoke(api.admit, target, details, prototype, requested, null);
            if (batch == null) return ordinary(details, prototype, 0L);
            var count = (BigInteger) UselessBatchApi.invoke(api.count, batch);
            if (count.signum() <= 0 || count.compareTo(requested) > 0) return rejected();
            long accepted = count.longValueExact();
            return new PreparedBatch() {
                private boolean submitted;
                @Override public long capacity() { return accepted; }
                @Override public long push(long offered) {
                    if (submitted) throw new IllegalStateException("Useless batch already submitted");
                    submitted = true;
                    if (offered <= 0) return 0L;
                    if (offered < accepted) {
                        // CPU energy limits or provider balancing may shrink the offer. Never
                        // commit a credential for more copies than the CPU actually reserved.
                        return prepareBatch(details, prototype, offered, job).push(offered);
                    }
                    // Only this private prototype is consumed. Expected outputs still return
                    // through ME storage and the CPU's normal waiting-for accounting.
                    if ((boolean) UselessBatchApi.invoke(api.commit, batch, (Object) prototype)) {
                        return offered - accepted;
                    }
                    return pushOrdinary(details, prototype, offered);
                }
            };
        }

        private PreparedBatch ordinary(IPatternDetails details, KeyCounter[] oneCopy, long capacity) {
            return new PreparedBatch() {
                @Override public long capacity() { return capacity; }
                @Override public long push(long offered) { return pushOrdinary(details, oneCopy, offered); }
            };
        }

        private long pushOrdinary(IPatternDetails details, KeyCounter[] oneCopy, long offered) {
            if (offered <= 0L) return 0L;
            // A temporary capacity/admission/commit rejection never downgrades this pattern.
            // A fresh copy protects both the prepared prototype and the CPU's borrowed template.
            return delegate.pushPattern(details, copyInputs(oneCopy)) ? offered - 1L : offered;
        }

        private static PreparedBatch rejected() {
            return new PreparedBatch() {
                @Override public long capacity() { return 0L; }
                @Override public long push(long offered) { return Math.max(0L, offered); }
            };
        }

        private static KeyCounter[] copyInputs(KeyCounter[] template) {
            var copy = new KeyCounter[template.length];
            for (int slot = 0; slot < template.length; slot++) {
                copy[slot] = new KeyCounter();
                copy[slot].addAll(template[slot]);
            }
            return copy;
        }
    }
}
