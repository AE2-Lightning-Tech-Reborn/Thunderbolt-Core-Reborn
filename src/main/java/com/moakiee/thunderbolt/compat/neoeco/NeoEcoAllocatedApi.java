package com.moakiee.thunderbolt.compat.neoeco;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;

/** Reflective boundary for NeoECO's CPU-allocated facade; no optional types escape into core. */
final class NeoEcoAllocatedApi {
    private static final String API = "cn.dancingsnow.neoecoae.api.me.";
    private static final ClassValue<Optional<NeoEcoAllocatedApi>> APIS = new ClassValue<>() {
        @Override protected Optional<NeoEcoAllocatedApi> computeValue(Class<?> type) {
            return Optional.ofNullable(load(type.getClassLoader()));
        }
    };

    private final Class<?> providerType, reservationType, energyType;
    private final @Nullable Class<?> indeterminateType;
    private final Method prepare, count, submit;
    private final @Nullable Method exactPrepare, exactCount, exactSubmit;
    private final @Nullable Class<?> parallelType;
    private final @Nullable Method parallelFind, parallelSlots, parallelPush;

    private NeoEcoAllocatedApi(ClassLoader loader) throws ReflectiveOperationException {
        providerType = Class.forName(API + "provider.ECOFastPathDispatchProvider", false, loader);
        var facade = Class.forName(API + "ECOFastPathFacade", false, loader);
        reservationType = Class.forName(API + "ECOFastPathFacade$Reservation", false, loader);
        energyType = Class.forName(API + "ECOFastPathFacade$EnergyAccount", false, loader);
        prepare = facade.getMethod("prepareAllocated", ICraftingProvider.class, IPatternDetails.class,
                KeyCounter[].class, long.class, Level.class, UUID.class);
        count = instanceMethod(prepare.getReturnType(), "craftCount", long.class);
        submit = instanceMethod(prepare.getReturnType(), "submit", boolean.class, energyType);
        var reserve = instanceMethod(energyType, "reserve", reservationType, double.class);
        var commit = instanceMethod(reservationType, "commit", void.class);
        var refund = instanceMethod(reservationType, "refund", void.class);
        if (!Modifier.isPublic(facade.getModifiers()) || !Modifier.isStatic(prepare.getModifiers())
                || !energyType.isInterface() || !reservationType.isInterface()
                || !supportsProxyMethods(energyType, reserve)
                || !supportsProxyMethods(reservationType, commit, refund)) {
            throw new NoSuchMethodException("Incompatible NeoECO allocated facade");
        }
        Class<?> uncertain = null;
        try { uncertain = Class.forName(API + "provider.ECOIndeterminateBatchException", false, loader); }
        catch (ClassNotFoundException absent) { /* Older facades have no explicit rollback guarantee. */ }
        indeterminateType = uncertain;

        Method exact = null, copies = null, dispatch = null;
        try {
            exact = facade.getMethod("prepareExactAllocated", ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, BigInteger.class, Level.class, UUID.class);
            copies = instanceMethod(exact.getReturnType(), "craftCount", BigInteger.class);
            dispatch = instanceMethod(exact.getReturnType(), "submit", boolean.class);
            if (!Modifier.isStatic(exact.getModifiers()))
                throw new NoSuchMethodException("Incompatible NeoECO exact allocated facade");
        } catch (ReflectiveOperationException | LinkageError absent) {
            exact = copies = dispatch = null;
        }
        exactPrepare = exact;
        exactCount = copies;
        exactSubmit = dispatch;

        Class<?> parallel = null;
        Method find = null, slots = null, push = null;
        try {
            parallel = Class.forName(API + "provider.ECOParallelCraftingProvider", false, loader);
            slots = instanceMethod(parallel, "eco$getAvailableParallelSlots", int.class);
            push = instanceMethod(parallel, "eco$pushPatternBatch", boolean.class, IPatternDetails.class, KeyCounter[].class,
                    long.class, UUID.class);
            try {
                find = Class.forName(API + "provider.ECOParallelCraftingProviders", false, loader)
                        .getMethod("find", Object.class);
                if (!Modifier.isStatic(find.getModifiers()) || find.getReturnType() != parallel)
                    throw new NoSuchMethodException("Incompatible parallel registry");
            } catch (ClassNotFoundException absent) { /* Direct providers still work without a registry. */ }
        } catch (ReflectiveOperationException | LinkageError absent) {
            parallel = null;
            find = slots = push = null;
        }
        parallelType = parallel;
        parallelFind = find;
        parallelSlots = slots;
        parallelPush = push;
    }

