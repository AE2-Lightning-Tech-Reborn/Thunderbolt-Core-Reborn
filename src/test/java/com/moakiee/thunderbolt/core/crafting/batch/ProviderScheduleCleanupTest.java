package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProviderScheduleCleanupTest {
    enum Access { IS_BLOCKED, BLOCKED_COUNT, RECORD_FAILURE, RECORD_SUCCESS }

    @ParameterizedTest
    @EnumSource(Access.class)
    void eachAccessorRestoresRejectedCandidatesWithoutReenumeration(Access access) {
        var schedule = new TickProviderDispatchSchedule();
        var pattern = pattern();
        var network = new Object();
        var a = new Provider();
        var b = new Provider();
        var scans = new AtomicInteger();
        Supplier<Iterable<ICraftingProvider>> source = () -> {
            scans.incrementAndGet();
            return Arrays.asList(a, null, b, a);
        };
        schedule.beginTick(1);
        var candidates = schedule.candidates(network, 1, pattern, source);
        assertIdentities(List.of(a, b), candidates);
        schedule.recordSuccess(pattern, a);
        schedule.recordFailure(pattern, a);
        assertIdentities(List.of(b), candidates);
        schedule.beginTick(2);
        switch (access) {
            case IS_BLOCKED -> assertFalse(schedule.isBlocked(pattern, a));
            case BLOCKED_COUNT -> assertEquals(0, schedule.blockedCount(pattern));
            case RECORD_FAILURE -> schedule.recordFailure(pattern, b);
            case RECORD_SUCCESS -> schedule.recordSuccess(pattern, a);
        }
        var expected = switch (access) {
            case IS_BLOCKED, BLOCKED_COUNT -> List.of(b, a);
            case RECORD_FAILURE -> List.of(a);
            case RECORD_SUCCESS -> List.of(a, b);
        };
        assertIdentities(expected, candidates);
        assertSame(candidates, schedule.candidates(network, 1, pattern, source));
        assertEquals(1, scans.get());
        assertEquals(access == Access.RECORD_FAILURE ? 1 : 0, schedule.blockedCount(pattern));
    }

    @Test
    void diagnosticsAndOutcomeUpdatesDoNotPromotePatternsInTheLru() {
        var schedule = new TickProviderDispatchSchedule();
        var network = new Object();
        var first = pattern();
        var provider = new Provider();
        var firstScans = new AtomicInteger();
        Supplier<Iterable<ICraftingProvider>> firstSource = () -> {
            firstScans.incrementAndGet();
            return List.of(provider);
        };
        schedule.beginTick(1);
        schedule.candidates(network, 1, first, firstSource);
        for (int i = 1; i < TickProviderDispatchSchedule.MAX_PATTERNS; i++)
            schedule.candidates(network, 1, pattern(), List::of);
        assertFalse(schedule.isBlocked(first, provider));
        assertEquals(0, schedule.blockedCount(first));
        schedule.recordFailure(first, provider);
        schedule.recordSuccess(first, provider);
        schedule.candidates(network, 1, pattern(), List::of);
        assertIdentities(List.of(provider), schedule.candidates(network, 1, first, firstSource));
        assertEquals(2, firstScans.get(), "only candidate selection promotes LRU entries");
        var absent = pattern();
        assertFalse(schedule.isBlocked(absent, provider));
        assertEquals(0, schedule.blockedCount(absent));
        schedule.recordFailure(absent, provider);
        schedule.recordSuccess(absent, provider);
        assertFalse(schedule.isBlocked(absent, provider));
    }

    @Test
    void blockingUnknownIdentitiesDoesNotInjectThemDuringRestore() {
        var a = new Provider();
        var b = new Provider();
        var c = new Provider();
        var absent = new Provider();
        var queue = new IdentityCandidateQueue<>(Arrays.asList(a, null, b, a, c));
        var iterator = queue.iterator();
        assertSame(a, iterator.next());
        queue.markSuccess(a);
        queue.block(a);
        queue.block(a);
        queue.block(c);
        queue.block(absent);
        queue.block(null);
        assertEquals(1, queue.activeCount());
        assertEquals(4, queue.blockedCount());
        assertSame(b, iterator.next());
        assertFalse(iterator.hasNext());
        queue.restoreBlocked();
        assertIdentities(List.of(b, a, c), queue);
        assertEquals(3, queue.activeCount());
        assertEquals(0, queue.blockedCount());
        assertFalse(queue.isBlocked(absent));
        queue.restoreBlocked();
        assertIdentities(List.of(b, a, c), queue);
    }

    private static void assertIdentities(List<Provider> expected, Iterable<? extends ICraftingProvider> actual) {
        var observed = new ArrayList<ICraftingProvider>();
        actual.forEach(observed::add);
        assertEquals(expected.size(), observed.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), observed.get(i));
    }

    private static IPatternDetails pattern() {
        return (IPatternDetails) Proxy.newProxyInstance(IPatternDetails.class.getClassLoader(),
                new Class<?>[] {IPatternDetails.class}, (proxy, method, args) -> {
                    throw new AssertionError("pattern equality/hash must not be queried");
                });
    }

    private static class Provider implements ICraftingProvider {
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return false; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { return false; }
        @Override public boolean equals(Object other) { return other instanceof Provider; }
        @Override public int hashCode() { return 1; }
    }
}
