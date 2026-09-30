package com.moakiee.thunderbolt.compat.extendedaeplus;

import java.util.List;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;

import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;

/** Adapts only EAEP's Super Assembler Matrix, leaving ordinary matrices untouched. */
public final class ExtendedAePlusBatchAdapter implements BatchProviderResolver {
    private final Class<?> providerType;

    private ExtendedAePlusBatchAdapter(Class<?> providerType) { this.providerType = providerType; }

    public static @Nullable BatchProviderResolver createAdapter() {
        if (!ExtendedAePlusSuperMatrixBatchBridge.isAvailable()) return null;
        try {
            var type = Class.forName(
                    "com.extendedae_plus.content.matrix.supermatrix.SuperAssemblerMatrixBlockEntity",
                    false, ExtendedAePlusBatchAdapter.class.getClassLoader());
            return ICraftingProvider.class.isAssignableFrom(type) && BlockEntity.class.isAssignableFrom(type)
                    ? new ExtendedAePlusBatchAdapter(type) : null;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return null;
        }
    }

    @Override public boolean cacheResolutionAcrossTicks() { return true; }
    @Override public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        return providerType.isInstance(provider) ? new Endpoint(provider) : null;
    }

    private static final class Endpoint implements IBatchCraftingProvider {
        private final ICraftingProvider delegate;
        private Endpoint(ICraftingProvider delegate) { this.delegate = delegate; }
        @Override public List<IPatternDetails> getAvailablePatterns() { return delegate.getAvailablePatterns(); }
        @Override public boolean isBusy() { return delegate.isBusy(); }
        private long batchLimitRulesVersion = Long.MIN_VALUE;
        private long batchCopyLimit = Long.MAX_VALUE;

        @Override
        public long getBatchCapacity(IPatternDetails details) {
            var self = delegate;
            if (self.isBusy()) {
                return 0L;
            }
            return Math.min(
                    ExtendedAePlusSuperMatrixBatchBridge.capacity(details),
                    batchCopyLimit());
        }

        @Override
        public long pushBatch(
                IPatternDetails details,
                KeyCounter[] oneCopyTemplate,
                long maxCraft) {
            long limited = Math.min(maxCraft, batchCopyLimit());
            if (limited <= 0L) {
                return maxCraft;
            }
            long limitedLeftover = ExtendedAePlusSuperMatrixBatchBridge.pushBatch(
                    delegate,
                    details,
                    oneCopyTemplate,
                    limited);
            long accepted = limited - Math.clamp(limitedLeftover, 0L, limited);
            return maxCraft - accepted;
        }

        private long batchCopyLimit() {
            var rules = CoreConfig.batchCopyLimitRules();
            if (batchLimitRulesVersion != rules.version()) {
                var self = (BlockEntity) delegate;
                var blockId = BuiltInRegistries.BLOCK.getKey(self.getBlockState().getBlock());
                batchCopyLimit = blockId != null
                        ? rules.limit(blockId.toString())
                        : Long.MAX_VALUE;
                batchLimitRulesVersion = rules.version();
            }
            return batchCopyLimit;
        }
    }
}
