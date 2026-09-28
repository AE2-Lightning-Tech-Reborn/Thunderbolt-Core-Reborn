package com.moakiee.thunderbolt.compat.useless;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

/** Cached public API methods; no dependency on Useless implementation classes or jars. */
final class UselessBatchApi {
    private static final String PACKAGE = "com.sorrowmist.useless.api.crafting.bigint.";
    final Class<?> providerType;
    final Method target;
    final Method capacity;
    final Method accepted;
    final Method admit;
    final Method count;
    final Method commit;

    UselessBatchApi(ClassLoader loader) throws ReflectiveOperationException {
        this(load(loader, "AlloyFurnaceBigIntegerProvider"),
                load(loader, "AlloyFurnaceBigIntegerTarget"),
                load(loader, "AlloyFurnaceBigIntegerCapacity"),
                load(loader, "AlloyFurnaceBigIntegerBatch"),
                load(loader, "cpu.AlloyFurnaceBigIntegerCpuBinding"));
    }

    UselessBatchApi(Class<?> provider, Class<?> targetType, Class<?> capacityType,
                    Class<?> batchType, Class<?> bindingType) throws ReflectiveOperationException {
        providerType = provider;
        target = method(provider, "bigIntegerTarget", targetType);
        capacity = method(targetType, "capacity", capacityType,
                IPatternDetails.class, KeyCounter[].class, BigInteger.class);
        accepted = method(capacityType, "accepted", BigInteger.class);
        admit = method(targetType, "admit", batchType,
                IPatternDetails.class, KeyCounter[].class, BigInteger.class, bindingType);
        count = method(batchType, "count", BigInteger.class);
        commit = method(batchType, "commit", boolean.class, KeyCounter[].class);
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(PACKAGE + name, false, loader);
    }

    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        var method = owner.getMethod(name, parameters);
        if (method.getReturnType() != result || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + "." + name + " has an incompatible signature");
        }
        return method;
    }

    static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            // In particular, a commit failure must reach BatchExecutor's ambiguous-ownership
            // handling. Never convert it into an ordinary retry or a material refund.
            var cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Useless public batch API failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot invoke Useless public batch API", failure);
        }
    }
}
