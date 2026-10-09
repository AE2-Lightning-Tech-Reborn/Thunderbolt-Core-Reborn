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
    void persistentEndpointRetriesAfterTemporaryRejectionEvenWithUnknownTick() throws Exception {
        var provider = new Provider();
        provider.target.success = false;
        var resolver = adapter();
        assertTrue(resolver.cacheResolutionAcrossTicks());
        var endpoint = resolver.resolve(provider);
        endpoint.beginDispatchTick(Long.MIN_VALUE);
        assertEquals(9, endpoint.pushBatch(null, inputs(), 10));
        assertEquals(Long.MAX_VALUE, endpoint.getBatchCapacity(null));
        provider.target.success = true;
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
    void rejectedCommitFallsBackWithoutDowngradingCapacity() throws Exception {
        var provider = new Provider();
        provider.target.success = false;
        var adapted = adapter().resolve(provider);
        assertEquals(9, adapted.pushBatch(null, inputs(), 10));
        assertEquals(1, provider.ordinaryCalls);
        assertEquals(Long.MAX_VALUE, adapted.getBatchCapacity(null));
    }

    @Test
    void preparedAdmissionLimitsExtractionAndDoesNotApplyThrottleTwice() throws Exception {
        var provider = new Provider();
        provider.target.limit = BigInteger.valueOf(1000);
        provider.target.throttlePercent = 50;
        var input = inputs();
        var prepared = adapter().resolve(provider).prepareBatch(null, input, 8, null);
        assertEquals(4, prepared.capacity());
        assertEquals(0, provider.target.committed);
        assertEquals(0, prepared.push(4));
        assertEquals(BigInteger.valueOf(8), provider.target.offered);
        assertEquals(1, provider.target.admissions);
        assertEquals(4, provider.target.committed);
        assertEquals(2, input[0].get(appeng.api.stacks.AEItemKey.of(Items.STONE)));
        assertThrows(IllegalStateException.class, () -> prepared.push(4));
    }

    @Test
    void reducedCpuOfferCannotCommitTheLargerPreparedBatch() throws Exception {
        var provider = new Provider();
        provider.target.limit = BigInteger.valueOf(1000);
        var endpoint = adapter().resolve(provider);
        var prepared = endpoint.prepareBatch(null, inputs(), 8, null);
        assertEquals(8, prepared.capacity());
        assertEquals(0, prepared.push(2));
        assertEquals(2, provider.target.committed);
        assertEquals(BigInteger.valueOf(2), provider.target.offered);
    }

    @Test
    void zeroCapacityAndRejectedAdmissionDoNotPoisonRecoveredMachine() throws Exception {
        var provider = new Provider();
        var endpoint = adapter().resolve(provider);
        provider.target.limit = BigInteger.ZERO;
        assertEquals(0, endpoint.prepareBatch(null, inputs(), 10, null).capacity());
        assertEquals(0, provider.ordinaryCalls);
        provider.target.limit = BigInteger.valueOf(3);
        provider.target.rejectAdmission = true;
        assertEquals(0, endpoint.prepareBatch(null, inputs(), 10, null).capacity());
        provider.target.rejectAdmission = false;
        assertEquals(3, endpoint.prepareBatch(null, inputs(), 10, null).capacity());
        assertEquals(7, endpoint.pushBatch(null, inputs(), 10));
    }

    @Test
    void busyMachineAndMissingTargetAreRecheckedLive() throws Exception {
        var provider = new Provider();
        var endpoint = adapter().resolve(provider);
        provider.busy = true;
        assertEquals(0, endpoint.getBatchCapacity(null));
        assertEquals(0, endpoint.prepareBatch(null, inputs(), 10, null).capacity());
        provider.busy = false;
        var target = provider.target;
        provider.target = null;
        assertEquals(1, endpoint.getBatchCapacity(null));
        provider.target = target;
        assertEquals(3, endpoint.prepareBatch(null, inputs(), 10, null).capacity());
    }

    @Test
    void executorReservesOnlyAdmittedCopiesAndAccountsEveryOutputAcrossTicks() throws Exception {
        var provider = new Provider();
        var resolver = adapter();
        var pattern = pattern();
        var schedule = new com.moakiee.thunderbolt.core.crafting.batch.TickProviderDispatchSchedule();
        var candidates = schedule.getClass().getDeclaredMethod("candidates", Object.class, long.class,
                IPatternDetails.class, java.util.function.Supplier.class);
        candidates.setAccessible(true);
        var stone = appeng.api.stacks.AEItemKey.of(Items.STONE);
        var stockRef = new appeng.crafting.inv.ListCraftingInventory[1];
        long[] minimumStock = {Long.MAX_VALUE};
        stockRef[0] = new appeng.crafting.inv.ListCraftingInventory(key -> {
            minimumStock[0] = Math.min(minimumStock[0], stockRef[0].extract(stone, Long.MAX_VALUE,
                    appeng.api.config.Actionable.SIMULATE));
        });
        var stock = stockRef[0];
        stock.insert(stone, 20_000, appeng.api.config.Actionable.MODULATE);
        minimumStock[0] = Long.MAX_VALUE;
        var waiting = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        long[] remaining = {10_000};
        var task = new com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle() {
            public IPatternDetails details() { return pattern; }
            public long getValue() { return remaining[0]; }
            public void setValue(long value) { remaining[0] = value; }
        };
        var job = new com.moakiee.thunderbolt.api.crafting.batch.BatchJobView() {
            public net.minecraft.world.level.Level level() { return null; }
            public java.util.UUID craftingId() { return null; }
            public java.util.Iterator<com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle> taskIterator() {
                return new java.util.ArrayList<com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle>(List.of(task)).iterator();
            }
            public appeng.crafting.inv.ListCraftingInventory waitingFor() { return waiting; }
            public void addContainerMaxItems(long count, appeng.api.stacks.AEKeyType type) {}
        };
        var energy = (appeng.api.networking.energy.IEnergyService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{appeng.api.networking.energy.IEnergyService.class},
                (proxy, method, args) -> method.getName().equals("extractAEPower") ? args[0] : null);
        var batched = new java.util.HashMap<IPatternDetails,
                java.util.IdentityHashMap<ICraftingProvider, Boolean>>();
        long[] limits = {64, 0, 128, 10_000};
        long accepted = 0;
        for (int tick = 0; tick < limits.length; tick++) {
            schedule.beginTick(tick);
            candidates.invoke(schedule, null, (long) tick, pattern,
                    (java.util.function.Supplier<Iterable<ICraftingProvider>>) () -> List.of(provider));
            batched.clear();
            provider.target.limit = BigInteger.valueOf(limits[tick]);
            long before = remaining[0];
            var result = com.moakiee.thunderbolt.core.crafting.batch.BatchExecutor.runBatchOnly(
                    10, com.moakiee.thunderbolt.core.crafting.batch.BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH,
                    null, energy, job, stock, batched, () -> {}, java.util.Map.of(), 10, 10_000,
                    false, schedule, resolver);
            long expected = Math.min(before, limits[tick]);
            assertEquals(expected, result.dispatchedCopies());
            assertEquals(before - expected, remaining[0]);
            accepted += expected;
            long held = stock.extract(stone, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE);
            assertEquals(20_000 - 2 * accepted, held);
            assertTrue(minimumStock[0] >= held - 2,
                    "Preflight must not reserve thousands of unaccepted copies; only a single prototype may be rolled back");
            minimumStock[0] = Long.MAX_VALUE;
            assertEquals(accepted, waiting.extract(appeng.api.stacks.AEItemKey.of(Items.DIRT),
                    Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE));
            assertEquals(accepted * 2, waiting.extract(appeng.api.stacks.AEItemKey.of(Items.COBBLESTONE),
                    Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE));
        }
        assertEquals(0, remaining[0]);
        assertEquals(0, provider.ordinaryCalls);
    }

    private static IPatternDetails pattern() {
        var stone = appeng.api.stacks.AEItemKey.of(Items.STONE);
        var input = new IPatternDetails.IInput() {
            public appeng.api.stacks.GenericStack[] getPossibleInputs() {
                return new appeng.api.stacks.GenericStack[]{new appeng.api.stacks.GenericStack(stone, 2)};
            }
            public long getMultiplier() { return 1; }
            public boolean isValid(appeng.api.stacks.AEKey key, net.minecraft.world.level.Level level) { return stone.equals(key); }
            public appeng.api.stacks.AEKey getRemainingKey(appeng.api.stacks.AEKey key) { return null; }
        };
        return (IPatternDetails) java.lang.reflect.Proxy.newProxyInstance(UselessBatchAdapterTest.class.getClassLoader(),
                new Class<?>[]{IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputs" -> new IPatternDetails.IInput[]{input};
                    case "getOutputs" -> List.of(
                            new appeng.api.stacks.GenericStack(appeng.api.stacks.AEItemKey.of(Items.DIRT), 1),
                            new appeng.api.stacks.GenericStack(appeng.api.stacks.AEItemKey.of(Items.COBBLESTONE), 2));
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "useless-batch-test";
                    case "supportsPushInputsToExternalInventory", "canSubstitute" -> false;
                    default -> null;
                });
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
        boolean rejectAdmission;
        int admissions;
        long committed;
        RuntimeException failure;
        public Capacity capacity(IPatternDetails pattern, KeyCounter[] input, BigInteger requested) {
            var accepted = limit.min(requested);
            return new Capacity(accepted.signum() <= 0 ? BigInteger.ZERO
                    : accepted.multiply(BigInteger.valueOf(throttlePercent)).divide(BigInteger.valueOf(100)).max(BigInteger.ONE));
        }
        public Batch admit(IPatternDetails pattern, KeyCounter[] input, BigInteger requested, Binding binding) {
            admissions++;
            offered = requested;
            assertNull(binding);
            if (rejectAdmission) return null;
            var accepted = capacity(pattern, input, requested).accepted();
            return new Batch() {
                public BigInteger count() { return accepted; }
                public boolean commit(KeyCounter[] prototype) {
                    assertSame(input, prototype);
                    if (failure != null) throw failure;
                    if (success) {
                        committed += accepted.longValueExact();
                        prototype[0].clear();
                    }
                    return success;
                }
            };
        }
    }
    public static final class Provider implements ICraftingProvider {
        Target target = new Target();
        int ordinaryCalls;
        boolean busy;
        public Target bigIntegerTarget() { return target; }
        public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        public boolean isBusy() { return busy; }
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
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.sorrowmist.useless.api.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        assertThrows(ReflectiveOperationException.class,
                () -> new UselessBatchApi(loader));
    }

    @Test
    void installedPublicApiMatchesBridgeSignatures() throws Exception {
        var loader = getClass().getClassLoader();
        org.junit.jupiter.api.Assumptions.assumeTrue(loader.getResource(
                "com/sorrowmist/useless/api/crafting/bigint/AlloyFurnaceBigIntegerProvider.class") != null);
        var api = new UselessBatchApi(loader);
        assertEquals("com.sorrowmist.useless.api.crafting.bigint.AlloyFurnaceBigIntegerProvider", api.providerType.getName());
        assertNotNull(UselessBatchCompat.loadAdapter(loader));
    }
}
