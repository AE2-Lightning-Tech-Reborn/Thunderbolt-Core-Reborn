package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import com.moakiee.thunderbolt.core.crafting.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.core.crafting.batch.BatchExecutor;
import com.moakiee.thunderbolt.core.crafting.batch.TickProviderDispatchSchedule;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.tools.ToolProvider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** API contracts and the real CPU ledger, with isolated optional-protocol fixtures. */
class NeoEcoFastPathContractTest {
    private static final String FACADE = "cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade";
    @TempDir static Path temporary;
    private static Path classes;
    private URLClassLoader loader;
    private ICraftingProvider provider;
    private IBatchCraftingProvider endpoint;
    private NeoEcoAllocatedApi api;
    private AEItemKey material, output;
    private final UUID id = UUID.randomUUID();
    private UUID liveId;
    private BatchJobView job;

    @BeforeAll static void compileFixture() throws Exception {
        MinecraftTestBootstrap.ensureInitialized();
        var paths = new LinkedHashSet<String>(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        for (ClassLoader loader = NeoEcoFastPathContractTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) for (URL url : urls.getURLs())
                if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
        }
        classes = Files.createDirectory(temporary.resolve("classes"));
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "--release", "17",
                "-classpath", String.join(File.pathSeparator, paths), "-d", classes.toString()));
        try (var files = Files.walk(Path.of(NeoEcoFastPathContractTest.class.getResource("/neoeco-fastpath").toURI()))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                () -> diagnostics.toString(StandardCharsets.UTF_8));
    }

    @BeforeEach void open() throws Exception {
        loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        provider = (ICraftingProvider) loader.loadClass(FACADE + "$Provider").getConstructor().newInstance();
        api = Objects.requireNonNull(NeoEcoAllocatedApi.load(loader));
        endpoint = ((BatchProviderResolver) Objects.requireNonNull(NeoEcoFastPathCompat.loadAdapter(loader))).resolve(provider);
        material = AEItemKey.of(Items.IRON_INGOT);
        output = AEItemKey.of(Items.GOLD_INGOT);
        liveId = id;
        job = (BatchJobView) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BatchJobView.class},
                (proxy, method, args) -> method.getName().equals("craftingId") ? liveId : null);
    }
    @AfterEach void close() throws Exception { loader.close(); }

    @Test void missingOptionalApiDoesNotLinkOrRegister() {
        assertNull(NeoEcoFastPathCompat.createAdapter());
        assertNull(NeoEcoAllocatedApi.load(getClass().getClassLoader()));
        int[] calls = {0};
        var unsupported = (ICraftingProvider) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ICraftingProvider.class}, (proxy, method, args) -> {
                    if (method.getName().equals("pushPattern")) { calls[0]++; return false; }
                    return null;
                });
        assertFalse(NeoEcoFastPathCompat.pushPattern(unsupported, null, inputs(), id));
        assertEquals(1, calls[0]);
    }

    @Test void publicOrdinaryHelperUsesTheProviderClassLoaderAndJob() throws Exception {
        assertTrue(NeoEcoFastPathCompat.pushPattern(provider, null, inputs(), id));
        assertEquals(id, read("ordinaryJob"));
        assertEquals(1, read("ordinaryCalls"));
        assertEquals(0, read("rawCalls"));
    }

    @Test void nativeProvidersKeepTheirOwnImplementation() {
        var nativeProvider = (IBatchCraftingProvider) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IBatchCraftingProvider.class}, (proxy, method, args) -> null);
        assertSame(nativeProvider, new NeoEcoFastPathBatchAdapter(api).resolve(nativeProvider));
    }

    @Test void preparationCapturesJobAndPrivateInputsAndCanOnlyCommitOnce() throws Exception {
        var input = inputs();
        var prepared = endpoint.prepareBatch(null, input, 8, job);
        assertEquals(8, prepared.capacity());
        assertEquals(0L, read("accepted"));
        liveId = UUID.randomUUID();
        input[0].add(material, 99);
        assertEquals(0, prepared.push(8));
        assertEquals(id, read("submittedJob"));
        assertEquals(2, ((KeyCounter[]) read("submittedInputs"))[0].get(material));
        assertThrows(IllegalStateException.class, () -> prepared.push(8));
        assertEquals(8L, read("accepted"));
    }

    @Test void reducedOfferIsRepreparedWithoutRetainingTheMutableJobView() throws Exception {
        var prepared = endpoint.prepareBatch(null, inputs(), 8, job);
        liveId = UUID.randomUUID();
        assertEquals(0, prepared.push(3));
        assertEquals(2, read("preparations"));
        assertEquals(3L, read("accepted"));
        assertEquals(id, read("preparedJob"));
    }

    @Test void directDispatchAndReducedOffersHonorTheLatestAdmission() throws Exception {
        set("capacity", 3L);
        assertEquals(5, endpoint.pushBatch(null, inputs(), 8, job));
        assertEquals(3L, read("accepted"));
        set("capacity", 8L);
        var prepared = endpoint.prepareBatch(null, inputs(), 8, job);
        set("capacity", 2L);
        assertEquals(3, prepared.push(5));
        assertEquals(5L, read("accepted"));
        assertEquals(0, read("ordinaryCalls"));
    }

    @Test void reducedOfferCanDeclineWithoutSubmittingTheObsoleteBatch() throws Exception {
        var prepared = endpoint.prepareBatch(null, inputs(), 8, job);
        set("noPreparation", true);
        assertEquals(4, prepared.push(5));
        assertEquals(0, read("submissions"));
        assertEquals(1, read("ordinaryCalls"));
        assertEquals(1L, read("accepted"));
    }

    @Test void ordinaryRejectionReturnsAllCopiesAndOrdinaryExceptionIsNotRetried() throws Exception {
        set("noPreparation", true);
        set("rejectOrdinary", true);
        assertEquals(8, endpoint.pushBatch(null, inputs(), 8, job));
        assertEquals(0L, read("accepted"));
        set("failOrdinary", true);
        assertThrows(IllegalStateException.class, () -> endpoint.pushBatch(null, inputs(), 8, job));
        assertEquals(2, read("ordinaryCalls"));
        assertEquals(0, read("submissions"));
    }

    @Test void declinedPreparationUsesOneOrdinaryCopyWithTheOriginalJob() throws Exception {
        set("noPreparation", true);
        endpoint.beginDispatchTick(10);
        var input = inputs();
        var prepared = endpoint.prepareBatch(null, input, 8, job);
        assertEquals(1, prepared.capacity());
        liveId = UUID.randomUUID();
        assertEquals(0, prepared.push(1));
        assertEquals(id, read("ordinaryJob"));
        assertEquals(2, input[0].get(material));
        assertEquals(1, endpoint.getBatchCapacity(null));
        endpoint.beginDispatchTick(11);
        assertEquals(Long.MAX_VALUE, endpoint.getBatchCapacity(null));
    }

    @Test void explicitBatchRejectionCanFallBackButSubmissionExceptionsNeverRetry() throws Exception {
        set("reject", true);
        assertEquals(7, endpoint.pushBatch(null, inputs(), 8, job));
        assertEquals(1, read("ordinaryCalls"));
        endpoint.beginDispatchTick(2);
        set("failSubmit", true);
        assertThrows(IllegalStateException.class, () -> endpoint.pushBatch(null, inputs(), 8, job));
        assertEquals(1, read("ordinaryCalls"));
    }

    @Test void invalidAdmissionAndUnexpectedEnergyAreNotSilentlyAccepted() throws Exception {
        set("invalidCount", 9L);
        assertThrows(IllegalStateException.class, () -> endpoint.prepareBatch(null, inputs(), 8, job));
        assertEquals(0, read("submissions"));
        set("invalidCount", 0L);
        set("energyCharge", 1.0D);
        assertThrows(IllegalStateException.class, () -> endpoint.pushBatch(null, inputs(), 8, job));
        assertEquals(0, read("ordinaryCalls"));
        assertEquals(0L, read("accepted"));
    }

    @Test void busyAndContextFreeCallsNeverTransferMaterials() throws Exception {
        set("busy", true);
        assertEquals(8, endpoint.pushBatch(null, inputs(), 8, job));
        assertEquals(8, endpoint.pushBatch(null, inputs(), 8));
        assertEquals(0, read("preparations"));
        assertEquals(0L, read("accepted"));
        set("busy", false);
        assertEquals(Long.MAX_VALUE, endpoint.getBatchCapacity(null));
        assertEquals(0, endpoint.pushBatch(null, inputs(), 8, job));
        assertEquals(8L, read("accepted"));
    }

    @Test void exactAdmissionPreservesWideCountsAndCancellationOwnership() throws Exception {
        var count = BigInteger.ONE.shiftLeft(100).add(BigInteger.valueOf(7));
        var prepared = NeoEcoFastPathCompat.prepareExact(provider, null, inputs(), count, null, id);
        assertNotNull(prepared);
        assertEquals(count, prepared.craftCount());
        assertEquals(BigInteger.ZERO, read("exactAccepted"));
        liveId = UUID.randomUUID();
        assertTrue(prepared.submit());
        assertEquals(count, read("exactAccepted"));
        assertEquals(id, read("submittedJob"));
        assertThrows(IllegalStateException.class, prepared::submit);
        api.cancel(null, id);
        assertEquals(id, loader.loadClass(FACADE).getField("cancelled").get(null));
    }

    @Test void cpuLedgerOnlyDebitsAcceptedCopiesAndEnergyOnce() throws Exception {
        set("capacity", 3L);
        var cpu = new Cpu();
        var result = cpu.run();
        assertEquals(3, result.dispatchedCopies());
        assertEquals(5, cpu.remaining);
        assertEquals(10, cpu.stock.list.get(material));
        assertEquals(3, cpu.waiting.list.get(output));
        assertEquals(1, cpu.energyDebits);
        assertNull(cpu.failure);
    }

    @Test void cpuAdmissionFailureRestoresStockAndDoesNotSubmit() throws Exception {
        set("failPrepare", true);
        var cpu = new Cpu();
        assertEquals(0, cpu.run().dispatchedCopies());
        assertEquals(16, cpu.stock.list.get(material));
        assertEquals(8, cpu.remaining);
        assertEquals(0, read("submissions"));
        assertEquals(0, cpu.energyDebits);
    }

    @Test void cpuStopsAmbiguousOwnershipWithoutRefundOrOrdinaryReplay() throws Exception {
        set("failSubmit", true);
        var cpu = new Cpu();
        cpu.run();
        assertEquals("AMBIGUOUS_BATCH_PROVIDER_OWNERSHIP", cpu.failure);
        assertEquals(0, cpu.stock.list.get(material));
        assertEquals(8, cpu.remaining);
        assertEquals(0, cpu.waiting.list.get(output));
        assertEquals(0, read("ordinaryCalls"));
        assertEquals(1, read("submissions"));
    }

    private KeyCounter[] inputs() {
        var counter = new KeyCounter(); counter.add(material, 2);
        return new KeyCounter[]{counter};
    }
    private Object read(String name) throws Exception { return provider.getClass().getField(name).get(provider); }
    private void set(String name, Object value) throws Exception { provider.getClass().getField(name).set(provider, value); }

    private final class Cpu implements BatchJobView {
        final ListCraftingInventory stock = new ListCraftingInventory(key -> {});
        final ListCraftingInventory waiting = new ListCraftingInventory(key -> {});
        long remaining = 8;
        int energyDebits;
        String failure;
        final IPatternDetails pattern;

        Cpu() {
            stock.insert(material, 16, Actionable.MODULATE);
            var input = (IPatternDetails.IInput) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IPatternDetails.IInput.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getPossibleInputs" -> new GenericStack[]{new GenericStack(material, 2)};
                        case "getMultiplier" -> 1L;
                        case "isValid" -> material.equals(args[0]);
                        default -> null;
                    });
            pattern = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getInputs" -> new IPatternDetails.IInput[]{input};
                        case "getOutputs" -> new GenericStack[]{new GenericStack(output, 1)};
                        case "getDefinition" -> AEItemKey.of(Items.PAPER);
                        case "supportsPushInputsToExternalInventory" -> false;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }

        BatchExecutor.BatchRunResult run() throws Exception {
            var schedule = new TickProviderDispatchSchedule();
            schedule.beginTick(1);
            // Seed the same cached snapshot used by the public CraftingService route without
            // constructing an unrelated ME grid or widening the scheduler's production API.
            var seed = TickProviderDispatchSchedule.class.getDeclaredMethod("candidates",
                    Object.class, long.class, IPatternDetails.class, java.util.function.Supplier.class);
            seed.setAccessible(true);
            seed.invoke(schedule, null, 1L, pattern,
                    (java.util.function.Supplier<Iterable<ICraftingProvider>>) () -> List.of(provider));
            var energy = (IEnergyService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IEnergyService.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("extractAEPower")) return null;
                        if (args[1] == Actionable.MODULATE) energyDebits++;
                        return args[0];
                    });
            return BatchExecutor.runBatchOnly(10, BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH,
                    null, energy, this, stock, new HashMap<>(), () -> {}, Map.of(), 10, 100, false,
                    schedule, new NeoEcoFastPathBatchAdapter(api));
        }
        @Override public Level level() { return null; }
        @Override public UUID craftingId() { return id; }
        @Override public ListCraftingInventory waitingFor() { return waiting; }
        @Override public void addContainerMaxItems(long count, AEKeyType type) {}
        @Override public void failDispatch(String reason, Throwable cause) { failure = reason; }
        @Override public Iterator<BatchTaskHandle> taskIterator() {
            return new ArrayList<BatchTaskHandle>(List.of(new BatchTaskHandle() {
                @Override public IPatternDetails details() { return pattern; }
                @Override public long getValue() { return remaining; }
                @Override public void setValue(long value) { remaining = value; }
            })).iterator();
        }
    }
}
