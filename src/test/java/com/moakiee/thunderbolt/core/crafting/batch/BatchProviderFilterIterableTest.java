package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import org.junit.jupiter.api.Test;

class BatchProviderFilterIterableTest {
    @Test
    void partialBatchAllowsOrdinaryFallbackAsSoonAsTheProviderIsAvailable() {
        var partiallyUsed = new Provider();
        var full = new Provider();
        full.busy = true;
        var ordinary = new Provider();
        var batched = new IdentityHashMap<ICraftingProvider, Boolean>();
        batched.put(partiallyUsed, true);
        batched.put(full, true);
        var filtered = new BatchProviderFilterIterable(List.of(partiallyUsed, full, ordinary), batched);
        assertEquals(List.of(partiallyUsed, ordinary), collect(filtered));
        partiallyUsed.busy = true;
        full.busy = false;
        assertEquals(List.of(full, ordinary), collect(filtered), "availability is read live for each scan");
        assertEquals(2, batched.size(), "ordinary fallback does not forget the batch accounting");
    }

    private static List<ICraftingProvider> collect(Iterable<ICraftingProvider> providers) {
        var result = new ArrayList<ICraftingProvider>();
        providers.forEach(result::add);
        return result;
    }

    private static final class Provider implements ICraftingProvider {
        boolean busy;
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return busy; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { return false; }
    }
}
