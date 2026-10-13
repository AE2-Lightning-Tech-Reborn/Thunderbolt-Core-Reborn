package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import org.junit.jupiter.api.Test;

class BatchProviderFilterIterableTest {
    @Test
    void partiallyUsedAvailableProviderCanStillReceiveOrdinaryWork() {
        var available = new Provider(false);
        var full = new Provider(true);
        var ordinary = new Provider(true);
        var excluded = new IdentityHashMap<ICraftingProvider, Boolean>();
        excluded.put(available, true);
        excluded.put(full, true);
        var result = new ArrayList<ICraftingProvider>();
        new BatchProviderFilterIterable(List.of(available, full, ordinary), excluded).forEach(result::add);
        assertEquals(List.of(available, ordinary), result);
        available.busy = true;
        result.clear();
        new BatchProviderFilterIterable(List.of(available, full, ordinary), excluded).forEach(result::add);
        assertEquals(List.of(ordinary), result);
    }

    @Test
    void availableExcludedProviderHasNormalIteratorSemantics() {
        var provider = new Provider(false);
        var excluded = new IdentityHashMap<ICraftingProvider, Boolean>();
        excluded.put(provider, true);
        var iterator = new BatchProviderFilterIterable(List.of(provider), excluded).iterator();
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasNext());
        assertSame(provider, iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void iteratorsHaveIndependentLookaheadAndOnlyQueryExcludedProviders() {
        var ordinary = new Provider(true);
        var batched = new Provider(false);
        var excluded = new IdentityHashMap<ICraftingProvider, Boolean>();
        excluded.put(batched, true);
        var iterable = new BatchProviderFilterIterable(List.of(ordinary, batched), excluded);
        var first = iterable.iterator();
        var second = iterable.iterator();
        assertEquals(0, ordinary.busyChecks + batched.busyChecks);
        assertSame(ordinary, first.next());
        assertTrue(first.hasNext());
        assertTrue(first.hasNext());
        assertEquals(1, batched.busyChecks);
        assertSame(ordinary, second.next());
        assertSame(batched, second.next());
        assertEquals(2, batched.busyChecks);
        assertSame(batched, first.next());
        assertEquals(0, ordinary.busyChecks);
        assertThrows(UnsupportedOperationException.class, first::remove);
    }

    private static final class Provider implements ICraftingProvider {
        boolean busy;
        int busyChecks;
        Provider(boolean busy) { this.busy = busy; }
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { busyChecks++; return busy; }
        @Override public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) { return true; }
    }
}
