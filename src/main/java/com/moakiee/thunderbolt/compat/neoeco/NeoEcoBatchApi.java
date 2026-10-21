package com.moakiee.thunderbolt.compat.neoeco;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Links the Forge concrete-input protocol without loading NeoECO on installations lacking it. */
final class NeoEcoBatchApi {
    private static final String API = "cn.dancingsnow.neoecoae.api.me.";
    private static final String EXECUTION = "cn.dancingsnow.neoecoae.crafting.execution.";
    private final Class<?> capacityType;
    private final Method capacity, push, findParallel, parallelCapacity, parallelPush;
    private final Constructor<?> contextConstructor;
    private final Method reconstruct, outputs, remainders, recipeType;
    private final Method status, accepted, canContinue;
    private final boolean hasReceipts;
    private final Class<?> exactType;
    private final Method prepareExact, exactCount, exactDispatch;

    private NeoEcoBatchApi(ClassLoader loader) throws ReflectiveOperationException {
        capacityType = Class.forName(API + "ECOBatchCapacityProvider", false, loader);
        var context = Class.forName(API + "ECOBatchDispatchContext", false, loader);
        var execution = Class.forName(EXECUTION + "fastpath.ECOExtractedPatternExecution", false, loader);
        contextConstructor = context.getConstructor(IPatternDetails.class, List.class, List.class, List.class,
                Level.class, UUID.class);
        reconstruct = execution.getMethod("fromProviderPush", IPatternDetails.class, KeyCounter[].class, Level.class);
        outputs = execution.getMethod("expectedOutputs");
        remainders = execution.getMethod("expectedContainerItems");
        recipeType = execution.getMethod("fastPathType");
        capacity = capacityType.getMethod("eco$getBatchCapacity", context);
        Method submit = capacityType.getMethod("eco$pushBatch", context, long.class);
        Method state = null, count = null, again = null;
        Method find = null, slots = null, parallelSubmit = null;
        boolean receipts = false;
        try {
            var receipt = Class.forName(EXECUTION + "batch.ECOBatchAdmission", false, loader);
            submit = capacityType.getMethod("eco$pushBatchAdmission", context, long.class);
            state = receipt.getMethod("status");
            count = receipt.getMethod("acceptedCrafts");
            again = receipt.getMethod("canContinue");
            if (submit.getReturnType() != receipt || count.getReturnType() != long.class
                    || again.getReturnType() != boolean.class || !state.getReturnType().isEnum())
                throw new NoSuchMethodException("Incompatible NeoECO ownership receipts");
            receipts = true;
            var parallel = Class.forName(API + "ECOParallelCraftingProvider", false, loader);
            var registry = Class.forName(API + "ECOParallelCraftingProviders", false, loader);
            find = registry.getMethod("find", Object.class);
            slots = parallel.getMethod("eco$getAvailableParallelSlots");
            parallelSubmit = parallel.getMethod("eco$pushPatternBatch", IPatternDetails.class, KeyCounter[].class,
                    long.class, UUID.class);
            if (slots.getReturnType() != long.class || parallelSubmit.getReturnType() != receipt)
                throw new NoSuchMethodException("Incompatible NeoECO parallel contract");
        } catch (ClassNotFoundException absent) {
            if (receipts) throw absent;
            // Earlier Forge 20.5 builds expose the same context with atomic boolean acceptance.
        }
        push = submit;
        status = state;
        accepted = count;
        canContinue = again;
        findParallel = find;
        parallelCapacity = slots;
        parallelPush = parallelSubmit;
        hasReceipts = receipts;
        Class<?> exact = null;
        Method prepare = null, exactCopies = null, dispatch = null;
        try {
            exact = Class.forName(API + "ECOExactBatchProvider", false, loader);
            prepare = exact.getMethod("eco$prepareExactBatch", context, BigInteger.class);
            var preparation = prepare.getReturnType();
            exactCopies = preparation.getMethod("craftCount");
            dispatch = preparation.getMethod("dispatch");
            if (exactCopies.getReturnType() != BigInteger.class
                    || dispatch.getReturnType() != BooleanSupplier.class)
                throw new NoSuchMethodException("Incompatible NeoECO exact preparation");
        } catch (ReflectiveOperationException | LinkageError absent) {
            // Older Forge releases retain their bounded long protocol.
            exact = null;
            prepare = null;
            exactCopies = null;
            dispatch = null;
        }
        exactType = exact;
        prepareExact = prepare;
        exactCount = exactCopies;
        exactDispatch = dispatch;
        if (capacity.getReturnType() != long.class || !recipeType.getReturnType().isEnum()
                || !receipts && push.getReturnType() != boolean.class)
            throw new NoSuchMethodException("Incompatible NeoECO batch protocol");
    }

    static @Nullable NeoEcoBatchApi load(ClassLoader loader) {
        try { return new NeoEcoBatchApi(loader); }
        catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
    }

    boolean hasCapacity(Object provider) { return capacityType.isInstance(provider); }
    @Nullable Object parallel(ICraftingProvider provider) { return findParallel == null ? null : invoke(findParallel, null, provider); }
    long capacity(Object provider, Object context) { return (long) invoke(capacity, provider, context); }
    long parallelCapacity(Object provider) { return (long) invoke(parallelCapacity, provider); }

