package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Uses real NeoECO classes supplied by its source build or mapped official CI artifact. */
@EnabledIfSystemProperty(named = "thunderbolt.neoecoProtocolPath", matches = ".+")
class NeoEcoBatchProtocolTest {
    private static URLClassLoader loader;
    private static NeoEcoBatchApi api;
    private static AEKey raw, output;

    @BeforeAll static void setup() throws Exception {
        MinecraftTestBootstrap.ensureInitialized();
        raw = AEItemKey.of(Items.IRON_INGOT);
        output = AEItemKey.of(Items.GOLD_INGOT);
        var paths = System.getProperty("thunderbolt.neoecoProtocolPath").split(java.io.File.pathSeparator);
        var urls = new java.net.URL[paths.length];
        for (int i = 0; i < paths.length; i++) urls[i] = Path.of(paths[i]).toUri().toURL();
        loader = new URLClassLoader(urls, NeoEcoBatchProtocolTest.class.getClassLoader());
        api = NeoEcoBatchApi.load(loader);
        assertNotNull(api, "Actual NeoECO protocol must link");
        assertInstanceOf(NeoEcoBatchAdapter.class, NeoEcoFastPathCompat.loadAdapter(loader));
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void nativeContextIsPerCopyAndLiveCapacityCanShrinkBeforeCommit() throws Exception {
        var f = new Fixture(false, false);
        var endpoint = f.endpoint();
        var template = f.inputs();
        f.capacity = 10;
        var batch = endpoint.prepareBatch(f.pattern, template, 100, f.job);
        assertEquals(10, batch.capacity());
        assertEquals(0, f.pushes);
        f.capacity = 3;
        assertEquals(7, batch.push(10));
        assertEquals(3, f.offered);
        assertEquals(2, f.contextInput);
        assertEquals(7, f.contextOutput);
        assertEquals(f.job.craftingId(), f.contextJob);
        assertEquals(2, template[0].get(raw));
        assertThrows(IllegalStateException.class, () -> batch.push(1));
    }

    @Test void arithmeticLimitIncludesOutputs() throws Exception {
        var f = new Fixture(false, false);
        f.capacity = Long.MAX_VALUE;
        var batch = f.endpoint().prepareBatch(f.pattern, f.inputs(), Long.MAX_VALUE, f.job);
        assertEquals(Long.MAX_VALUE / 7, batch.capacity());
    }

    @Test void arithmeticLimitAggregatesTheSameKeyAcrossInputSlots() throws Exception {
        var f = new Fixture(false, false, 5);
        f.capacity = Long.MAX_VALUE;
        var batch = f.endpoint().prepareBatch(f.pattern, f.inputs(), Long.MAX_VALUE, f.job);
        assertEquals(Long.MAX_VALUE / 10, batch.capacity());
    }

    @Test void zeroFastPathCapacityUsesAnOwnedSingleCopyAndRetriesBatchingNextTick() throws Exception {
        var f = new Fixture(false, false);
        var endpoint = f.endpoint();
        endpoint.beginDispatchTick(1);
        f.capacity = 0;
        var inputs = f.inputs();
        var batch = endpoint.prepareBatch(f.pattern, inputs, 100, f.job);
        assertEquals(1, batch.capacity());
        assertEquals(0, batch.push(1));
        assertEquals(1, f.ordinary);
        assertEquals(2, inputs[0].get(raw));
        assertEquals(1, endpoint.getBatchCapacity(f.pattern));
        endpoint.beginDispatchTick(1);
        assertEquals(1, endpoint.getBatchCapacity(f.pattern));
        endpoint.beginDispatchTick(2);
        assertEquals(Long.MAX_VALUE, endpoint.getBatchCapacity(f.pattern));
    }

    @Test void reusableMaterialUsesOrdinaryDispatchWithoutMultiplyingItsReturnedSeed() throws Exception {
        var f = new Fixture(false, true);
        var batch = f.endpoint().prepareBatch(f.pattern, f.inputs(), 100, f.job);
        assertEquals(1, batch.capacity());
        assertEquals(0, batch.push(1));
        assertEquals(0, f.pushes);
        assertEquals(1, f.ordinary);
    }

    @Test void oneCopyCapacityStillUsesNativeJobAwareBatchContract() throws Exception {
        var f = new Fixture(false, false);
        f.capacity = 1;
        var batch = f.endpoint().prepareBatch(f.pattern, f.inputs(), 100, f.job);
        assertEquals(0, batch.push(1));
        assertEquals(1, f.pushes);
        assertEquals(0, f.ordinary);
        assertEquals(f.job.craftingId(), f.contextJob);
    }

    @Test void failedLiveProbeReturnsAllCopiesBeforeNativeOwnershipChanges() throws Exception {
        var f = new Fixture(false, false);
        var batch = f.endpoint().prepareBatch(f.pattern, f.inputs(), 8, f.job);
        f.capacityFailure = true;
        assertEquals(8, batch.push(8));
        assertEquals(0, f.pushes);
    }

    @Test void rejectionReturnsAllCopiesAndNeverRetriesAnOrdinaryPushInsideTheTransaction() throws Exception {
        var f = new Fixture(false, false);
        f.state = "REJECTED";
        var batch = f.endpoint().prepareBatch(f.pattern, f.inputs(), 8, f.job);
        assertEquals(8, batch.push(8));
        assertEquals(1, f.pushes);
        assertEquals(0, f.ordinary);
    }

    @Test void exceptionAfterNativeCallPropagatesWithoutRefundOrRetry() throws Exception {
        var f = new Fixture(false, false);
        f.pushFailure = true;
        var batch = f.endpoint().prepareBatch(f.pattern, f.inputs(), 8, f.job);
        assertThrows(IllegalStateException.class, () -> batch.push(8));
        assertEquals(1, f.pushes);
        assertEquals(0, f.ordinary);
    }

    @Test void partialReceiptRetainsOnlyAcceptedCopiesAndHonorsStopUntilNextTick() throws Exception {
        requireReceipts();
        var f = new Fixture(false, false);
        var endpoint = f.endpoint();
        endpoint.beginDispatchTick(1);
        f.accept = 3;
        f.again = false;
        assertEquals(5, endpoint.prepareBatch(f.pattern, f.inputs(), 8, f.job).push(8));
        assertEquals(0, endpoint.getBatchCapacity(f.pattern));
        assertEquals(1, endpoint.pushBatch(f.pattern, f.inputs(), 1));
        assertEquals(0, f.ordinary);
        endpoint.beginDispatchTick(2);
        assertEquals(Long.MAX_VALUE, endpoint.getBatchCapacity(f.pattern));
    }

    @Test void indeterminateReceiptStopsDispatchWithoutOrdinaryRetry() throws Exception {
        requireReceipts();
        var f = new Fixture(false, false);
        f.state = "INDETERMINATE";
        var batch = f.endpoint().prepareBatch(f.pattern, f.inputs(), 8, f.job);
        assertThrows(IllegalStateException.class, () -> batch.push(8));
        assertEquals(1, f.pushes);
        assertEquals(0, f.ordinary);
    }

    @Test void parallelProtocolGetsOwnedBatchTotalsAndJobIdentity() throws Exception {
        requireReceipts();
        var f = new Fixture(true, false);
        f.accept = 3;
        var inputs = f.inputs();
        var endpoint = f.endpoint();
        assertFalse(new NeoEcoBatchAdapter(api).cacheResolutionAcrossTicks());
        assertEquals(5, endpoint.prepareBatch(f.pattern, inputs, 8, f.job).push(8));
        assertEquals(16, f.parallelInput);
        assertEquals(f.job.craftingId(), f.contextJob);
        assertEquals(2, inputs[0].get(raw));
    }

    private static void requireReceipts() throws Exception {
        try { loader.loadClass("cn.dancingsnow.neoecoae.crafting.execution.batch.ECOBatchAdmission"); }
        catch (ClassNotFoundException oldProtocol) { Assumptions.abort("Atomic boolean NeoECO protocol"); }
    }

    private static final class Fixture implements java.lang.reflect.InvocationHandler {
        final IPatternDetails pattern;
        final ICraftingProvider provider;
        final BatchJobView job;
        long capacity = 100, offered, accept = Long.MAX_VALUE, contextInput, contextOutput, parallelInput;
        UUID contextJob;
        int pushes, ordinary;
        String state = "ACCEPTED";
        boolean again = true, capacityFailure, pushFailure;
        final int inputSlots;

        Fixture(boolean parallel, boolean reusable) throws Exception {
            this(parallel, reusable, 1);
        }

        Fixture(boolean parallel, boolean reusable, int inputSlots) throws Exception {
            this.inputSlots = inputSlots;
            var input = (IPatternDetails.IInput) Proxy.newProxyInstance(loader,
                    new Class<?>[] {IPatternDetails.IInput.class}, (p, m, args) -> switch (m.getName()) {
                        case "getPossibleInputs" -> new GenericStack[] {new GenericStack(raw, 2)};
                        case "getMultiplier" -> 1L;
                        case "getRemainingKey" -> reusable ? raw : null;
                        case "isValid" -> raw.equals(args[0]);
                        default -> null;
                    });
            pattern = (IPatternDetails) Proxy.newProxyInstance(loader, new Class<?>[] {IPatternDetails.class},
                    (p, m, args) -> switch (m.getName()) {
                        case "getInputs" -> Collections.nCopies(inputSlots, input).toArray(IPatternDetails.IInput[]::new);
                        case "getOutputs" -> new GenericStack[] {new GenericStack(output, 7)};
                        case "getDefinition" -> AEItemKey.of(Items.PAPER);
                        case "getPrimaryOutput" -> new GenericStack(output, 7);
                        case "supportsPushInputsToExternalInventory" -> true;
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> null;
                    });
            var type = loader.loadClass("cn.dancingsnow.neoecoae.api.me." + (parallel
                    ? "ECOParallelCraftingProvider" : "ECOBatchCapacityProvider"));
            provider = (ICraftingProvider) Proxy.newProxyInstance(loader,
                    new Class<?>[] {ICraftingProvider.class, type}, this);
            var id = UUID.randomUUID();
            job = new BatchJobView() {
                public Level level() { return null; }
                public UUID craftingId() { return id; }
                public Iterator<BatchTaskHandle> taskIterator() { return Collections.emptyIterator(); }
                public ListCraftingInventory waitingFor() { return new ListCraftingInventory(key -> {}); }
                public void addContainerMaxItems(long count, AEKeyType type) {}
            };
        }
        KeyCounter[] inputs() {
            var result = new KeyCounter[inputSlots];
            for (int i = 0; i < inputSlots; i++) { result[i] = new KeyCounter(); result[i].add(raw, 2); }
            return result;
        }
        IBatchCraftingProvider endpoint() { return Objects.requireNonNull(new NeoEcoBatchAdapter(api).resolve(provider)); }
        @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "isBusy": return false;
                case "getAvailablePatterns": return List.of(pattern);
                case "eco$getBatchCapacity": case "eco$getAvailableParallelSlots":
                    if (capacityFailure) throw new IllegalStateException("probe failed before ownership");
                    return capacity;
                case "pushPattern":
                    ordinary++;
                    ((KeyCounter[]) args[1])[0].reset();
                    return true;
                case "eco$pushBatch": case "eco$pushBatchAdmission":
                    var context = args[0];
                    var type = context.getClass();
                    contextInput = ((KeyCounter[]) type.getMethod("inputCounters").invoke(context))[0].get(raw);
                    contextOutput = ((GenericStack) ((List<?>) type.getMethod("outputs").invoke(context)).get(0)).amount();
                    contextJob = (UUID) type.getMethod("craftingJobId").invoke(context);
                    offered = (long) args[1];
                    break;
                case "eco$pushPatternBatch":
                    var owned = (KeyCounter[]) args[1];
                    parallelInput = owned[0].get(raw);
                    owned[0].reset();
                    offered = (long) args[2];
                    contextJob = (UUID) args[3];
                    break;
                default: throw new AssertionError(method);
            }
            pushes++;
            if (pushFailure) throw new IllegalStateException("ownership uncertain");
            if (method.getReturnType() == boolean.class) return state.equals("ACCEPTED");
            var receipt = loader.loadClass("cn.dancingsnow.neoecoae.crafting.execution.batch.ECOBatchAdmission");
            return switch (state) {
                case "ACCEPTED" -> receipt.getMethod("accepted", long.class, boolean.class)
                        .invoke(null, Math.min(accept, offered), again);
                case "REJECTED" -> receipt.getMethod("rejected").invoke(null);
                default -> receipt.getMethod("indeterminate").invoke(null);
            };
        }
    }
}
