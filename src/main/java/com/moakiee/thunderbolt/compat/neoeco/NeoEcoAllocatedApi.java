package com.moakiee.thunderbolt.compat.neoeco;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.UUID;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Optional NeoECO 1.20.1 facade. No foreign types occur in Thunderbolt's descriptors. */
final class NeoEcoAllocatedApi {
    private static final String FACADE = "cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade";
    private final Class<?> providerType;
    private final Method prepare, count, submit, ordinary, cancel;
    private final @Nullable Method prepareExact, exactCount, exactSubmit;
    private final Object prepaidEnergy;

    private NeoEcoAllocatedApi(ClassLoader loader) throws ReflectiveOperationException {
        providerType = Class.forName("cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider", false, loader);
        Class<?> facade = Class.forName(FACADE, false, loader);
        Class<?> energy = Class.forName(FACADE + "$EnergyAccount", false, loader);
        Class<?> reservation = Class.forName(FACADE + "$Reservation", false, loader);
        prepare = facade.getMethod("prepareAllocated", ICraftingProvider.class, IPatternDetails.class,
                KeyCounter[].class, long.class, Level.class, UUID.class);
        requireStatic(prepare);
        count = prepare.getReturnType().getMethod("craftCount");
        submit = prepare.getReturnType().getMethod("submit", energy);
        ordinary = facade.getMethod("pushPatternForJob", ICraftingProvider.class,
                IPatternDetails.class, KeyCounter[].class, UUID.class);
        cancel = facade.getMethod("cancelJob", Level.class, UUID.class);
        requireStatic(ordinary);
        requireStatic(cancel);
        if (!providerType.isInterface() || !energy.isInterface() || !reservation.isInterface()
                || count.getReturnType() != long.class || submit.getReturnType() != boolean.class
                || ordinary.getReturnType() != boolean.class || cancel.getReturnType() != void.class
                || energy.getMethod("reserve", double.class).getReturnType() != reservation
                || reservation.getMethod("commit").getReturnType() != void.class
                || reservation.getMethod("refund").getReturnType() != void.class) {
            throw new NoSuchMethodException("Incompatible NeoECO allocated-batch contract");
        }
        Object prepaid = Proxy.newProxyInstance(loader, new Class<?>[]{reservation}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "commit", "refund" -> null; // The CPU alone settles physical energy.
                    default -> objectMethod(proxy, method, args);
                });
        prepaidEnergy = Proxy.newProxyInstance(loader, new Class<?>[]{energy}, (proxy, method, args) -> {
            if (method.getName().equals("reserve")) {
                // prepareAllocated promises a zero-power private ledger. Never silently waive a new fee.
                if ((double) args[0] != 0.0D) throw new IllegalStateException("Unexpected allocated ECO energy charge");
                return prepaid;
            }
            return objectMethod(proxy, method, args);
        });
        Method exact = null, copies = null, dispatch = null;
        try {
            exact = facade.getMethod("prepareExactAllocated", ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, BigInteger.class, Level.class, UUID.class);
            requireStatic(exact);
            copies = exact.getReturnType().getMethod("craftCount");
            dispatch = exact.getReturnType().getMethod("submit");
            if (copies.getReturnType() != BigInteger.class || dispatch.getReturnType() != boolean.class)
                throw new NoSuchMethodException("Incompatible NeoECO exact contract");
        } catch (NoSuchMethodException absent) {
            exact = null;
            copies = null;
            dispatch = null;
        }
        prepareExact = exact;
        exactCount = copies;
        exactSubmit = dispatch;
    }

    static @Nullable NeoEcoAllocatedApi load(ClassLoader loader) {
        try { return new NeoEcoAllocatedApi(loader); }
        catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
    }

    boolean supports(ICraftingProvider provider) { return providerType.isInstance(provider); }

    @Nullable Admission prepare(ICraftingProvider provider, IPatternDetails pattern, KeyCounter[] inputs,
            long requested, Level level, @Nullable UUID jobId) {
        Object batch = invoke(prepare, null, provider, pattern, copyInputs(inputs), requested, level, jobId);
        if (batch == null) return null;
        long capacity = (long) invoke(count, batch);
        if (capacity <= 0 || capacity > requested) throw new IllegalStateException("Invalid ECO batch admission");
        return new Admission(capacity, () -> (boolean) invoke(submit, batch, prepaidEnergy));
    }

    @Nullable NeoEcoFastPathCompat.ExactBatch prepareExact(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, BigInteger requested, Level level, UUID jobId) {
        if (prepareExact == null || !supports(provider) || requested.signum() <= 0) return null;
        Object batch = invoke(prepareExact, null, provider, pattern, copyInputs(inputs), requested, level, jobId);
        if (batch == null) return null;
        BigInteger capacity = (BigInteger) invoke(exactCount, batch);
        if (capacity == null || capacity.signum() <= 0 || capacity.compareTo(requested) > 0)
            throw new IllegalStateException("Invalid ECO exact admission");
        return new NeoEcoFastPathCompat.ExactBatch(capacity, () -> (boolean) invoke(exactSubmit, batch));
    }

    boolean pushOrdinary(ICraftingProvider provider, IPatternDetails pattern, KeyCounter[] inputs, @Nullable UUID jobId) {
        return (boolean) invoke(ordinary, null, provider, pattern, inputs, jobId);
    }

    void cancel(@Nullable Level level, UUID jobId) { invoke(cancel, null, level, jobId); }

    static KeyCounter[] copyInputs(KeyCounter[] inputs) {
        KeyCounter[] copy = new KeyCounter[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            copy[i] = new KeyCounter();
            copy[i].addAll(inputs[i]);
        }
        return copy;
    }

    private static void requireStatic(Method method) throws NoSuchMethodException {
        if (!Modifier.isStatic(method.getModifiers())) throw new NoSuchMethodException("Expected static " + method);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "ThunderboltCpuOwnedEnergy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    private static Object invoke(Method method, Object owner, Object... args) {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("ECO invocation failed: " + method.getName(), failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("ECO linkage failed: " + method.getName(), failure);
        }
    }

    record Admission(long capacity, java.util.function.BooleanSupplier submit) {}
}