    static @Nullable NeoEcoAllocatedApi load(ClassLoader loader) {
        try { return new NeoEcoAllocatedApi(loader); }
        catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) { return null; }
    }

    private static Method instanceMethod(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        var method = owner.getMethod(name, parameters);
        if (!Modifier.isPublic(owner.getModifiers()) || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != result) throw new NoSuchMethodException(method.toString());
        return method;
    }

    private static boolean supportsProxyMethods(Class<?> type, Method... supportedMethods) {
        var supported = Set.of(supportedMethods);
        for (var method : supportedMethods) if (Modifier.isStatic(method.getModifiers())) return false;
        for (var method : type.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || supported.contains(method)) continue;
            try {
                if (Object.class.getMethod(method.getName(), method.getParameterTypes()).getReturnType()
                        == method.getReturnType()) continue;
            } catch (NoSuchMethodException absent) { /* An unknown abstract callback cannot be honored. */ }
            return false;
        }
        return true;
    }

    static @Nullable NeoEcoAllocatedApi forProvider(ICraftingProvider provider) {
        return APIS.get(provider.getClass()).orElse(null);
    }

    boolean supports(Object provider) { return providerType.isInstance(provider); }
    boolean hasExactApi() { return exactPrepare != null; }

    @Nullable Object parallel(ICraftingProvider provider) {
        if (parallelType == null) return null;
        return parallelType.isInstance(provider) ? provider
                : parallelFind == null ? null : invoke(parallelFind, null, provider);
    }

    long parallelCapacity(Object provider) { return Math.max(0, (int) invoke(parallelSlots, provider)); }

    boolean pushParallel(Object provider, IPatternDetails pattern, KeyCounter[] oneCopy,
            long copies, @Nullable UUID jobId) {
        KeyCounter[] totals;
        try { totals = NeoEcoBatchApi.cloneInputs(oneCopy, copies); }
        catch (ArithmeticException overflow) { return false; } // No provider has been invoked yet.
        return (boolean) invoke(parallelPush, provider, pattern, totals, copies, jobId);
    }

    @Nullable Prepared prepareAllocated(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, long copies, Level level, @Nullable UUID id) {
        if (!supports(provider) || copies <= 0) return null;
        var value = invoke(prepare, null, provider, pattern,
                NeoEcoBatchApi.cloneInputs(inputs, 1), copies, level, id);
        if (value == null) return null;
        long admitted = (long) invoke(count, value);
        if (admitted <= 0 || admitted > copies)
            throw new IllegalArgumentException("Invalid NeoECO allocated count: " + admitted);
        return new Prepared(value, admitted);
    }

    @Nullable NeoEcoFastPathCompat.ExactBatch prepareExact(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, BigInteger requested, Level level, @Nullable UUID id) {
        if (exactPrepare == null || !supports(provider) || requested.signum() <= 0 || id == null) return null;
        var value = invoke(exactPrepare, null, provider, pattern,
                NeoEcoBatchApi.cloneInputs(inputs, 1), requested, level, id);
        if (value == null) return null;
        var admitted = (BigInteger) invoke(exactCount, value);
        if (admitted == null || admitted.signum() <= 0 || admitted.compareTo(requested) > 0)
            throw new IllegalArgumentException("Invalid exact NeoECO admission");
        return new NeoEcoFastPathCompat.ExactBatch(admitted, value, exactSubmit);
    }

    final class Prepared {
        private final Object value;
        private final long craftCount;
        Prepared(Object value, long craftCount) {
            this.value = value;
            this.craftCount = craftCount;
        }
        long craftCount() { return craftCount; }

        boolean submit() {
            // The CPU settles its own energy once after dispatch; the allocated facade uses zero power.
            Object reservation = Proxy.newProxyInstance(reservationType.getClassLoader(),
                    new Class<?>[]{reservationType}, (proxy, method, args) -> {
                        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method,
                                args == null ? new Object[0] : args);
                        return switch (method.getName()) {
                        case "commit", "refund" -> null;
                        case "toString" -> "ThunderboltAllocatedReservation";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
            Object account = Proxy.newProxyInstance(energyType.getClassLoader(), new Class<?>[]{energyType},
                    (proxy, method, args) -> {
                        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method,
                                args == null ? new Object[0] : args);
                        return switch (method.getName()) {
                        case "reserve" -> reservation;
                        case "toString" -> "ThunderboltAllocatedEnergy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
            try { return (boolean) submit.invoke(value, account); }
            catch (InvocationTargetException failure) {
                var cause = failure.getCause();
                if (indeterminateType != null && !indeterminateType.isInstance(cause)
                        && cause instanceof RuntimeException) {
                    throw new RejectedBatchException(cause);
                }
                // Older facades, explicit uncertainty and Errors cannot prove rollback.
                throw new IndeterminateBatchException(cause);
            } catch (ReflectiveOperationException failure) {
                throw new IndeterminateBatchException(failure);
            }
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("NeoECO facade call failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("NeoECO facade linkage failed", failure);
        }
    }

    static final class RejectedBatchException extends RuntimeException {
        RejectedBatchException(Throwable cause) { super(cause); }
    }

    static final class IndeterminateBatchException extends RuntimeException {
        IndeterminateBatchException(Throwable cause) { super(cause); }
    }
}
