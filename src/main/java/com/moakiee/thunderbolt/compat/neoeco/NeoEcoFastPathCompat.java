package com.moakiee.thunderbolt.compat.neoeco;

import org.jetbrains.annotations.Nullable;

import net.minecraftforge.fml.ModList;

import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

/** Keeps NeoECO API types outside the class-loading path when the optional mod is absent. */
public final class NeoEcoFastPathCompat {
    private static final Optional<Method> CANCEL = findCancellationApi();
    private static final ClassValue<Optional<Method>> ORDINARY = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> providerType) {
            try {
                var contract = Class.forName("cn.dancingsnow.neoecoae.api.me.provider.ECOJobAwareCraftingProvider",
                        false, providerType.getClassLoader());
                if (!contract.isAssignableFrom(providerType)) return Optional.empty();
                var facade = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade",
                        false, providerType.getClassLoader());
                return Optional.of(facade.getMethod("pushPatternForJob", ICraftingProvider.class,
                        IPatternDetails.class, KeyCounter[].class, UUID.class));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return Optional.empty();
            }
        }
    };
    private static final ClassValue<Optional<Method>> EXACT = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> providerType) {
            try {
                var contract = Class.forName("cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider",
                        false, providerType.getClassLoader());
                if (!contract.isAssignableFrom(providerType)) return Optional.empty();
                var facade = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade",
                        false, providerType.getClassLoader());
                return Optional.of(facade.getMethod("prepareExactAllocated", ICraftingProvider.class,
                        IPatternDetails.class, KeyCounter[].class, BigInteger.class, Level.class, UUID.class));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return Optional.empty();
            }
        }
    };
    private static final String ADAPTER_CLASS =
            "com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathBatchAdapter";

    private NeoEcoFastPathCompat() {
    }

    private static Optional<Method> findCancellationApi() {
        try {
            var facade = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade",
                    false, NeoEcoFastPathCompat.class.getClassLoader());
            return Optional.of(facade.getMethod("cancelJob", Level.class, UUID.class));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /** Optional terminal notice for independent CPU implementations. */
    public static void cancelJob(@Nullable Level level, UUID craftingId) {
        if (CANCEL.isEmpty()) return;
        try {
            CANCEL.get().invoke(null, level, craftingId);
        } catch (ReflectiveOperationException failure) {
            appeng.core.AELog.warn("[thunderbolt] Could not recover NeoECO workers for cancelled job %s: %s",
                    craftingId, failure);
        }
    }

    /** Preserves task ownership on both the batch fallback and the CPU's later ordinary visits. */
    public static boolean pushPattern(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, @Nullable UUID craftingId) {
        var method = ORDINARY.get(provider.getClass()).orElse(null);
        if (method == null) return provider.pushPattern(pattern, inputs);
        try {
            return (boolean) method.invoke(null, provider, pattern, inputs, craftingId);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("NeoECO ordinary submission failed", failed.getCause());
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("Cannot determine NeoECO ordinary submission ownership", failed);
        }
    }

    /** Exact FX admission is optional; the calling CPU retains extraction and settlement. */
    public static @Nullable ExactBatch prepareExact(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] oneCopy, BigInteger requested, Level level, UUID craftingId) {
        var prepare = EXACT.get(provider.getClass()).orElse(null);
        if (prepare == null) return null;
        try {
            var batch = prepare.invoke(null, provider, pattern, oneCopy, requested, level, craftingId);
            if (batch == null) return null;
            var count = (BigInteger) batch.getClass().getMethod("craftCount").invoke(batch);
            if (count.signum() <= 0 || count.compareTo(requested) > 0)
                throw new IllegalArgumentException("Invalid exact FX admission");
            return new ExactBatch(count, batch, batch.getClass().getMethod("submit"));
        } catch (InvocationTargetException failure) {
            throw invocationFailure(failure);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot prepare exact FX batch", failure);
        }
    }

    public record ExactBatch(BigInteger craftCount, Object batch, Method submitMethod) {
        public boolean submit() {
            try { return (boolean) submitMethod.invoke(batch); }
            catch (InvocationTargetException failure) { throw invocationFailure(failure); }
            catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot determine exact FX submission ownership", failure);
            }
        }
    }

    private static RuntimeException invocationFailure(InvocationTargetException failure) {
        if (failure.getCause() instanceof RuntimeException runtime) return runtime;
        if (failure.getCause() instanceof Error error) throw error;
        return new IllegalStateException("NeoECO exact batch failed", failure.getCause());
    }

    @Nullable
    public static BatchProviderAdapter createAdapter() {
        // Plain unit tests do not initialize the Forge mod list.
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
            // Constructing the adapter alone does not force lazy API references to link.
            // Probe before registration so old NeoECO releases retain ordinary dispatch.
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
