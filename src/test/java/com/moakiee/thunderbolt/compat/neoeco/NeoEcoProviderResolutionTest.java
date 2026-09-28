package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import cn.dancingsnow.neoecoae.api.me.provider.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import org.junit.jupiter.api.Test;

class NeoEcoProviderResolutionTest {
    @Test
    void reusedEndpointReadsTheCurrentJobAndLiveBusyState() {
        var provider = new Provider();
        var endpoint = new NeoEcoFastPathBatchAdapter().resolve(provider);
        assertNotNull(endpoint);
        assertEquals(Long.MAX_VALUE, endpoint.getBatchCapacity(null));
        provider.busy = true;
        assertEquals(0L, endpoint.getBatchCapacity(null));
        provider.busy = false;

        IPatternDetails pattern = (IPatternDetails) Proxy.newProxyInstance(
                IPatternDetails.class.getClassLoader(), new Class<?>[] {IPatternDetails.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInputs" -> new IPatternDetails.IInput[0];
                    case "getOutputs" -> List.of();
                    default -> null;
                });
        var firstReads = new AtomicInteger();
        var secondReads = new AtomicInteger();
        var template = new KeyCounter[] {new KeyCounter()};
        endpoint.pushBatch(pattern, template, 2L, job(firstReads));
        int firstCount = firstReads.get();
        assertTrue(firstCount > 0);
        endpoint.pushBatch(pattern, template, 2L, job(secondReads));
        assertEquals(firstCount, firstReads.get(), "must not retain the previous job");
        assertEquals(firstCount, secondReads.get());
    }

    @Test
    void contextFreeDispatchLeavesInputsWithCaller() {
        var endpoint = new NeoEcoFastPathBatchAdapter().resolve(new Provider());
        var template = new KeyCounter[] {new KeyCounter()};
        assertNotNull(endpoint);
        assertEquals(3L, endpoint.pushBatch(null, template, 3L));
        assertNotNull(template[0]);
        assertEquals(0L, endpoint.pushBatch(null, template, 0L));
    }

    private static BatchJobView job(AtomicInteger reads) {
        return (BatchJobView) Proxy.newProxyInstance(BatchJobView.class.getClassLoader(),
                new Class<?>[] {BatchJobView.class}, (proxy, method, args) -> {
                    reads.incrementAndGet();
                    return null;
                });
    }

    private static final class Provider implements ICraftingProvider, ECOFastPathDispatchProvider {
        private boolean busy;
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return busy; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { return false; }
        @Override public Preparation eco$prepareFastPath(ECOBatchDispatchContext context) {
            throw new AssertionError("unsupported pattern should be rejected before preparation");
        }
    }
}
