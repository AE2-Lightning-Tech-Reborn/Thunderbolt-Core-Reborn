package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import appeng.api.networking.crafting.ICraftingProvider;
import org.junit.jupiter.api.Test;

class BatchProviderFallbackTest {
    @Test
    void aPreviouslyBatchedProviderCanRecoverForOrdinaryDispatch() {
        var busy = new AtomicBoolean(true);
        var provider = (ICraftingProvider) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ICraftingProvider.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isBusy")) return busy.get();
                    throw new UnsupportedOperationException(method.getName());
                });
        var excluded = new IdentityHashMap<ICraftingProvider, Boolean>();
        excluded.put(provider, true);
        var filtered = new BatchProviderFilterIterable(List.of(provider), excluded);
        assertFalse(filtered.iterator().hasNext());
        busy.set(false);
        var recovered = filtered.iterator();
        assertTrue(recovered.hasNext());
        assertSame(provider, recovered.next());
        assertFalse(recovered.hasNext());
    }
}
