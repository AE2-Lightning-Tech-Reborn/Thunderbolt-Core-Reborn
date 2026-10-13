package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;

import appeng.api.networking.crafting.ICraftingProvider;

public final class BatchProviderFilterIterable implements Iterable<ICraftingProvider> {
    private final Iterable<ICraftingProvider> raw;
    private final IdentityHashMap<ICraftingProvider, Boolean> excluded;

    public BatchProviderFilterIterable(Iterable<ICraftingProvider> raw,
                                       IdentityHashMap<ICraftingProvider, Boolean> excluded) {
        this.raw = raw;
        this.excluded = excluded;
    }

    @Override
    public Iterator<ICraftingProvider> iterator() {
        var iterator = raw.iterator();
        return new Iterator<>() {
            private ICraftingProvider next;
            private boolean ready;

            @Override
            public boolean hasNext() {
                while (!ready && iterator.hasNext()) {
                    var candidate = iterator.next();
                    // Partial batches do not exclude an available provider from ordinary work.
                    if (!excluded.containsKey(candidate) || !candidate.isBusy()) {
                        next = candidate;
                        ready = true;
                    }
                }
                return ready;
            }

            @Override
            public ICraftingProvider next() {
                if (!hasNext()) throw new NoSuchElementException();
                ready = false;
                var result = next;
                next = null;
                return result;
            }
        };
    }
}
