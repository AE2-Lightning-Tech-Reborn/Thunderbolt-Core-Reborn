package com.moakiee.thunderbolt.compat.neoeco;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.fml.ModList;

import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;
import java.util.UUID;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/** Keeps NeoECO API types outside the class-loading path when the optional mod is absent. */
public final class NeoEcoFastPathCompat {
    private static final ClassValue<Optional<NeoEcoBatchApi>> EXACT_API = new ClassValue<>() {
        @Override protected Optional<NeoEcoBatchApi> computeValue(Class<?> providerType) {
            return Optional.ofNullable(NeoEcoBatchApi.load(providerType.getClassLoader()));
        }
    };
    private static final Optional<Method> CANCEL = cancellationApi();
    private static final ClassValue<Optional<Method>> JOB_PUSH = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> providerType) {
            try {
                var facade = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade",
                        false, providerType.getClassLoader());
                var push = facade.getMethod("pushPatternForJob", ICraftingProvider.class, IPatternDetails.class,
                        KeyCounter[].class, UUID.class);
                if (push.getReturnType() == boolean.class) return Optional.of(push);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                // Earlier Forge releases expose job identity on the pattern bus itself.
            }
            try {
                var bus = Class.forName(
                        "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity",
                        false, providerType.getClassLoader());
                if (!bus.isAssignableFrom(providerType)) return Optional.empty();
                var push = bus.getMethod("pushPattern", IPatternDetails.class, KeyCounter[].class, UUID.class);
                return push.getReturnType() == boolean.class ? Optional.of(push) : Optional.empty();
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return Optional.empty();
            }
        }
    };
    private NeoEcoFastPathCompat() {
    }

    private static Optional<Method> cancellationApi() {
        try {
            var lifecycle = Class.forName(
                    "cn.dancingsnow.neoecoae.crafting.execution.worker.ECOCraftingJobLifecycle",
                    false, NeoEcoFastPathCompat.class.getClassLoader());
            return Optional.of(lifecycle.getMethod("cancelAndRecover", Level.class, UUID.class));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    public static void cancelJob(@Nullable Level level, UUID craftingId) {
        if (CANCEL.isEmpty()) return;
        try {
            CANCEL.get().invoke(null, level, craftingId);
        } catch (ReflectiveOperationException failure) {
            appeng.core.AELog.warn("[thunderbolt] Could not recover NeoECO workers for cancelled job %s: %s",
                    craftingId, failure);
        }
    }

    /** Exact allocation; the calling CPU owns its input/output reservation and settlement. */
    public static @Nullable ExactBatch prepareExact(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] oneCopy, java.math.BigInteger requested, Level level, UUID craftingId) {
        var allocated = NeoEcoAllocatedApi.forProvider(provider);
        if (allocated != null && allocated.hasExactApi()) {
            // A modern decline is authoritative; never bypass it through an older protocol.
            return allocated.prepareExact(provider, pattern, oneCopy, requested, level, craftingId);
        }
        var api = EXACT_API.get(provider.getClass()).orElse(null);
        return api == null ? null : api.prepareExact(provider, pattern, oneCopy, requested, level, craftingId);
    }

    public record ExactBatch(java.math.BigInteger craftCount, Object batch, Method submitMethod) {
        public boolean submit() {
            try {
                return (boolean) submitMethod.invoke(batch);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new IllegalStateException("NeoECO exact submission failed", failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot determine exact NeoECO submission ownership", failure);
            }
        }
    }

    /** Forge NeoECO exposes job ownership on the native pattern bus's public overload. */
    public static boolean pushPattern(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, @Nullable UUID craftingId) {
        var method = JOB_PUSH.get(provider.getClass()).orElse(null);
        if (method == null) return provider.pushPattern(pattern, inputs);
        try {
            return java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    ? (boolean) method.invoke(null, provider, pattern, inputs, craftingId)
                    : (boolean) method.invoke(provider, pattern, inputs, craftingId);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("NeoECO ordinary submission failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot determine NeoECO ordinary submission ownership", failure);
        }
    }

    @Nullable
    public static BatchProviderAdapter createAdapter() {
        var modList = ModList.get();
        if (modList == null || !modList.isLoaded("neoecoae")) {
            return null;
        }
        return loadAdapter(NeoEcoFastPathCompat.class.getClassLoader());
    }

    static @Nullable BatchProviderAdapter loadAdapter(ClassLoader loader) {
        var forgeApi = NeoEcoBatchApi.load(loader);
        if (forgeApi != null) return new NeoEcoBatchAdapter(forgeApi);
        var allocated = NeoEcoAllocatedApi.load(loader);
        return allocated == null ? null : new NeoEcoFastPathBatchAdapter(allocated);
    }
}
