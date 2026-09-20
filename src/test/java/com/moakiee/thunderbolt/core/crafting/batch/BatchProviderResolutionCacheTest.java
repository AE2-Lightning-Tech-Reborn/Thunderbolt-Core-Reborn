package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import org.junit.jupiter.api.Test;

class BatchProviderResolutionCacheTest {
    @Test
    void cachesHitsAndMissesAcrossPatternsAndJobsUntilNextTick() {
        var schedule = new TickProviderDispatchSchedule();
        var supported = new Provider();
        var unsupported = new Provider();
        var endpoint = new Endpoint();
        var calls = new AtomicInteger();
        BatchProviderResolver resolver = provider -> {
            calls.incrementAndGet();
            return provider == supported ? endpoint : null;
        };
        schedule.beginTick(10L);
        var cache = schedule.batchProviders();
        for (int i = 0; i < 100; i++) {
            assertSame(endpoint, cache.resolve(supported, proxy(IPatternDetails.class), proxy(BatchJobView.class), resolver));
            assertNull(cache.resolve(unsupported, null, null, resolver));
        }
        assertEquals(2, calls.get());
        schedule.beginTick(10L);
        assertSame(endpoint, cache.resolve(supported, null, null, resolver));
        assertEquals(2, calls.get());
        schedule.beginTick(11L);
        assertSame(endpoint, cache.resolve(supported, null, null, resolver));
        assertNull(cache.resolve(unsupported, null, null, resolver));
        assertEquals(4, calls.get());
    }

    @Test
    void scopesResolutionByProviderAndAdapterIdentity() {
        var cache = new BatchProviderResolutionCache();
        var first = new Provider();
        var second = new Provider();
        var a = new Endpoint();
        var b = new Endpoint();
        BatchProviderResolver resolverA = provider -> provider == first ? a : null;
        BatchProviderResolver resolverB = provider -> b;
        assertSame(a, cache.resolve(first, null, null, resolverA));
        assertNull(cache.resolve(second, null, null, resolverA));
        assertSame(b, cache.resolve(first, null, null, resolverB));
        assertNull(cache.resolve(first, null, null, null));
    }

    @Test
    void nativeProvidersBypassAdaptersAndKeepCapacityLive() {
        var cache = new BatchProviderResolutionCache();
        var nativeProvider = new Endpoint();
        BatchProviderResolver resolver = provider -> { throw new AssertionError("native priority"); };
        var resolved = cache.resolve(nativeProvider, null, null, resolver);
        assertSame(nativeProvider, resolved);
        assertEquals(Long.MAX_VALUE, resolved.getBatchCapacity(null));
        nativeProvider.busy = true;
        assertEquals(0L, resolved.getBatchCapacity(null));
    }

    @Test
    void dynamicResolversCanOptOutAndRecoverFromMissWithinTick() {
        var cache = new BatchProviderResolutionCache();
        var provider = new Provider();
        var endpoint = new Endpoint();
        var calls = new AtomicInteger();
        BatchProviderResolver resolver = new BatchProviderResolver() {
            @Override public boolean cacheResolutionForTick() { return false; }
            @Override public IBatchCraftingProvider resolve(ICraftingProvider candidate) {
                return calls.incrementAndGet() == 1 ? null : endpoint;
            }
        };
        assertNull(cache.resolve(provider, null, null, resolver));
        assertSame(endpoint, cache.resolve(provider, null, null, resolver));
        assertEquals(2, calls.get());
    }

    @Test
    void legacyAdaptersAlwaysReceiveCurrentPatternAndJob() {
        var cache = new BatchProviderResolutionCache();
        var provider = new Provider();
        var firstPattern = proxy(IPatternDetails.class);
        var secondPattern = proxy(IPatternDetails.class);
        var firstJob = proxy(BatchJobView.class);
        var secondJob = proxy(BatchJobView.class);
        var calls = new AtomicInteger();
        BatchProviderAdapter adapter = (candidate, pattern, job) -> {
            assertSame(provider, candidate);
            boolean first = calls.getAndIncrement() == 0;
            assertSame(first ? firstPattern : secondPattern, pattern);
            assertSame(first ? firstJob : secondJob, job);
            return null;
        };
        cache.resolve(provider, firstPattern, firstJob, adapter);
        cache.resolve(provider, secondPattern, secondJob, adapter);
        assertEquals(2, calls.get());
    }

    @Test
    void resolutionFailureDoesNotPoisonCache() {
        var cache = new BatchProviderResolutionCache();
        var provider = new Provider();
        var endpoint = new Endpoint();
        var calls = new AtomicInteger();
        BatchProviderResolver resolver = candidate -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("temporary");
            return endpoint;
        };
        assertThrows(IllegalStateException.class, () -> cache.resolve(provider, null, null, resolver));
        assertSame(endpoint, cache.resolve(provider, null, null, resolver));
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, args) -> null));
    }

    private static class Provider implements ICraftingProvider {
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return false; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { return false; }
        // Deliberately equal: capabilities belong to instances, not equals() or provider classes.
        @Override public boolean equals(Object other) { return other instanceof Provider; }
        @Override public int hashCode() { return 1; }
    }

    private static final class Endpoint extends Provider implements IBatchCraftingProvider {
        private boolean busy;
        @Override public boolean isBusy() { return busy; }
        @Override public long pushBatch(IPatternDetails pattern, KeyCounter[] inputs, long count) { return count; }
    }
}
