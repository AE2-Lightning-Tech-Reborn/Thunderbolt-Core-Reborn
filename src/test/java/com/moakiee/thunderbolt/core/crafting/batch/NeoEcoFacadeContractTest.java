package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathCompat;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import javax.tools.ToolProvider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Verifies CPU resource settlement against the modern public contract in an isolated loader. */
class NeoEcoFacadeContractTest {
    private static final String CORE = "com.moakiee.thunderbolt.compat.neoeco.";
    private static final String ECO = "cn.dancingsnow.neoecoae.";
    @TempDir static Path temporary;
    private static Path contracts;
    private static String compilerClasspath;
    private Fixture fixture;

    @BeforeAll static void compileContracts() throws Exception {
        MinecraftTestBootstrap.ensureInitialized();
        var paths = new LinkedHashSet<>(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        for (ClassLoader loader = NeoEcoFacadeContractTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) for (URL url : urls.getURLs())
                if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
        }
        compilerClasspath = String.join(File.pathSeparator, paths);
        contracts = compileVariant("contracts", UnaryOperator.identity());
    }

    private static Path compileVariant(String name, UnaryOperator<String> facadeChange) throws Exception {
        var output = Files.createDirectory(temporary.resolve(name));
        var copiedSources = Files.createDirectory(temporary.resolve(name + "-sources"));
        var sources = Path.of(NeoEcoFacadeContractTest.class.getResource("/neoeco-facade-contract").toURI());
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "--release", "17",
                "-classpath", compilerClasspath, "-d", output.toString()));
        try (var files = Files.walk(sources)) {
            for (var source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var target = copiedSources.resolve(sources.relativize(source));
                Files.createDirectories(target.getParent());
                var text = Files.readString(source);
                if (source.getFileName().toString().equals("ECOFastPathFacade.java")) text = facadeChange.apply(text);
                Files.writeString(target, text);
                args.add(target.toString());
            }
        }
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                () -> diagnostics.toString(StandardCharsets.UTF_8));
        return output;
    }

    @BeforeEach void open() throws Exception { fixture = new Fixture("Provider"); }
    @AfterEach void close() throws Exception { fixture.close(); }

    @Test void rolledBackProviderExceptionReturnsAllInputsWithoutWaitingForPhantomOutputs() throws Exception {
        fixture.set("submitFailure", new IllegalStateException("provider rejected before accepting"));
        var cpu = fixture.cpu();
        assertEquals(0, cpu.run(1).dispatchedCopies());
        assertEquals(16, cpu.stock.extract(fixture.raw, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0, cpu.waiting.extract(fixture.output, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(8, cpu.remaining);
        assertEquals(0, cpu.energyDebited);
        assertNull(cpu.failure);
        assertEquals(0, fixture.get("ordinaryPushes"));
        fixture.set("submitFailure", null);
        assertEquals(8, cpu.run(2).dispatchedCopies());
        assertEquals(0, cpu.stock.extract(fixture.raw, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(8, cpu.waiting.extract(fixture.output, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0, cpu.remaining);
        assertEquals(fixture.jobId, fixture.get("jobId"));
    }

    @Test void indeterminateOwnershipStopsTheCpuAndDoesNotRefundOrTryOrdinaryDispatch() throws Exception {
        var unknown = (RuntimeException) fixture.loader.loadClass(ECO + "api.me.provider.ECOIndeterminateBatchException")
                .getConstructor(String.class, Throwable.class).newInstance("unknown ownership", null);
        fixture.set("submitFailure", unknown);
        var cpu = fixture.cpu();
        cpu.run(1);
        assertEquals("AMBIGUOUS_BATCH_PROVIDER_OWNERSHIP", cpu.failure);
        assertEquals(0, cpu.stock.extract(fixture.raw, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0, cpu.waiting.extract(fixture.output, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(8, cpu.remaining);
        assertEquals(1, fixture.get("submissions"));
        assertEquals(0, fixture.get("ordinaryPushes"));
        assertEquals(0, fixture.get("directPushes"));
    }

    @Test void admissionBoundsExtractionAndReducedOffersNeverOverSubmit() throws Exception {
        fixture.set("capacity", 5L);
        var endpoint = fixture.endpoint();
        var template = fixture.inputs();
        var prepared = endpoint.prepareBatch(fixture.pattern, template, 8, fixture.cpu());
        assertNotNull(prepared);
        assertEquals(5, prepared.capacity());
        assertEquals(0, fixture.get("submissions"));
        template[0].reset();
        assertEquals(0, prepared.push(2));
        assertEquals(2L, fixture.get("accepted"));
        assertEquals(2, ((KeyCounter[]) fixture.get("inputs"))[0].get(fixture.raw));
        assertThrows(IllegalStateException.class, () -> prepared.push(2));
    }

    @Test void rejectedBatchUsesOneOwnedOrdinaryCopyAndKeepsTheJob() throws Exception {
        fixture.set("accept", false);
        fixture.set("ordinaryAccept", true);
        var template = fixture.inputs();
        assertEquals(7, fixture.endpoint().pushBatch(fixture.pattern, template, 8, fixture.cpu()));
        assertEquals(1, fixture.get("submissions"));
        assertEquals(1, fixture.get("ordinaryPushes"));
        assertEquals(0, fixture.get("directPushes"));
        assertEquals(fixture.jobId, fixture.get("jobId"));
        ((KeyCounter[]) fixture.get("inputs"))[0].reset();
        assertEquals(2, template[0].get(fixture.raw));
    }

    @Test void invalidCapacityCannotTransferOwnership() throws Exception {
        for (long count : new long[]{0, -1, 9}) {
            fixture.set("invalidCount", count);
            var endpoint = fixture.endpoint();
            assertThrows(IllegalArgumentException.class,
                    () -> endpoint.prepareBatch(fixture.pattern, fixture.inputs(), 8, fixture.cpu()));
        }
        assertEquals(0, fixture.get("submissions"));
    }

    @Test void admissionCountIsReadOnceAtTheBoundaryAndRetainedUntilSubmission() throws Exception {
        var prepared = fixture.endpoint().prepareBatch(fixture.pattern, fixture.inputs(), 8, fixture.cpu());
        assertEquals(1, fixture.get("countReads"));
        assertEquals(8, prepared.capacity());
        fixture.set("invalidCount", -1L);
        assertEquals(8, prepared.capacity());
        assertEquals(0, prepared.push(8));
        assertEquals(1, fixture.get("countReads"));
        assertEquals(8L, fixture.get("accepted"));
    }

    @Test void inaccessiblePreparedTypeIsRejectedBeforeAnyProviderCall() throws Exception {
        var variant = compileVariant("hidden-prepared", text -> text.replace(
                "public static final class PreparedBatch", "static final class PreparedBatch"));
        try (var incompatible = new Fixture(variant, "Provider")) {
            assertNull(incompatible.resolver());
            assertEquals(0, incompatible.get("preparations"));
            assertEquals(0, incompatible.get("submissions"));
        }
    }

    @Test void overloadedAbstractCallbacksAreRejectedEvenWhenTheirNamesAreKnown() throws Exception {
        for (var callback : List.of("Reservation reserve(String amount);", "void refund(long amount);")) {
            var variant = compileVariant(callback.startsWith("Reservation") ? "unknown-reserve" : "unknown-refund",
                    text -> callback.startsWith("Reservation")
                            ? text.replace("Reservation reserve(double amount);", "Reservation reserve(double amount); " + callback)
                            : text.replace("void refund();", "void refund(); " + callback));
            try (var incompatible = new Fixture(variant, "Provider")) {
                assertNull(incompatible.resolver());
                assertEquals(0, incompatible.get("preparations"));
                assertEquals(0, incompatible.get("submissions"));
            }
        }
    }

    @Test void defaultEnergyAndReservationMethodsComposeWithTheSupportedCallbacks() throws Exception {
        var variant = compileVariant("default-callbacks", text -> text
                .replace("Reservation reserve(double amount);", "Reservation reserve(double amount); "
                        + "default Reservation reserveDefault(double amount) { return reserve(amount); }")
                .replace("void commit(); void refund();", "void commit(); void refund(); "
                        + "default void commitDefault() { commit(); } default void refundDefault() { refund(); }")
                .replace("energy.reserve(0)", "energy.reserveDefault(0)")
                .replace("reservation.commit()", "reservation.commitDefault()")
                .replace("reservation.refund()", "reservation.refundDefault()"));
        try (var compatible = new Fixture(variant, "Provider")) {
            compatible.set("accept", false);
            assertEquals(8, compatible.endpoint().pushBatch(compatible.pattern, compatible.inputs(), 8, compatible.cpu()));
            compatible.set("accept", true);
            assertEquals(0, compatible.endpoint().pushBatch(compatible.pattern, compatible.inputs(), 8, compatible.cpu()));
            assertEquals(2, compatible.get("submissions"));
            assertEquals(8L, compatible.get("accepted"));
        }
    }

    @Test void exactFacadePreservesCountsAboveLongAndRejectsRepeatedSubmission() throws Exception {
        var huge = BigInteger.ONE.shiftLeft(130).add(BigInteger.TEN);
        fixture.set("exactCount", huge.subtract(BigInteger.ONE));
        var template = fixture.inputs();
        var prepared = fixture.exact(huge, template);
        assertNotNull(prepared);
        assertEquals(huge.subtract(BigInteger.ONE), invoke(prepared.getClass().getMethod("craftCount"), prepared));
        assertEquals(huge, fixture.get("requested"));
        assertEquals(fixture.jobId, fixture.get("jobId"));
        template[0].reset();
        assertEquals(2, ((KeyCounter[]) fixture.get("inputs"))[0].get(fixture.raw));
        assertEquals(true, invoke(prepared.getClass().getMethod("submit"), prepared));
        assertThrows(IllegalStateException.class, () -> invoke(prepared.getClass().getMethod("submit"), prepared));
        assertEquals(1, fixture.get("submissions"));
    }

    @Test void exactInvalidAdmissionsFailBeforeAnySubmission() throws Exception {
        for (var count : List.of(BigInteger.ZERO, BigInteger.ONE.negate(), BigInteger.valueOf(11))) {
            fixture.set("exactCount", count);
            assertThrows(IllegalArgumentException.class, () -> fixture.exact(BigInteger.TEN, fixture.inputs()));
        }
        assertEquals(0, fixture.get("submissions"));
    }

    @Test void exactDeclineAndNonPositiveRequestDoNotUseLegacyOrOrdinaryFallback() throws Exception {
        assertNull(fixture.exact(BigInteger.ZERO, fixture.inputs()));
        assertEquals(0, fixture.get("preparations"));
        fixture.set("decline", true);
        assertNull(fixture.exact(BigInteger.TEN, fixture.inputs()));
        assertEquals(1, fixture.get("preparations"));
        assertEquals(0, fixture.get("submissions"));
        assertEquals(0, fixture.get("ordinaryPushes"));
    }

    @Test void modernParallelProviderScalesOnlyAcceptedTotalsAndRetainsJob() throws Exception {
        fixture.close();
        fixture = new Fixture("ParallelProvider");
        var resolver = fixture.resolver();
        assertFalse(resolver.cacheResolutionAcrossTicks());
        var endpoint = resolver.resolve(fixture.provider);
        assertNotNull(endpoint);
        var template = fixture.inputs();
        var prepared = endpoint.prepareBatch(fixture.pattern, template, 8, fixture.cpu());
        assertEquals(8, prepared.capacity());
        fixture.set("slots", 3);
        assertEquals(5, prepared.push(8));
        assertEquals(3L, fixture.get("accepted"));
        assertEquals(6, ((KeyCounter[]) fixture.get("inputs"))[0].get(fixture.raw));
        assertEquals(2, template[0].get(fixture.raw));
        assertEquals(fixture.jobId, fixture.get("jobId"));
    }

    @Test void reducedOfferCanBeDeclinedWithoutSubmittingOrFallingBack() throws Exception {
        var endpoint = fixture.endpoint();
        var admitted = endpoint.prepareBatch(fixture.pattern, fixture.inputs(), 8, fixture.cpu());
        fixture.set("decline", true);
        assertEquals(2, admitted.push(2));
        fixture.set("decline", false);
        var next = endpoint.prepareBatch(fixture.pattern, fixture.inputs(), 8, fixture.cpu());
        fixture.set("prepareFailure", new IllegalStateException("lane disappeared"));
        assertEquals(2, next.push(2));
        assertEquals(0, fixture.get("submissions"));
        assertEquals(0, fixture.get("ordinaryPushes"));
    }

    @Test void parallelRejectionAndPreDispatchOverflowKeepEveryCopy() throws Exception {
        fixture.close();
        fixture = new Fixture("ParallelProvider");
        fixture.set("accept", false);
        var endpoint = fixture.endpoint();
        assertEquals(8, endpoint.prepareBatch(fixture.pattern, fixture.inputs(), 8, fixture.cpu()).push(8));
        assertEquals(1, fixture.get("parallelPushes"));
        fixture.set("accept", true);
        var input = new KeyCounter();
        input.add(fixture.raw, Long.MAX_VALUE);
        assertEquals(8, endpoint.prepareBatch(fixture.pattern, new KeyCounter[]{input}, 8, fixture.cpu()).push(8));
        assertEquals(1, fixture.get("parallelPushes"), "Overflow must be rejected before the provider is invoked");
        assertEquals(0, fixture.get("ordinaryPushes"));
    }

    @Test void providerArithmeticExceptionIsUncertainAfterTheParallelCallHasStarted() throws Exception {
        fixture.close();
        fixture = new Fixture("ParallelProvider");
        fixture.set("submitFailure", new ArithmeticException("provider failed after ownership transfer"));
        var cpu = fixture.cpu();
        cpu.run(1);
        assertEquals("AMBIGUOUS_BATCH_PROVIDER_OWNERSHIP", cpu.failure);
        assertEquals(0, cpu.stock.extract(fixture.raw, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0, fixture.get("ordinaryPushes"));
    }

    @Test void registryCanExposeParallelIntakeForAnExternalProvider() throws Exception {
        var adapterType = fixture.loader.loadClass(ECO + "api.me.ECOFastPathFacade$ParallelProvider");
        var adapter = adapterType.getConstructor().newInstance();
        var registry = fixture.loader.loadClass(ECO + "api.me.provider.ECOParallelCraftingProviders");
        registry.getField("adapter").set(null, adapter);
        var external = (ICraftingProvider) fixture.loader.loadClass(ECO + "api.me.ECOFastPathFacade$BaseProvider")
                .getConstructor().newInstance();
        var endpoint = fixture.resolver().resolve(external);
        assertNotNull(endpoint);
        assertEquals(0, endpoint.prepareBatch(fixture.pattern, fixture.inputs(), 8, fixture.cpu()).push(8));
        assertEquals(8L, adapterType.getField("accepted").get(adapter));
        assertEquals(fixture.jobId, adapterType.getField("jobId").get(adapter));
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader;
        final Class<?> compat;
        final ICraftingProvider provider;
        final AEItemKey raw = AEItemKey.of(Items.IRON_INGOT), output = AEItemKey.of(Items.GOLD_INGOT);
        final UUID jobId = UUID.randomUUID();
        final IPatternDetails pattern;
        Fixture(String type) throws Exception {
            this(contracts, type);
        }
        Fixture(Path contracts, String type) throws Exception {
            loader = new URLClassLoader(new URL[]{contracts.toUri().toURL(),
                    NeoEcoFastPathCompat.class.getProtectionDomain().getCodeSource().getLocation()},
                    NeoEcoFacadeContractTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        if (name.startsWith(CORE) || name.startsWith(ECO)) {
                            var loaded = findLoadedClass(name);
                            if (loaded == null) loaded = findClass(name);
                            if (resolve) resolveClass(loaded);
                            return loaded;
                        }
                        return super.loadClass(name, resolve);
                    }
                }
            };
            compat = loader.loadClass(CORE + "NeoEcoFastPathCompat");
            provider = (ICraftingProvider) loader.loadClass(ECO + "api.me.ECOFastPathFacade$" + type)
                    .getConstructor().newInstance();
            var input = new IPatternDetails.IInput() {
                public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(raw, 2)}; }
                public long getMultiplier() { return 1; }
                public boolean isValid(AEKey key, Level level) { return raw.equals(key); }
                public AEKey getRemainingKey(AEKey key) { return null; }
            };
            pattern = (IPatternDetails) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IPatternDetails.class}, (p, m, a) -> switch (m.getName()) {
                        case "getInputs" -> new IPatternDetails.IInput[]{input};
                        case "getOutputs" -> m.getReturnType().isArray()
                                ? new GenericStack[]{new GenericStack(output, 1)} : List.of(new GenericStack(output, 1));
                        case "supportsPushInputsToExternalInventory" -> true;
                        case "equals" -> p == a[0];
                        case "hashCode" -> System.identityHashCode(p);
                        case "toString" -> "facade-contract-pattern";
                        default -> m.getReturnType() == boolean.class ? false : null;
                    });
        }
        Object get(String field) throws Exception { return provider.getClass().getField(field).get(provider); }
        void set(String field, Object value) throws Exception { provider.getClass().getField(field).set(provider, value); }
        KeyCounter[] inputs() { var counter = new KeyCounter(); counter.add(raw, 2); return new KeyCounter[]{counter}; }
        BatchProviderResolver resolver() throws Exception {
            var method = compat.getDeclaredMethod("loadAdapter", ClassLoader.class);
            method.setAccessible(true);
            return (BatchProviderResolver) invoke(method, null, loader);
        }
        IBatchCraftingProvider endpoint() throws Exception { return Objects.requireNonNull(resolver().resolve(provider)); }
        Object exact(BigInteger count, KeyCounter[] inputs) throws Exception {
            return invoke(compat.getMethod("prepareExact", ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, BigInteger.class, Level.class, UUID.class), null,
                    provider, pattern, inputs, count, null, jobId);
        }
        Cpu cpu() throws Exception { return new Cpu(this); }
        public void close() throws Exception { loader.close(); }
    }

    private static final class Cpu implements BatchJobView {
        final Fixture fixture;
        final IBatchCraftingProvider endpoint;
        final ListCraftingInventory stock = new ListCraftingInventory(k -> {});
        final ListCraftingInventory waiting = new ListCraftingInventory(k -> {});
        final TickProviderDispatchSchedule schedule = new TickProviderDispatchSchedule();
        final Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batched = new IdentityHashMap<>();
        long remaining = 8;
        double energyDebited;
        String failure;
        Cpu(Fixture fixture) throws Exception {
            this.fixture = fixture;
            endpoint = fixture.endpoint();
            stock.insert(fixture.raw, 16, Actionable.MODULATE);
        }
        BatchExecutor.BatchRunResult run(long tick) {
            schedule.beginTick(tick);
            schedule.candidates(null, tick, fixture.pattern, () -> List.of(endpoint));
            IEnergyService energy = (IEnergyService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IEnergyService.class}, (p, m, a) -> {
                        if (m.getName().equals("extractAEPower")) {
                            if (a[1] == Actionable.MODULATE) energyDebited += (double) a[0];
                            return a[0];
                        }
                        return m.getReturnType() == double.class ? Double.MAX_VALUE : null;
                    });
            return BatchExecutor.runBatchOnly(10, BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH,
                    null, energy, this, stock, batched, () -> {}, Map.of(), 10, 8, false, schedule);
        }
        public Level level() { return null; }
        public UUID craftingId() { return fixture.jobId; }
        public ListCraftingInventory waitingFor() { return waiting; }
        public void addContainerMaxItems(long count, AEKeyType type) {}
        public void failDispatch(String reason, Throwable cause) { failure = reason; }
        public Iterator<BatchTaskHandle> taskIterator() {
            return new ArrayList<BatchTaskHandle>(List.of(new BatchTaskHandle() {
                public IPatternDetails details() { return fixture.pattern; }
                public long getValue() { return remaining; }
                public void setValue(long count) { remaining = count; }
            })).iterator();
        }
    }
}
