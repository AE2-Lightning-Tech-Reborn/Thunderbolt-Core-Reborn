package com.moakiee.thunderbolt.compat.useless;

import java.math.BigInteger;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
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
        private final Set<IPatternDetails> ordinaryPatterns =
                Collections.newSetFromMap(new IdentityHashMap<>());

        AdaptedProvider(ICraftingProvider delegate, UselessBatchApi api) {
            this.delegate = delegate;
            this.api = api;
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
            return isBusy() ? 0L : ordinaryPatterns.contains(details) ? 1L : Long.MAX_VALUE;
        }

        @Override
        public long pushBatch(IPatternDetails details, KeyCounter[] oneCopy, long maxCraft) {
            if (maxCraft <= 0L) return 0L;
            // Targets are live machine views: never retain one across dispatches/ticks.
            var target = UselessBatchApi.invoke(api.target, delegate);
            var prototype = copyInputs(oneCopy);
            if (target != null) {
                var requested = BigInteger.valueOf(maxCraft);
                var capacity = UselessBatchApi.invoke(api.capacity, target, details, prototype, requested);
                var capacityCount = (BigInteger) UselessBatchApi.invoke(api.accepted, capacity);
                if (capacityCount.signum() > 0) {
                    // BatchExecutor has already reserved every offered copy. Only the
                    // prototype is consumed here; it refunds the unaccepted copies.
                    // Outputs return through ME storage and normal CPU waiting-for accounting.
                    // admit recalculates capacity, including the runtime throttle. Keep the
                    // original request so an already throttled count is not scaled twice.
                    var batch = UselessBatchApi.invoke(api.admit, target,
                            details, prototype, requested, null);
                    if (batch != null) {
                        var count = (BigInteger) UselessBatchApi.invoke(api.count, batch);
                        if (count.signum() <= 0 || count.compareTo(requested) > 0) {
                            return maxCraft;
                        }
                        long accepted = count.longValueExact();
                        if ((boolean) UselessBatchApi.invoke(api.commit, batch, (Object) prototype)) {
                            return maxCraft - accepted;
                        }
                    }
                }
            }
            ordinaryPatterns.add(details);
            // An unsupported/rejected batch still gets one ordinary attempt. Use a fresh
            // copy so neither API can mutate the executor's borrowed template.
            return delegate.pushPattern(details, copyInputs(oneCopy)) ? maxCraft - 1L : maxCraft;
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
