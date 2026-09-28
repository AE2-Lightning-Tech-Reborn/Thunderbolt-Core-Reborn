package com.moakiee.thunderbolt.compat.neoeco;

import com.moakiee.thunderbolt.core.crafting.support.CraftingPatternDelegates;
import com.moakiee.thunderbolt.core.crafting.batch.ParallelBatchCpuHelper;
import com.moakiee.thunderbolt.core.crafting.pattern.PlannedInputPattern;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.world.item.Items;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import net.minecraft.world.level.Level;
import appeng.api.stacks.GenericStack;
import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import cn.dancingsnow.neoecoae.api.me.provider.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import org.junit.jupiter.api.Test;

class NeoEcoFastPathBatchAdapterTest {
    static {
        LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void persistentEndpointResetsOnlyItsTickLocalFallback() {
        var resolver = new NeoEcoFastPathBatchAdapter();
        assertTrue(resolver.cacheResolutionAcrossTicks());
        var provider = new OrdinaryProvider(true);
        var endpoint = new NeoEcoFastPathBatchAdapter.AdaptedProvider(provider);
        endpoint.beginDispatchTick(10);
        assertEquals(1, endpoint.pushBatch(null, new KeyCounter[] {new KeyCounter()}, 2, job));
        assertEquals(1, endpoint.getBatchCapacity(null));
        endpoint.beginDispatchTick(10);
        assertEquals(1, endpoint.getBatchCapacity(null));
        endpoint.beginDispatchTick(11);
        assertEquals(Long.MAX_VALUE, endpoint.getBatchCapacity(null));
    }

    private final BatchJobView job = (BatchJobView) Proxy.newProxyInstance(
            BatchJobView.class.getClassLoader(), new Class<?>[] {BatchJobView.class},
            (proxy, method, args) -> null);

    @Test
    void declinedFastPathFallbackKeepsThePlannedMaterialAndRefundsOnlyUnacceptedCopies() {
        var a = appeng.api.stacks.AEItemKey.of(Items.STONE);
        var b = appeng.api.stacks.AEItemKey.of(Items.SAND);
        var source = new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return null; }
            @Override public IInput[] getInputs() {
                return new IInput[] {new IInput() {
                    @Override public GenericStack[] getPossibleInputs() {
                        return new GenericStack[] {
                                new GenericStack(b, 1), new GenericStack(a, 1)};
                    }
                    @Override public long getMultiplier() { return 1; }
                    @Override public boolean isValid(AEKey key, Level level) { return true; }
                    @Override public AEKey getRemainingKey(AEKey key) { return null; }
                }};
            }
            @Override public List<GenericStack> getOutputs() { return List.of(); }
        };
        var planned = new PlannedInputPattern(
                source, List.of(Map.of(a, 1L)));
        var inventory = new ListCraftingInventory(key -> {});
        inventory.insert(a, 2, Actionable.MODULATE);
        inventory.insert(b, 2, Actionable.MODULATE);
        var result = ParallelBatchCpuHelper.bulkExtract(
                planned, inventory, 2, true, Map.of(), null);
        assertNotNull(result);
        var provider = new NativeProvider();
        var adapter = new NeoEcoFastPathBatchAdapter.AdaptedProvider(provider);
        var template = ParallelBatchCpuHelper.cloneSingleCopy(result);
        long leftover = adapter.pushBatch(
                CraftingPatternDelegates.forBatchExecution(planned),
                template, result.actualCopies, job);
        assertEquals(1, leftover);
        assertSame(source, ((OrdinaryProvider) provider).receivedPattern);
        assertEquals(1, ((OrdinaryProvider) provider).receivedSlot.get(a));
        assertEquals(0, ((OrdinaryProvider) provider).receivedSlot.get(b));
        ParallelBatchCpuHelper.markDispatched(result, 1);
        ParallelBatchCpuHelper.reinject(result, leftover, inventory);
        assertEquals(1, inventory.list.get(a));
        assertEquals(2, inventory.list.get(b));
    }

    @Test
    void providerWithoutAllocatedFastPathStillReceivesOneOrdinaryCopy() {
        var provider = new OrdinaryProvider(true);
        var adapter = new NeoEcoFastPathBatchAdapter.AdaptedProvider(provider);
        var template = new KeyCounter[] {new KeyCounter()};

        assertEquals(6L, adapter.pushBatch(null, template, 7L, job));
        assertEquals(1, provider.calls);
        assertNotSame(template, provider.received);
        assertNotSame(template[0], provider.receivedSlot);
        assertNotNull(template[0], "the provider must not mutate the borrowed template array");
    }

    @Test
    void ordinaryProviderIsNotAdvertisedAsAnAllocatedBatchEndpoint() {
        assertNull(new NeoEcoFastPathBatchAdapter().resolve(new OrdinaryProvider(true)));
    }

    @Test
    void singleCopyFallbackDowngradesOnlyThisPatternForTheCurrentEndpoint() {
        var provider = new OrdinaryProvider(true);
        var adapter = new NeoEcoFastPathBatchAdapter.AdaptedProvider(provider);
        IPatternDetails otherPattern = (IPatternDetails) Proxy.newProxyInstance(
                IPatternDetails.class.getClassLoader(), new Class<?>[] {IPatternDetails.class},
                (proxy, method, args) -> null);

        assertEquals(Long.MAX_VALUE, adapter.getBatchCapacity(null));
        assertEquals(99L, adapter.pushBatch(null, new KeyCounter[] {new KeyCounter()}, 100L, job));
        assertEquals(1L, adapter.getBatchCapacity(null),
                "later visits must not extract and refund another 100-copy batch");
        assertEquals(Long.MAX_VALUE, adapter.getBatchCapacity(otherPattern));
        assertEquals(Long.MAX_VALUE,
                new NeoEcoFastPathBatchAdapter.AdaptedProvider(provider).getBatchCapacity(null),
                "next tick must be allowed to retry FastPath");
    }

    @Test
    void rejectedOrdinaryCopyLeavesTheWholeBatchForCallerRollback() {
        var provider = new OrdinaryProvider(false);
        var adapter = new NeoEcoFastPathBatchAdapter.AdaptedProvider(provider);

        assertEquals(7L, adapter.pushBatch(null, new KeyCounter[] {new KeyCounter()}, 7L, job));
        assertEquals(1, provider.calls);
    }

    @Test
    void singleCopyOrderCanCompleteWithoutFastPath() {
        var provider = new OrdinaryProvider(true);
        var adapter = new NeoEcoFastPathBatchAdapter.AdaptedProvider(provider);

        assertEquals(0L, adapter.pushBatch(null, new KeyCounter[] {new KeyCounter()}, 1L, job));
        assertEquals(1, provider.calls);
    }

    @Test
    void zeroCopyBudgetNeverPushesTheProvider() {
        var provider = new OrdinaryProvider(true);
        var adapter = new NeoEcoFastPathBatchAdapter.AdaptedProvider(provider);

        assertEquals(0L, adapter.pushBatch(null, new KeyCounter[0], 0L, job));
        assertEquals(0, provider.calls);
    }

    @Test
    void nativeFastPathProviderAlsoFallsBackForUnsupportedPattern() {
        var provider = new NativeProvider();
        IPatternDetails pattern = (IPatternDetails) Proxy.newProxyInstance(
                IPatternDetails.class.getClassLoader(), new Class<?>[] {IPatternDetails.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInputs" -> new IPatternDetails.IInput[0];
                    case "getOutputs" -> List.of();
                    default -> null;
                });
        var adapter = new NeoEcoFastPathBatchAdapter().adapt(provider, pattern, job);

        assertNotNull(adapter);
        assertEquals(2L, adapter.pushBatch(pattern, new KeyCounter[] {new KeyCounter()}, 3L, job));
        assertEquals(1, ((OrdinaryProvider) provider).calls);
        assertEquals(1L, adapter.getBatchCapacity(pattern));
    }

    private static class OrdinaryProvider implements ICraftingProvider {
        private final boolean accepted;
        private int calls;
        private KeyCounter[] received;
        private KeyCounter receivedSlot;
        private IPatternDetails receivedPattern;

        private OrdinaryProvider(boolean accepted) {
            this.accepted = accepted;
        }

        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return false; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
            calls++;
            receivedPattern = pattern;
            received = inputs;
            receivedSlot = inputs[0];
            if (accepted) inputs[0] = null;
            return accepted;
        }
    }

    private static final class NativeProvider extends OrdinaryProvider implements ECOFastPathDispatchProvider {
        private NativeProvider() { super(true); }
        @Override public Preparation eco$prepareFastPath(ECOBatchDispatchContext context) {
            throw new AssertionError("unsupported recipe must be declined before batch preparation");
        }
    }
}
