package com.moakiee.thunderbolt.compat.useless;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.item.Items;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

class UselessBatchAdapterTest {
    static {
        LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void persistentEndpointRetriesRecoveredTargetOnTheNextTick() throws Exception {
        var provider = new Provider();
        provider.target.success = false;
        var resolver = adapter();
        assertTrue(resolver.cacheResolutionAcrossTicks());
        var endpoint = resolver.resolve(provider);
        endpoint.beginDispatchTick(10);
        assertEquals(9, endpoint.pushBatch(null, inputs(), 10));
        assertEquals(1, endpoint.getBatchCapacity(null));
        endpoint.beginDispatchTick(10);
        assertEquals(1, endpoint.getBatchCapacity(null));
        provider.target.success = true;
        endpoint.beginDispatchTick(11);
        assertEquals(Long.MAX_VALUE, endpoint.getBatchCapacity(null));
        assertEquals(7, endpoint.pushBatch(null, inputs(), 10));
        assertEquals(1, provider.ordinaryCalls);
    }

    private UselessBatchAdapter adapter() throws Exception {
        return new UselessBatchAdapter(new UselessBatchApi(
                Provider.class, Target.class, Capacity.class, Batch.class, Binding.class));
    }

    @Test
    void partialAcceptancePreservesTemplateAndSamePrototypeIdentity() throws Exception {
        var provider = new Provider();
        var input = inputs();
        assertEquals(7, adapter().resolve(provider).pushBatch(null, input, 10));
        assertEquals(2, input[0].get(appeng.api.stacks.AEItemKey.of(Items.STONE)));
        assertEquals(0, provider.ordinaryCalls);
    }

    @Test
    void admissionAppliesRuntimeThrottleOnlyOnce() throws Exception {
        var provider = new Provider();
        provider.target.limit = BigInteger.valueOf(1000);
        provider.target.throttlePercent = 50;
        var input = inputs();
        assertEquals(4, adapter().resolve(provider).pushBatch(null, input, 8));
        assertEquals(BigInteger.valueOf(8), provider.target.offered);
        assertEquals(2, input[0].get(appeng.api.stacks.AEItemKey.of(Items.STONE)));
        assertEquals(0, provider.ordinaryCalls);
    }

    @Test
    void minimumThrottleDoesNotCollapseCpuBatchToOneCopy() throws Exception {
        var provider = new Provider();
        provider.target.limit = BigInteger.valueOf(1000);
        provider.target.throttlePercent = 2;
        assertEquals(251, adapter().resolve(provider).pushBatch(null, inputs(), 256));
        assertEquals(BigInteger.valueOf(256), provider.target.offered);
        assertEquals(0, provider.ordinaryCalls);
    }

    @Test
    void longLimitClampsRemoteCapacity() throws Exception {
        var provider = new Provider();
        provider.target.limit = BigInteger.ONE.shiftLeft(100);
        assertEquals(0, adapter().resolve(provider).pushBatch(null, inputs(), Long.MAX_VALUE));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), provider.target.offered);
    }

    @Test
    void rejectedCommitFallsBackAndDowngradesCapacity() throws Exception {
        var provider = new Provider();
        provider.target.success = false;
        var adapted = adapter().resolve(provider);
        assertEquals(9, adapted.pushBatch(null, inputs(), 10));
        assertEquals(1, provider.ordinaryCalls);
        assertEquals(1, adapted.getBatchCapacity(null));
    }

    @Test
    void thrownCommitNeverRetriesOrdinaryDispatch() throws Exception {
        var provider = new Provider();
        provider.target.failure = new IllegalStateException("ownership uncertain");
        var failure = assertThrows(IllegalStateException.class,
                () -> adapter().resolve(provider).pushBatch(null, inputs(), 10));
        assertSame(provider.target.failure, failure);
        assertEquals(0, provider.ordinaryCalls);
    }

    @Test
    void missingTargetFallsBackAndZeroBudgetDoesNothing() throws Exception {
        var provider = new Provider();
        provider.target = null;
        var adapted = adapter().resolve(provider);
        assertEquals(0, adapted.pushBatch(null, inputs(), 0));
        assertEquals(0, provider.ordinaryCalls);
        assertEquals(9, adapted.pushBatch(null, inputs(), 10));
    }

    @Test
    void incompatibleReturnSignatureIsRejectedBeforeDispatch() {
        assertThrows(NoSuchMethodException.class, () -> new UselessBatchApi(
                Provider.class, Target.class, Capacity.class, String.class, Binding.class));
    }

    private static KeyCounter[] inputs() {
        var slot = new KeyCounter();
        slot.add(appeng.api.stacks.AEItemKey.of(Items.STONE), 2);
        return new KeyCounter[] {slot};
    }

    // Test-owned public contract fixtures. No Useless classes or implementation are bundled.
    public static final class Binding {}
    public record Capacity(BigInteger accepted) {}
    public interface Batch {
        BigInteger count();
        boolean commit(KeyCounter[] prototype);
    }
    public static final class Target {
        BigInteger limit = BigInteger.valueOf(3);
        BigInteger offered;
        int throttlePercent = 100;
        boolean success = true;
        RuntimeException failure;
        public Capacity capacity(IPatternDetails pattern, KeyCounter[] input, BigInteger requested) {
            var accepted = limit.min(requested);
            return new Capacity(accepted.signum() <= 0 ? BigInteger.ZERO
                    : accepted.multiply(BigInteger.valueOf(throttlePercent)).divide(BigInteger.valueOf(100)).max(BigInteger.ONE));
        }
        public Batch admit(IPatternDetails pattern, KeyCounter[] input, BigInteger requested, Binding binding) {
            offered = requested;
            assertNull(binding);
            var accepted = capacity(pattern, input, requested).accepted();
            return new Batch() {
                public BigInteger count() { return accepted; }
                public boolean commit(KeyCounter[] prototype) {
                    assertSame(input, prototype);
                    if (failure != null) throw failure;
                    if (success) prototype[0].clear();
                    return success;
                }
            };
        }
    }
    public static final class Provider implements ICraftingProvider {
        Target target = new Target();
        int ordinaryCalls;
        public Target bigIntegerTarget() { return target; }
        public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        public boolean isBusy() { return false; }
        public boolean pushPattern(IPatternDetails pattern, KeyCounter[] input) {
            ordinaryCalls++;
            assertEquals(2, input[0].get(appeng.api.stacks.AEItemKey.of(Items.STONE)));
            input[0].clear();
            return true;
        }
    }
    @Test
    void oldInstallationWithoutApiDoesNotLoadAdapter() {
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.sorrowmist.useless.api.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        assertNull(UselessBatchCompat.loadAdapter(loader));
    }

    @Test
    void apiMethodLookupRejectsMissingContract() {
        assertThrows(ReflectiveOperationException.class,
                () -> new UselessBatchApi(getClass().getClassLoader()));
    }
}
