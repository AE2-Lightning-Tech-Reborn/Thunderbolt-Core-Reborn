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
    private static final String ADAPTER_CLASS =
            "com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathBatchAdapter";

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

    /** Forge full-virtual preparation owns its native context and atomic dispatch callback. */
    public static @Nullable ExactBatch prepareExact(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] oneCopy, java.math.BigInteger requested, Level level, UUID craftingId) {
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
            return (boolean) method.invoke(provider, pattern, inputs, craftingId);
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
        try {
            // Validate lazy API references before registration; older releases use ordinary dispatch.
            Class.forName("cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider", false, loader);
            var facade = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade", false, loader);
            var prepared = facade.getMethod("prepareAllocated", ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, long.class, Level.class, UUID.class).getReturnType();
            var energy = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade$EnergyAccount", false, loader);
            if (prepared.getMethod("craftCount").getReturnType() != long.class
                    || prepared.getMethod("submit", energy).getReturnType() != boolean.class) {
                throw new NoSuchMethodException("Incompatible allocated FastPath contract");
            }
            return (BatchProviderAdapter) Class.forName(ADAPTER_CLASS, true, loader)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            appeng.core.AELog.warn(
                    "[thunderbolt] NeoECO FastPath API is unavailable; CPUs will use ordinary provider dispatch. %s",
                    unavailable);
            return null;
        }
    }
}
