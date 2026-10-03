package com.moakiee.thunderbolt.compat.useless;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.math.BigInteger;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

/** Cached public API methods; no dependency on Useless implementation classes or jars. */
final class UselessBatchApi {
    private static final String PACKAGE = "com.sorrowmist.useless.api.crafting.bigint.";
    final Class<?> providerType;
    final MethodHandle target;
    final MethodHandle capacity;
    final MethodHandle accepted;
    final MethodHandle admit;
    final MethodHandle count;
    final MethodHandle commit;

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

    private static MethodHandle method(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (method.getReturnType() != result || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + "." + name + " has an incompatible signature");
        }
        try {
            var parametersWithReceiver = new Class<?>[parameters.length + 1];
            parametersWithReceiver[0] = Object.class;
            for (int i = 0; i < parameters.length; i++) {
                parametersWithReceiver[i + 1] = parameters[i].isPrimitive()
                        ? parameters[i] : Object.class;
            }
            return MethodHandles.lookup().unreflect(method).asType(
                    MethodType.methodType(result == boolean.class ? boolean.class : Object.class, parametersWithReceiver));
        } catch (IllegalAccessException failure) {
            NoSuchMethodException wrapped = new NoSuchMethodException(
                    owner.getName() + "." + name + " is not publicly accessible");
            wrapped.initCause(failure);
            throw wrapped;
        }
    }

    Object target(Object receiver) {
        try {
            return (Object) target.invokeExact(receiver);
        } catch (RuntimeException | Error failure) {
            // Never retry a commit whose ownership may already have transferred.
            throw failure;
        } catch (Throwable failure) {
            throw failure(failure);
        }
    }

    Object capacity(Object receiver, Object pattern, Object inputs, Object requested) {
        try {
            return (Object) capacity.invokeExact(receiver, pattern, inputs, requested);
        } catch (RuntimeException | Error failure) {
            // Never retry a commit whose ownership may already have transferred.
            throw failure;
        } catch (Throwable failure) {
            throw failure(failure);
        }
    }

    BigInteger accepted(Object receiver) {
        try {
            return (BigInteger) (Object) accepted.invokeExact(receiver);
        } catch (RuntimeException | Error failure) {
            // Never retry a commit whose ownership may already have transferred.
            throw failure;
        } catch (Throwable failure) {
            throw failure(failure);
        }
    }

    Object admit(Object receiver, Object pattern, Object inputs, Object requested, Object binding) {
        try {
            return (Object) admit.invokeExact(receiver, pattern, inputs, requested, binding);
        } catch (RuntimeException | Error failure) {
            // Never retry a commit whose ownership may already have transferred.
            throw failure;
        } catch (Throwable failure) {
            throw failure(failure);
        }
    }

    BigInteger count(Object receiver) {
        try {
            return (BigInteger) (Object) count.invokeExact(receiver);
        } catch (RuntimeException | Error failure) {
            // Never retry a commit whose ownership may already have transferred.
            throw failure;
        } catch (Throwable failure) {
            throw failure(failure);
        }
    }

    boolean commit(Object receiver, Object inputs) {
        try {
            return (boolean) commit.invokeExact(receiver, inputs);
        } catch (RuntimeException | Error failure) {
            // Never retry a commit whose ownership may already have transferred.
            throw failure;
        } catch (Throwable failure) {
            throw failure(failure);
        }
    }

    private static IllegalStateException failure(Throwable failure) {
        return new IllegalStateException("Cannot invoke Useless public batch API", failure);
    }

    static MethodHandle staticMethod(Class<?> owner, String name, Class<?> result,
                                     Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (method.getReturnType() != result
                || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + "." + name
                    + " has an incompatible signature");
        }
        try {
            var adapted = new Class<?>[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                adapted[i] = parameters[i].isPrimitive() ? parameters[i] : Object.class;
            }
            return MethodHandles.lookup().unreflect(method).asType(
                    MethodType.methodType(Object.class, adapted));
        } catch (IllegalAccessException failure) {
            NoSuchMethodException wrapped = new NoSuchMethodException(
                    owner.getName() + "." + name + " is not publicly accessible");
            wrapped.initCause(failure);
            throw wrapped;
        }
    }

    static Object invokeStatic(MethodHandle method, Object pattern) {
        try {
            return (Object) method.invokeExact(pattern);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw failure(failure);
        }
    }

    static Object invokeStatic(MethodHandle method, Object pattern, long count) {
        try {
            return (Object) method.invokeExact(pattern, count);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw failure(failure);
        }
    }
}
