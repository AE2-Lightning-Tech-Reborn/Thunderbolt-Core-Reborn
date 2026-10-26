package com.moakiee.thunderbolt.compat.neoeco;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Optional facade for batch dispatch and independent CPU integrations; not an ECO dependency. */
public final class NeoEcoFastPathCompat {
    private static final ClassValue<Optional<NeoEcoAllocatedApi>> APIS = new ClassValue<>() {
        @Override protected Optional<NeoEcoAllocatedApi> computeValue(Class<?> type) {
            return Optional.ofNullable(NeoEcoAllocatedApi.load(type.getClassLoader()));
        }
    };

    private NeoEcoFastPathCompat() {}

    public static @Nullable BatchProviderAdapter createAdapter() {
        return loadAdapter(NeoEcoFastPathCompat.class.getClassLoader());
    }

    static @Nullable BatchProviderAdapter loadAdapter(ClassLoader loader) {
        var api = NeoEcoAllocatedApi.load(loader);
        return api == null ? null : new NeoEcoFastPathBatchAdapter(api);
    }

    public static boolean pushPattern(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, @Nullable UUID jobId) {
        var api = APIS.get(provider.getClass()).orElse(null);
        return api == null ? provider.pushPattern(pattern, inputs) : api.pushOrdinary(provider, pattern, inputs, jobId);
    }

    /** No extraction or settlement: an exact-capable caller must own its complete transaction. */
    public static @Nullable ExactBatch prepareExact(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, BigInteger requested, Level level, UUID jobId) {
        var api = APIS.get(provider.getClass()).orElse(null);
        return api == null ? null : api.prepareExact(provider, pattern, inputs, requested, level, jobId);
    }

    public static void cancelJob(@Nullable Level level, UUID jobId) {
        var api = APIS.get(NeoEcoFastPathCompat.class).orElse(null);
        if (api != null) api.cancel(level, jobId);
    }

    public static final class ExactBatch {
        private final BigInteger craftCount;
        private final BooleanSupplier dispatch;
        private boolean used;

        ExactBatch(BigInteger craftCount, BooleanSupplier dispatch) {
            this.craftCount = craftCount;
            this.dispatch = dispatch;
        }
        public BigInteger craftCount() { return craftCount; }
        public boolean submit() {
            if (used) throw new IllegalStateException("ECO exact preparation already submitted");
            used = true;
            return dispatch.getAsBoolean();
        }
    }
}
