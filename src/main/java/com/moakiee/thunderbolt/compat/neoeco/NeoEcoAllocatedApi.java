package com.moakiee.thunderbolt.compat.neoeco;

import java.lang.reflect.*;
import java.util.UUID;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;

/** Optional allocated-input API, discovered without initializing addon classes. */
final class NeoEcoAllocatedApi {
    private static final NeoEcoAllocatedApi API = load(NeoEcoAllocatedApi.class.getClassLoader());
    private final Class<?> providerType, reservationType, accountType;
    private final Method prepare, count, submit;

    private NeoEcoAllocatedApi(ClassLoader loader) throws ReflectiveOperationException {
        String prefix = "cn.dancingsnow.neoecoae.api.me.";
        providerType = Class.forName(prefix + "provider.ECOFastPathDispatchProvider", false, loader);
        reservationType = Class.forName(prefix + "ECOFastPathFacade$Reservation", false, loader);
        accountType = Class.forName(prefix + "ECOFastPathFacade$EnergyAccount", false, loader);
        var facade = Class.forName(prefix + "ECOFastPathFacade", false, loader);
        prepare = facade.getMethod("prepareAllocated", ICraftingProvider.class,
                IPatternDetails.class, KeyCounter[].class, long.class, Level.class, UUID.class);
        if (!Modifier.isStatic(prepare.getModifiers())) throw new NoSuchMethodException("prepareAllocated");
        count = method(prepare.getReturnType(), "craftCount", long.class);
        submit = method(prepare.getReturnType(), "submit", boolean.class, accountType);
        method(accountType, "reserve", reservationType, double.class);
        method(reservationType, "commit", void.class);
        method(reservationType, "refund", void.class);
        validateCallbacks(accountType, "reserve");
        validateCallbacks(reservationType, "commit", "refund");
    }

    static NeoEcoAllocatedApi load(ClassLoader loader) {
        try { return new NeoEcoAllocatedApi(loader); }
        catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) { return null; }
    }

    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        var method = owner.getMethod(name, parameters);
        if (!Modifier.isPublic(owner.getModifiers()) || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != result) throw new NoSuchMethodException(name);
        return method;
    }

    private static void validateCallbacks(Class<?> type, String... allowed) throws NoSuchMethodException {
        if (!type.isInterface()) throw new NoSuchMethodException(type.getName());
        for (var method : type.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers())
                    && !java.util.List.of(allowed).contains(method.getName()))
                throw new NoSuchMethodException(method.toString());
        }
    }

    static boolean available() { return API != null; }
    static boolean supports(Object provider) { return API != null && API.providerType.isInstance(provider); }

    static Prepared prepareAllocated(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, long offered, Level level, UUID id) {
        if (!supports(provider)) return null;
        try {
            var value = API.prepare.invoke(null, provider, pattern, inputs, offered, level, id);
            if (value == null) return null;
            long count = (long) API.count.invoke(value);
            if (count <= 0 || count > offered) throw new IllegalArgumentException("Invalid NeoECO allocated count: " + count);
            return new Prepared(value, count);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("NeoECO allocation preparation failed", failure);
        }
    }

    private static Object callback(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            return switch (method.getName()) {
                case "toString" -> "AllocatedNeoEcoAccount";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> handler.invoke(proxy, method, args);
            };
        });
    }

    record Prepared(Object value, long craftCount) {
        boolean submit() {
            var reservation = callback(API.reservationType, (proxy, method, args) -> null);
            var account = callback(API.accountType, (proxy, method, args) -> reservation);
            try { return (boolean) API.submit.invoke(value, account); }
            catch (InvocationTargetException uncertain) { throw new IndeterminateBatchException(uncertain.getCause()); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
    }

    static final class IndeterminateBatchException extends RuntimeException {
        IndeterminateBatchException(Throwable cause) { super(cause); }
    }
}
