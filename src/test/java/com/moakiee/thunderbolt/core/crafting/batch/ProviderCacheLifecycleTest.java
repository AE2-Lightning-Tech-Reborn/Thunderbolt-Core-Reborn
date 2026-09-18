package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import org.junit.jupiter.api.Test;

class ProviderCacheLifecycleTest {
    @Test
    void stableNetworkBuildsOnceAcrossOneThousandTicksAndKeepsCapacityLive() {
        var schedule = new TickProviderDispatchSchedule();
        var network = new Object();
        var pattern = pattern();
        var providers = new ArrayList<ICraftingProvider>();
        for (int i = 0; i < 64; i++) providers.add(new Provider());
        var scans = new AtomicInteger();
        var resolves = new AtomicInteger();
        var tickStarts = new AtomicInteger();
        BatchProviderResolver resolver = persistent(provider -> {
            resolves.incrementAndGet();
            return providers.indexOf(provider) % 2 == 0 ? new Endpoint(tickStarts) : null;
        });
        Supplier<Iterable<ICraftingProvider>> source = () -> { scans.incrementAndGet(); return providers; };
        Iterable<ICraftingProvider> snapshot = null;
        for (int tick = 0; tick < 1_000; tick++) {
            schedule.beginTick(tick);
            var candidates = schedule.candidates(network, 1, pattern, source);
            if (snapshot != null) assertSame(snapshot, candidates);
            snapshot = candidates;
            for (var provider : candidates) {
                var endpoint = schedule.batchProviders().resolve(provider, pattern, null, resolver);
                assertSame(endpoint, schedule.batchProviders().resolve(provider, null, null, resolver));
                if (endpoint instanceof Endpoint e) {
                    e.busy = true;
                    assertEquals(0, e.getBatchCapacity(pattern));
                    e.busy = false;
                    assertEquals(Long.MAX_VALUE, e.getBatchCapacity(pattern));
                }
            }
        }
        assertEquals(1, scans.get(), "provider enumeration is independent of tick count");
        assertEquals(64, resolves.get(), "positive and negative capability results both survive ticks");
        assertEquals(32_000, tickStarts.get(), "reset each used endpoint once per tick, never per dispatch");
        System.out.println("STABLE_CACHE: ticks=1000 providers=64 enumerations=1 resolutions=64 (tick-cache baseline: 1000 / 64000)");
    }

    @Test
    void sameTickChangesAndGridReplacementInvalidateCandidatesAndCapabilities() {
        var schedule = new TickProviderDispatchSchedule();
        Object network = new Object();
        var pattern = pattern();
        var old = new Provider();
        var replacement = new Provider();
        var resolves = new AtomicInteger();
        var resolver = persistent(p -> { resolves.incrementAndGet(); return null; });
        schedule.beginTick(10);
        var first = schedule.candidates(network, 1, pattern, () -> List.of(old));
        schedule.batchProviders().resolve(old, pattern, null, resolver);
        schedule.recordFailure(pattern, old);
        assertTrue(schedule.isBlocked(pattern, old));
        var refreshed = schedule.candidates(network, 2, pattern, () -> List.of(replacement));
        assertNotSame(first, refreshed);
        assertEquals(List.of(replacement), copy(refreshed));
        assertFalse(schedule.isBlocked(pattern, old));
        schedule.batchProviders().resolve(old, pattern, null, resolver);
        assertEquals(2, resolves.get(), "refresh invalidates even a negative resolution");
        var migrated = schedule.candidates(new Object(), 2, pattern, () -> List.of(old));
        assertEquals(List.of(old), copy(migrated), "same revision on another network is unrelated");
    }

