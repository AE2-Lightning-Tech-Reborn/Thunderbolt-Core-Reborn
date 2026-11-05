package com.moakiee.thunderbolt.compat.useless;

import java.util.List;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchDispatchMode;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.api.crafting.batch.PreparedBatch;
import org.jetbrains.annotations.Nullable;

/**
 * Routes each pattern to the Useless adapter that can actually execute it.
 *
 * <p>Providers implementing both the public bigint API and the smart-doubling protocol (the
 * multiblock alloy furnace's pattern assembly) would otherwise always take the bigint path. That
 * API only understands raw patterns, so a plan-folded pattern — smart doubling wraps N recipe
 * operations into one push with pre-scaled inputs and outputs — loses its multiplier there: the
 * machine consumes the whole prototype for a single base operation and the CPU waits for the
 * remaining {@code N - 1} results. The scaled adapter folds {@code operationsPerPush} correctly by
 * pushing a pattern scaled by the accepted copy count through the ordinary path, so wrapped
 * patterns must go to it. Raw patterns keep using the bigint API, preserving its live admission
 * and throttling behaviour.</p>
 */
final class UselessHybridBatchAdapter implements BatchProviderResolver {
    private final BatchProviderResolver bigint;
    private final UselessScaledBatchAdapter scaled;

    UselessHybridBatchAdapter(BatchProviderResolver bigint, UselessScaledBatchAdapter scaled) {
        this.bigint = bigint;
        this.scaled = scaled;
    }

    @Override
    public boolean cacheResolutionAcrossTicks() {
        return true;
    }

    @Override
    public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        var bigintEndpoint = bigint.resolve(provider);
        var scaledEndpoint = scaled.resolve(provider);
        if (bigintEndpoint == null) return scaledEndpoint;
        if (scaledEndpoint == null) return bigintEndpoint;
        return new Endpoint(bigintEndpoint, scaledEndpoint);
    }

    private final class Endpoint implements IBatchCraftingProvider {
        private final IBatchCraftingProvider bigintEndpoint;
        private final IBatchCraftingProvider scaledEndpoint;

        private Endpoint(IBatchCraftingProvider bigintEndpoint, IBatchCraftingProvider scaledEndpoint) {
            this.bigintEndpoint = bigintEndpoint;
            this.scaledEndpoint = scaledEndpoint;
        }

        /** Wrapped smart-doubling patterns carry {@code operationsPerPush > 1}. */
        private IBatchCraftingProvider pick(IPatternDetails details) {
            return scaled.operationsPerPush(details) > 1L ? scaledEndpoint : bigintEndpoint;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return bigintEndpoint.getAvailablePatterns();
        }

        @Override
        public boolean isBusy() {
            return bigintEndpoint.isBusy();
        }

        @Override
        public long getBatchCapacity(IPatternDetails details) {
            return pick(details).getBatchCapacity(details);
        }

        @Override
        public BatchDispatchMode getBatchDispatchMode(IPatternDetails details) {
            return pick(details).getBatchDispatchMode(details);
        }

        @Override
        public @Nullable PreparedBatch prepareBatch(IPatternDetails details,
                                                    KeyCounter[] oneCopyTemplate,
                                                    long maxCraft,
                                                    BatchJobView job) {
            return pick(details).prepareBatch(details, oneCopyTemplate, maxCraft, job);
        }

        @Override
        public long pushBatch(IPatternDetails details, KeyCounter[] oneCopyTemplate, long maxCraft) {
            return pick(details).pushBatch(details, oneCopyTemplate, maxCraft);
        }

        @Override
        public long pushBatch(IPatternDetails details,
                              KeyCounter[] oneCopyTemplate,
                              long maxCraft,
                              BatchJobView job) {
            return pick(details).pushBatch(details, oneCopyTemplate, maxCraft, job);
        }

        @Override
        public boolean supportsSharedBatchInputs() {
            return bigintEndpoint.supportsSharedBatchInputs() || scaledEndpoint.supportsSharedBatchInputs();
        }

        @Override
        public void beginDispatchTick(long tick) {
            bigintEndpoint.beginDispatchTick(tick);
            scaledEndpoint.beginDispatchTick(tick);
        }
    }
}