    boolean hasExact(Object provider) { return exactType != null && exactType.isInstance(provider); }

    @Nullable NeoEcoFastPathCompat.ExactBatch prepareExact(ICraftingProvider provider,
            IPatternDetails pattern, KeyCounter[] inputs, BigInteger requested,
            Level level, UUID craftingId) {
        if (!hasExact(provider) || requested.signum() <= 0) return null;
        var context = context(pattern, inputs, level, craftingId);
        if (context == null) return null;
        var preparation = invoke(prepareExact, provider, context.value(), requested);
        if (preparation == null) return null;
        var count = (BigInteger) invoke(exactCount, preparation);
        if (count == null || count.signum() <= 0 || count.compareTo(requested) > 0)
            throw new IllegalArgumentException("Invalid exact NeoECO admission");
        var dispatch = (BooleanSupplier) invoke(exactDispatch, preparation);
        if (dispatch == null) throw new IllegalArgumentException("Missing exact NeoECO dispatch");
        try {
            return new NeoEcoFastPathCompat.ExactBatch(count, dispatch,
                    BooleanSupplier.class.getMethod("getAsBoolean"));
        } catch (NoSuchMethodException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable Context context(IPatternDetails pattern, KeyCounter[] oneCopy, Level level, UUID jobId) {
        var owned = cloneInputs(oneCopy, 1);
        var execution = invoke(reconstruct, null, pattern, owned, level);
        // Stateful recipes use a different material contract. Keep their ordinary one-copy route.
        if (!((Enum<?>) invoke(recipeType, execution)).name().equals("NORMAL")) return null;
        var expected = List.copyOf((List<GenericStack>) invoke(outputs, execution));
        var containers = List.copyOf((List<GenericStack>) invoke(remainders, execution));
        if (expected.isEmpty()) return null;
        long limit = Long.MAX_VALUE;
        var totals = new KeyCounter();
        for (var slot : owned) for (var entry : slot) add(totals, new GenericStack(entry.getKey(), entry.getLongValue()));
        for (var entry : totals) limit = Math.min(limit, Long.MAX_VALUE / entry.getLongValue());
        totals = new KeyCounter();
        for (var stack : expected) add(totals, stack);
        for (var stack : containers) add(totals, stack);
        for (var entry : totals) limit = Math.min(limit, Long.MAX_VALUE / entry.getLongValue());
        var slots = Arrays.stream(owned).map(slot -> {
            var stacks = new java.util.ArrayList<GenericStack>();
            for (var entry : slot) stacks.add(new GenericStack(entry.getKey(), entry.getLongValue()));
            return List.copyOf(stacks);
        }).toList();
        try {
            var value = contextConstructor.newInstance(pattern, slots, expected, containers, level, jobId);
            return new Context(value, owned, limit);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("NeoECO batch context", failure); }
    }

    Receipt push(Object provider, Object context, long copies) {
        var result = invoke(push, provider, context, copies);
        return hasReceipts ? receipt(result, copies) : new Receipt((boolean) result ? copies : 0, (boolean) result);
    }

    Receipt pushParallel(Object provider, IPatternDetails pattern, KeyCounter[] oneCopy, long copies, UUID jobId) {
        return receipt(invoke(parallelPush, provider, pattern, cloneInputs(oneCopy, copies), copies, jobId), copies);
    }

    private Receipt receipt(Object value, long offered) {
        if (value == null) throw new IllegalStateException("Missing NeoECO ownership receipt");
        String state = ((Enum<?>) invoke(status, value)).name();
        long count = (long) invoke(accepted, value);
        boolean again = (boolean) invoke(canContinue, value);
        if (state.equals("REJECTED") && count == 0) return new Receipt(0, false);
        if (!state.equals("ACCEPTED") || count <= 0 || count > offered)
            throw new IllegalStateException("Invalid or indeterminate NeoECO ownership receipt");
        return new Receipt(count, again);
    }

    static KeyCounter[] cloneInputs(KeyCounter[] template, long copies) {
        var result = new KeyCounter[template.length];
        for (int slot = 0; slot < template.length; slot++) {
            result[slot] = new KeyCounter();
            for (var entry : template[slot])
                add(result[slot], new GenericStack(entry.getKey(), Math.multiplyExact(entry.getLongValue(), copies)));
        }
        return result;
    }

    private static void add(KeyCounter target, GenericStack stack) {
        if (stack.what() == null || stack.amount() <= 0) throw new IllegalArgumentException("Invalid per-copy stack");
        Math.addExact(target.get(stack.what()), stack.amount());
        target.add(stack.what(), stack.amount());
    }

    private static Object invoke(Method method, Object owner, Object... arguments) {
        try { return method.invoke(owner, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("NeoECO batch call failed: " + method.getName(), failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("NeoECO batch linkage", failure); }
    }

    record Context(Object value, KeyCounter[] inputs, long limit) {}
    record Receipt(long accepted, boolean canContinue) {}
}