    @Test
    void rejectionRecoversNextTickWithoutRebuildingAndSuccessfulProviderRemainsFirst() {
        var schedule = new TickProviderDispatchSchedule();
        var network = new Object();
        var pattern = pattern();
        var first = new Provider();
        var second = new Provider();
        var scans = new AtomicInteger();
        Supplier<Iterable<ICraftingProvider>> source = () -> {
            scans.incrementAndGet(); return List.of(first, second);
        };
        schedule.beginTick(1);
        var candidates = schedule.candidates(network, 0, pattern, source);
        schedule.recordFailure(pattern, first);
        schedule.recordFailure(pattern, first);
        schedule.recordSuccess(pattern, second);
        assertEquals(List.of(second), copy(candidates));
        schedule.beginTick(1);
        assertEquals(List.of(second), copy(schedule.candidates(network, 0, pattern, source)));
        schedule.beginTick(2);
        assertFalse(schedule.isBlocked(pattern, first));
        assertSame(candidates, schedule.candidates(network, 0, pattern, source));
        assertEquals(List.of(second, first), copy(candidates));
        schedule.recordFailure(pattern, second);
        schedule.recordFailure(pattern, first);
        assertTrue(copy(candidates).isEmpty());
        schedule.beginTick(3);
        assertEquals(2, copy(schedule.candidates(network, 0, pattern, source)).size());
        assertEquals(1, scans.get());
    }

    @Test
    void boundedLruReleasesOldPatternAndProviderIdentities() {
        var schedule = new TickProviderDispatchSchedule();
        var network = new Object();
        var first = pattern();
        var scans = new AtomicInteger();
        Supplier<Iterable<ICraftingProvider>> source = () -> { scans.incrementAndGet(); return List.of(); };
        schedule.candidates(network, 0, first, source);
        for (int i = 0; i < TickProviderDispatchSchedule.MAX_PATTERNS; i++) {
            schedule.candidates(network, 0, pattern(), source);
        }
        schedule.candidates(network, 0, first, source);
        assertEquals(TickProviderDispatchSchedule.MAX_PATTERNS + 2, scans.get());
        var cache = schedule.batchProviders();
        var resolves = new AtomicInteger();
        var resolver = persistent(p -> { resolves.incrementAndGet(); return null; });
        var original = new Provider();
        cache.resolve(original, null, null, resolver);
        for (int i = 0; i < BatchProviderResolutionCache.MAX_PROVIDERS_PER_RESOLVER; i++) {
            cache.resolve(new Provider(), null, null, resolver);
        }
        cache.resolve(original, null, null, resolver);
        assertEquals(BatchProviderResolutionCache.MAX_PROVIDERS_PER_RESOLVER + 2, resolves.get());
    }

    @Test
    void changedTickFallbackAndLegacyResolversDoNotBecomePersistent() {
        var schedule = new TickProviderDispatchSchedule();
        var sourceIdentity = new Object();
        var pattern = pattern();
        var provider = new Provider();
        var scans = new AtomicInteger();
        var resolves = new AtomicInteger();
        BatchProviderResolver legacy = p -> { resolves.incrementAndGet(); return null; };
        for (int tick = 0; tick < 3; tick++) {
            schedule.beginTick(tick);
            schedule.candidates(sourceIdentity, tick, pattern, () -> {
                scans.incrementAndGet(); return List.of(provider);
            });
            schedule.batchProviders().resolve(provider, pattern, null, legacy);
            schedule.batchProviders().resolve(provider, pattern, null, legacy);
        }
        assertEquals(3, scans.get());
        assertEquals(3, resolves.get());
    }

    private static BatchProviderResolver persistent(java.util.function.Function<ICraftingProvider,
            IBatchCraftingProvider> resolve) {
        return new BatchProviderResolver() {
            @Override public IBatchCraftingProvider resolve(ICraftingProvider p) { return resolve.apply(p); }
            @Override public boolean cacheResolutionAcrossTicks() { return true; }
        };
    }

    private static IPatternDetails pattern() {
        return (IPatternDetails) Proxy.newProxyInstance(IPatternDetails.class.getClassLoader(),
                new Class<?>[] {IPatternDetails.class}, (p, m, a) -> null);
    }

    private static List<ICraftingProvider> copy(Iterable<ICraftingProvider> input) {
        var result = new ArrayList<ICraftingProvider>(); input.forEach(result::add); return result;
    }

    private static class Provider implements ICraftingProvider {
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return false; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { return false; }
    }

    private static final class Endpoint extends Provider implements IBatchCraftingProvider {
        private final AtomicInteger tickStarts;
        private boolean busy;
        Endpoint(AtomicInteger tickStarts) { this.tickStarts = tickStarts; }
        @Override public void beginDispatchTick(long tick) { tickStarts.incrementAndGet(); }
        @Override public boolean isBusy() { return busy; }
        @Override public long pushBatch(IPatternDetails p, KeyCounter[] inputs, long copies) { return copies; }
    }
}
