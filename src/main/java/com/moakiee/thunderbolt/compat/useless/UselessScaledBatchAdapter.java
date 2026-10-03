package com.moakiee.thunderbolt.compat.useless;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import org.jetbrains.annotations.Nullable;

/** Uses Useless's released Smart Doubling protocol, including already-scaled patterns. */
public final class UselessScaledBatchAdapter implements BatchProviderResolver {
    private final Class<?> providerType;
    private final MethodHandle scale;
    private final MethodHandle unwrap;
    private final MethodHandle operations;
    private final MethodHandle maximum;

    UselessScaledBatchAdapter(Class<?> providerType, Class<?> patterns) throws NoSuchMethodException {
        this.providerType = providerType;
        Method scaleMethod = patterns.getMethod("scale", IPatternDetails.class, long.class);
        Method unwrapMethod = patterns.getMethod("unwrap", IPatternDetails.class);
        Method operationsMethod = patterns.getMethod("operationsPerPush", IPatternDetails.class);
        Method maximumMethod = patterns.getMethod("maximumSafeMultiplier", IPatternDetails.class);
        for (var method : List.of(scaleMethod, unwrapMethod, operationsMethod, maximumMethod)) {
            if (!Modifier.isStatic(method.getModifiers())) throw new NoSuchMethodException(method.toString());
        }
        if (!IPatternDetails.class.isAssignableFrom(scaleMethod.getReturnType())
                || unwrapMethod.getReturnType() != IPatternDetails.class
                || operationsMethod.getReturnType() != long.class || maximumMethod.getReturnType() != long.class) {
            throw new NoSuchMethodException("Incompatible Useless Smart Doubling signatures");
        }
        scale = UselessBatchApi.staticMethod(patterns, "scale", scaleMethod.getReturnType(),
                IPatternDetails.class, long.class);
        unwrap = UselessBatchApi.staticMethod(patterns, "unwrap", unwrapMethod.getReturnType(),
                IPatternDetails.class);
        operations = UselessBatchApi.staticMethod(patterns, "operationsPerPush", operationsMethod.getReturnType(),
                IPatternDetails.class);
        maximum = UselessBatchApi.staticMethod(patterns, "maximumSafeMultiplier", maximumMethod.getReturnType(),
                IPatternDetails.class);
    }

    static @Nullable BatchProviderResolver loadAdapter(ClassLoader loader) {
        try {
            return new UselessScaledBatchAdapter(
                    Class.forName("com.sorrowmist.useless.api.crafting.SmartDoublingCraftingProvider", false, loader),
                    Class.forName("com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.SmartDoublingPatterns", false, loader));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    @Override public boolean cacheResolutionAcrossTicks() { return true; }
    @Override public @Nullable IBatchCraftingProvider resolve(ICraftingProvider provider) {
        return providerType.isInstance(provider) ? new Endpoint(provider) : null;
    }

    private final class Endpoint implements IBatchCraftingProvider {
        private final ICraftingProvider delegate;
        private Endpoint(ICraftingProvider delegate) { this.delegate = delegate; }
        @Override public List<IPatternDetails> getAvailablePatterns() { return delegate.getAvailablePatterns(); }
        @Override public boolean isBusy() { return delegate.isBusy(); }

        @Override public long getBatchCapacity(IPatternDetails details) {
            if (isBusy()) return 0;
            var original = (IPatternDetails) UselessBatchApi.invokeStatic(unwrap, details);
            long factor = (long) UselessBatchApi.invokeStatic(operations, details);
            long limit = (long) UselessBatchApi.invokeStatic(maximum, original);
            return factor > 0 && limit > 0 ? limit / factor : 0;
        }

        @Override public long pushBatch(IPatternDetails details, KeyCounter[] template, long requested) {
            if (requested <= 0) return 0;
            long count = Math.min(requested, getBatchCapacity(details));
            // The actual selected input may differ from the pattern's first alternative.
            for (var slot : template) {
                for (var entry : slot) {
                    long amount = entry.getLongValue();
                    if (amount < 0) return requested;
                    if (amount > 0) count = Math.min(count, Long.MAX_VALUE / amount);
                }
            }
            if (count <= 0) return requested;
            var owned = new KeyCounter[template.length];
            for (int i = 0; i < template.length; i++) {
                owned[i] = new KeyCounter();
                for (var entry : template[i]) owned[i].add(entry.getKey(), Math.multiplyExact(entry.getLongValue(), count));
            }
            var pattern = (IPatternDetails) UselessBatchApi.invokeStatic(scale, details, count);
            // Do not swallow push exceptions: ownership may already have transferred.
            return delegate.pushPattern(pattern, owned) ? requested - count : requested;
        }
    }
}
