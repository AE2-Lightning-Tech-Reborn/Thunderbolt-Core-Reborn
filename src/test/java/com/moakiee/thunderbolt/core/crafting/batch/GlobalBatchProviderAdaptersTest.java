package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GlobalBatchProviderAdaptersTest {
    private final List<ResourceLocation> registered = new ArrayList<>();
    private void register(String name, int priority, BatchProviderAdapter adapter) {
        var id = ResourceLocation.fromNamespaceAndPath("global_batch_test", name);
        BatchProviderAdapters.register(id, priority, adapter);
        registered.add(id);
    }
    @AfterEach void cleanup() { registered.forEach(BatchProviderAdapters::unregister); }

    @Test void globalOrderIsDeterministicAndExplicitAdapterOverridesIt() {
        var cache = new BatchProviderResolutionCache();
        var provider = new Provider();
        var a = new Endpoint();
        var b = new Endpoint();
        register("b", 0, (p, pattern, job) -> b);
        register("a", 0, (p, pattern, job) -> a);
        assertSame(a, cache.resolve(provider, null, null, null));
        register("priority", 10, (p, pattern, job) -> b);
        assertSame(b, cache.resolve(provider, null, null, null));
        assertSame(a, cache.resolve(provider, null, null, (p, pattern, job) -> a));
        assertNull(cache.resolve(provider, null, null, (p, pattern, job) -> null), "explicit miss must not use globals");
        assertSame(a, cache.resolve(a, null, null, null), "native providers bypass every adapter");
        assertThrows(IllegalArgumentException.class, () -> BatchProviderAdapters.register(registered.getFirst(), (p, d, j) -> null));
        assertThrows(UnsupportedOperationException.class, () -> BatchProviderAdapters.entries().clear());
    }

    @Test void mixedGlobalAdaptersKeepTheirIndividualLifetimesAndCurrentContext() {
        var cache = new BatchProviderResolutionCache();
        var provider = new Provider();
        var stableCalls = new AtomicInteger();
        var tickCalls = new AtomicInteger();
        var dynamicCalls = new AtomicInteger();
        var pattern = proxy(IPatternDetails.class);
        var job = proxy(BatchJobView.class);
        var endpoint = new Endpoint();
        register("stable", 3, new BatchProviderResolver() {
            @Override public boolean cacheResolutionAcrossTicks() { return true; }
            @Override public IBatchCraftingProvider resolve(ICraftingProvider p) { stableCalls.incrementAndGet(); return null; }
        });
        register("tick", 2, (BatchProviderResolver) p -> { tickCalls.incrementAndGet(); return null; });
        register("dynamic", 1, new BatchProviderResolver() {
            @Override public boolean cacheResolutionForTick() { return false; }
            @Override public IBatchCraftingProvider resolve(ICraftingProvider p) { dynamicCalls.incrementAndGet(); return null; }
        });
        register("context", 0, (p, d, j) -> d == pattern && j == job ? endpoint : null);
        for (long tick = 0; tick < 3; tick++) {
            cache.beginTick(tick);
            assertSame(endpoint, cache.resolve(provider, pattern, job, null));
            assertNull(cache.resolve(provider, proxy(IPatternDetails.class), job, null));
            assertNull(cache.resolve(provider, pattern, proxy(BatchJobView.class), null));
        }
        assertEquals(1, stableCalls.get());
        assertEquals(3, tickCalls.get());
        assertEquals(9, dynamicCalls.get());
    }

    @Test void changedRegistrationsInvalidatePositiveAndNegativeCachesWithinTick() {
        var cache = new BatchProviderResolutionCache();
        var provider = new Provider();
        var match = new boolean[1];
        var endpoint = new Endpoint();
        register("changing", 0, new BatchProviderResolver() {
            @Override public boolean cacheResolutionAcrossTicks() { return true; }
            @Override public IBatchCraftingProvider resolve(ICraftingProvider p) { return match[0] ? endpoint : null; }
        });
        cache.beginTick(4);
        assertNull(cache.resolve(provider, null, null, null));
        match[0] = true;
        register("invalidate", -1, (p, d, j) -> null);
        assertSame(endpoint, cache.resolve(provider, null, null, null));
        BatchProviderAdapters.unregister(registered.getFirst());
        assertNull(cache.resolve(provider, null, null, null));
    }

    @Test void eachCpuOwnsItsEndpointAndBeginsEachTickOnce() {
        register("owned", 0, new BatchProviderResolver() {
            @Override public boolean cacheResolutionAcrossTicks() { return true; }
            @Override public IBatchCraftingProvider resolve(ICraftingProvider p) { return new Endpoint(); }
        });
        var a = new BatchProviderResolutionCache();
        var b = new BatchProviderResolutionCache();
        var provider = new Provider();
        a.beginTick(1); b.beginTick(1);
        var first = (Endpoint) a.resolve(provider, null, null, null);
        assertNotSame(first, b.resolve(provider, null, null, null));
        assertSame(first, a.resolve(provider, null, null, null));
        assertEquals(1, first.ticks);
        a.beginTick(2);
        assertSame(first, a.resolve(provider, null, null, null));
        assertEquals(2, first.ticks);
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> null));
    }
    private static class Provider implements ICraftingProvider {
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return false; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { throw new AssertionError(); }
    }
    private static final class Endpoint extends Provider implements IBatchCraftingProvider {
        int ticks;
        @Override public void beginDispatchTick(long tick) { ticks++; }
        @Override public long pushBatch(IPatternDetails p, KeyCounter[] inputs, long count) { return count; }
    }
}
