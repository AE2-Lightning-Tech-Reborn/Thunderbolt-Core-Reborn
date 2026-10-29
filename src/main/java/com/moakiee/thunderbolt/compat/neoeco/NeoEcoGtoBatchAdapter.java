package com.moakiee.thunderbolt.compat.neoeco;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;

import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.api.crafting.batch.PreparedBatch;

/** Optional bridge to NeoECO 20.5.0-gto.1's public allocated-input protocol. */
public final class NeoEcoGtoBatchAdapter implements BatchProviderResolver {
    private final Api api;

    private NeoEcoGtoBatchAdapter(Api api) { this.api = api; }

    public static @Nullable BatchProviderResolver createAdapter() {
        try {
            return new NeoEcoGtoBatchAdapter(new Api());
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    @Override public boolean cacheResolutionAcrossTicks() { return true; }

    @Override public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        // In particular, leave NeoECO's native Thunderbolt workstation implementation in charge.
        return !(provider instanceof IBatchCraftingProvider) && api.providerType.isInstance(provider)
                ? new Endpoint(provider) : null;
    }

    private final class Endpoint implements IBatchCraftingProvider {
        private final ICraftingProvider provider;

        Endpoint(ICraftingProvider provider) { this.provider = provider; }

        @Override public List<IPatternDetails> getAvailablePatterns() {
            return provider.getAvailablePatterns();
        }

        @Override public boolean isBusy() { return provider.isBusy(); }

        // Capability is cached; admission and worker availability are checked for every offer.
        @Override public long getBatchCapacity(IPatternDetails pattern) {
            return isBusy() ? 0 : Long.MAX_VALUE;
        }

        @Override public long pushBatch(IPatternDetails pattern, KeyCounter[] oneCopy, long copies) {
            // An external CPU must supply the job context. Never create unowned persistent work.
            return copies;
        }

        @Override public long pushBatch(IPatternDetails pattern, KeyCounter[] oneCopy,
                                        long copies, BatchJobView job) {
            return copies <= 0 ? copies : prepareBatch(pattern, oneCopy, copies, job).push(copies);
        }

        @Override public PreparedBatch prepareBatch(IPatternDetails pattern, KeyCounter[] oneCopy,
                                                     long copies, BatchJobView job) {
            // BatchJobView is borrowed and may be reused for a different job immediately afterwards.
            Level level = job.level();
            UUID jobId = job.craftingId();
            KeyCounter[] inputs = copy(oneCopy);
            Object admitted = copies > 0 && jobId != null
                    ? api.prepare(provider, pattern, inputs, copies, level, jobId) : null;
            long capacity = admitted == null ? (copies > 0 && jobId != null ? 1 : 0)
                    : api.capacity(admitted, copies);
            return new PreparedBatch() {
                private boolean submitted;

                @Override public long capacity() { return capacity; }

                @Override public long push(long offered) {
                    if (submitted) throw new IllegalStateException("NeoECO admission already submitted");
                    submitted = true;
                    if (offered <= 0 || capacity <= 0) return offered;
                    long bounded = Math.min(offered, capacity);
                    if (admitted == null) {
                        return (boolean) Api.invoke(api.pushForJob, null,
                                provider, pattern, copy(inputs), jobId) ? offered - 1 : offered;
                    }
                    // Power limits and sharing among providers can shrink the CPU's final offer.
                    Object batch = bounded == capacity ? admitted
                            : api.prepare(provider, pattern, inputs, bounded, level, jobId);
                    if (batch == null) return offered;
                    long accepted = api.capacity(batch, bounded);
                    return api.submit(batch) ? offered - accepted : offered;
                }
            };
        }
    }

    private static KeyCounter[] copy(KeyCounter[] inputs) {
        var result = new KeyCounter[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            result[i] = new KeyCounter();
            if (inputs[i] != null) result[i].addAll(inputs[i]);
        }
        return result;
    }

    /** No NeoECO symbols are linked when the optional mod is absent. */
    private static final class Api {
        private static final String ROOT = "cn.dancingsnow.neoecoae.api.me.";
        private final Class<?> providerType = type(ROOT + "provider.ECOFastPathDispatchProvider");
        private final Class<?> indeterminate = type(ROOT + "provider.ECOIndeterminateBatchException");
        private final Class<?> facade = type(ROOT + "ECOFastPathFacade");
        private final Class<?> batchType = type(ROOT + "ECOFastPathFacade$PreparedBatch");
        private final Class<?> energyType = type(ROOT + "ECOFastPathFacade$EnergyAccount");
        private final Class<?> reservationType = type(ROOT + "ECOFastPathFacade$Reservation");
        private final Method prepareAllocated = facade.getMethod("prepareAllocated", ICraftingProvider.class,
                IPatternDetails.class, KeyCounter[].class, long.class, Level.class, UUID.class);
        private final Method pushForJob = facade.getMethod("pushPatternForJob", ICraftingProvider.class,
                IPatternDetails.class, KeyCounter[].class, UUID.class);
        private final Method craftCount = batchType.getMethod("craftCount");
        private final Method power = batchType.getMethod("power");
        private final Method remainders = batchType.getMethod("remainders");
        private final Method submit = batchType.getMethod("submit", energyType);
        private final Object energy;

        Api() throws ReflectiveOperationException {
            if (prepareAllocated.getReturnType() != batchType || craftCount.getReturnType() != long.class
                    || power.getReturnType() != double.class || submit.getReturnType() != boolean.class
                    || pushForJob.getReturnType() != boolean.class) {
                throw new NoSuchMethodException("Unsupported NeoECO allocated-input API");
            }
            var loader = NeoEcoGtoBatchAdapter.class.getClassLoader();
            Object reservation = Proxy.newProxyInstance(loader, new Class<?>[]{reservationType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "commit", "refund" -> null; // The CPU settles its own energy exactly once.
                        default -> objectMethod(proxy, method, args);
                    });
            energy = Proxy.newProxyInstance(loader, new Class<?>[]{energyType}, (proxy, method, args) -> {
                if (!method.getName().equals("reserve")) return objectMethod(proxy, method, args);
                // prepareAllocated must never charge the already allocated CPU a second time.
                return (double) args[0] == 0.0 ? reservation : null;
            });
        }

        Object prepare(ICraftingProvider provider, IPatternDetails pattern, KeyCounter[] inputs,
                       long copies, Level level, UUID jobId) {
            return invoke(prepareAllocated, null, provider, pattern, copy(inputs), copies, level, jobId);
        }

        long capacity(Object batch, long offered) {
            long count = (long) invoke(craftCount, batch);
            if (count <= 0 || count > offered || (double) invoke(power, batch) != 0.0
                    || !((List<?>) invoke(remainders, batch)).isEmpty()) {
                throw new IllegalStateException("Invalid NeoECO allocated batch admission");
            }
            return count;
        }

        boolean submit(Object batch) {
            try {
                return (boolean) invoke(submit, batch, energy);
            } catch (RuntimeException failure) {
                // This public API guarantees rollback for ordinary provider exceptions. Ambiguous
                // acceptance must reach BatchExecutor.failDispatch: no refund, fallback or replay.
                if (indeterminate.isInstance(failure)) throw failure;
                appeng.core.AELog.warn("[thunderbolt] NeoECO rejected allocated batch after rollback: %s", failure);
                return false;
            }
        }

        private static Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, false, NeoEcoGtoBatchAdapter.class.getClassLoader());
        }

        private static Object invoke(Method method, Object receiver, Object... args) {
            try {
                return method.invoke(receiver, args);
            } catch (InvocationTargetException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("NeoECO API invocation failed", cause);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("NeoECO API is inaccessible", failure);
            }
        }

        private static Object objectMethod(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Thunderbolt CPU-owned NeoECO energy reservation";
                default -> throw new UnsupportedOperationException(method.toString());
            };
        }
    }
}
