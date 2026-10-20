package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import javax.tools.ToolProvider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the optional public contracts in isolation; does not simulate a real NeoECO machine. */
class NeoEcoOptionalContractTest {
    private static final String CORE = "com.moakiee.thunderbolt.compat.neoeco.";
    private static final String API = "cn.dancingsnow.neoecoae.api.me.";
    @TempDir static Path temporary;
    private static Path contracts, oldFacade;
    private static String compilerClasspath;
    private Fixture fixture;

    @BeforeAll static void compileContracts() throws Exception {
        MinecraftTestBootstrap.ensureInitialized();
        var paths = new LinkedHashSet<String>();
        paths.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        for (ClassLoader loader = NeoEcoOptionalContractTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
            }
        }
        compilerClasspath = String.join(File.pathSeparator, paths);
        contracts = Files.createDirectory(temporary.resolve("contracts"));
        Path sources = Path.of(NeoEcoOptionalContractTest.class.getResource("/neoeco-contract").toURI());
        try (var files = Files.walk(sources)) {
            compile(contracts, files.filter(p -> p.toString().endsWith(".java")).toList());
        }
        oldFacade = Files.createDirectory(temporary.resolve("old-facade"));
        Path oldSource = temporary.resolve("ECOFastPathFacade.java");
        Files.writeString(oldSource, "package cn.dancingsnow.neoecoae.api.me; public final class ECOFastPathFacade {}\n");
        compile(oldFacade, List.of(oldSource));
    }

    private static void compile(Path output, List<Path> sources) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Contract verification requires the configured JDK");
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "--release", "17",
                "-classpath", compilerClasspath, "-d", output.toString()));
        sources.forEach(source -> args.add(source.toString()));
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                () -> diagnostics.toString(StandardCharsets.UTF_8));
    }

    @BeforeEach void open() throws Exception { fixture = new Fixture(false, false); }
    @AfterEach void close() throws Exception { fixture.close(); }

    @Test void unsupportedBatchAndLaterOrdinaryVisitKeepIdentityAndBorrowedInputs() throws Exception {
        var endpoint = fixture.endpoint();
        var inputs = fixture.inputs();
        endpoint.beginDispatchTick(10);
        assertEquals(4, endpoint.pushBatch(null, inputs, 5, fixture.job));
        assertEquals(fixture.id, fixture.receivedJob);
        assertEquals(2, inputs[0].get(fixture.key));
        assertEquals(1, endpoint.getBatchCapacity(null));
        assertTrue(fixture.ordinary(inputs));
        assertEquals(2, fixture.contextualCalls);
        assertEquals(0, fixture.directCalls);
        endpoint.beginDispatchTick(10);
        assertEquals(1, endpoint.getBatchCapacity(null));
        endpoint.beginDispatchTick(11);
        assertEquals(Long.MAX_VALUE, endpoint.getBatchCapacity(null));
    }

    @Test void explicitlyRejectedAllocationUsesOneContextualFallback() throws Exception {
        fixture.set("allocatedCount", 3L);
        fixture.set("accepted", false);
        assertEquals(4, fixture.endpoint().pushBatch(null, fixture.inputs(), 5, fixture.job));
        assertEquals(1, fixture.get("submissions"));
        assertEquals(1, fixture.contextualCalls);
        assertEquals(fixture.id, fixture.receivedJob);
    }

    @Test void indeterminateAllocatedAcceptanceKeepsExistingNoRefundProtection() throws Exception {
        fixture.set("allocatedCount", 3L);
        fixture.set("submitFailure", new IllegalStateException("ownership uncertain"));
        assertEquals(2, fixture.endpoint().pushBatch(null, fixture.inputs(), 5, fixture.job));
        assertEquals(1, fixture.get("submissions"));
        assertEquals(0, fixture.contextualCalls);
        assertEquals(0, fixture.directCalls);
    }

    @Test void ordinaryRejectionReturnsTheWholeOfferAndLeavesTheTemplateUntouched() throws Exception {
        fixture.acceptOrdinary = false;
        var inputs = fixture.inputs();
        assertEquals(5, fixture.endpoint().pushBatch(null, inputs, 5, fixture.job));
        assertEquals(2, inputs[0].get(fixture.key));
        assertEquals(fixture.id, fixture.receivedJob);
        assertEquals(1, fixture.contextualCalls);
    }

    @Test void ordinaryFailurePropagatesTheOriginalExceptionWithoutRetry() throws Exception {
        var failure = new IllegalStateException("ownership uncertain");
        fixture.ordinaryFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fixture.endpoint().pushBatch(null, fixture.inputs(), 5, fixture.job)));
        assertEquals(1, fixture.contextualCalls);
        assertEquals(0, fixture.directCalls);
    }

    @Test void ordinaryErrorAlsoPropagatesWithoutRetry() throws Exception {
        var failure = new AssertionError("provider failure");
        fixture.ordinaryFailure = failure;
        assertSame(failure, assertThrows(AssertionError.class, () -> fixture.ordinary(fixture.inputs())));
        assertEquals(1, fixture.contextualCalls);
        assertEquals(0, fixture.directCalls);
    }

    @Test void exactAdmissionPreservesBigIntegerCountsAndDefersSubmission() throws Exception {
        var requested = BigInteger.ONE.shiftLeft(126).add(BigInteger.TEN);
        var admitted = requested.subtract(BigInteger.ONE);
        fixture.set("exactCount", admitted);
        var inputs = fixture.inputs();
        var batch = fixture.exact(requested, inputs);
        assertNotNull(batch);
        assertEquals(admitted, call(batch.getClass().getMethod("craftCount"), batch));
        assertEquals(requested, fixture.get("requested"));
        assertEquals(fixture.id, fixture.get("preparedJob"));
        assertSame(inputs, fixture.get("preparedInputs"));
        assertEquals(2, inputs[0].get(fixture.key));
        assertEquals(0, fixture.get("submissions"));
        assertEquals(true, call(batch.getClass().getMethod("submit"), batch));
        assertEquals(1, fixture.get("submissions"));
    }

    @Test void exactDeclineDoesNotSubmitOrPerformOrdinaryFallback() throws Exception {
        assertNull(fixture.exact(BigInteger.TEN, fixture.inputs()));
        assertEquals(1, fixture.get("preparations"));
        assertEquals(0, fixture.get("submissions"));
        assertEquals(0, fixture.contextualCalls);
    }

    @Test void invalidExactCountsAreRejectedBeforeSubmission() throws Exception {
        for (var count : List.of(BigInteger.ZERO, BigInteger.ONE.negate(), BigInteger.valueOf(11))) {
            fixture.set("exactCount", count);
            assertThrows(IllegalArgumentException.class, () -> fixture.exact(BigInteger.TEN, fixture.inputs()));
        }
        assertEquals(0, fixture.get("submissions"));
    }

    @Test void exactPreparationFailurePropagatesBeforeOwnershipTransfers() throws Exception {
        var failure = new IllegalStateException("preparation failed");
        fixture.set("prepareFailure", failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fixture.exact(BigInteger.TEN, fixture.inputs())));
        assertEquals(0, fixture.get("submissions"));
    }

    @Test void exactSubmissionRejectionAndUncertainFailureRemainDistinct() throws Exception {
        fixture.set("exactCount", BigInteger.TEN);
        fixture.set("accepted", false);
        var declined = fixture.exact(BigInteger.TEN, fixture.inputs());
        assertEquals(false, call(declined.getClass().getMethod("submit"), declined));
        var failure = new IllegalStateException("exact ownership uncertain");
        fixture.set("submitFailure", failure);
        var uncertain = fixture.exact(BigInteger.TEN, fixture.inputs());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> call(uncertain.getClass().getMethod("submit"), uncertain)));
        assertEquals(2, fixture.get("submissions"));
        assertEquals(0, fixture.contextualCalls);
    }

    @Test void cancellationNotifiesTheJobAndContainsOptionalApiFailure() throws Exception {
        fixture.cancel();
        assertEquals(fixture.id, fixture.get("cancelledJob"));
        assertEquals(1, fixture.get("cancellations"));
        fixture.set("cancelFailure", new IllegalStateException("worker unavailable"));
        assertDoesNotThrow(fixture::cancel);
        assertEquals(2, fixture.get("cancellations"));
    }

    @Test void facadeWithoutNewMethodsRetainsOrdinaryDispatchAndOptionalNoOps() throws Exception {
        try (var old = new Fixture(true, false)) {
            assertTrue(old.ordinary(old.inputs()));
            assertEquals(1, old.directCalls);
            assertEquals(0, old.contextualCalls);
            assertNull(old.exact(BigInteger.TEN, old.inputs()));
            assertDoesNotThrow(old::cancel);
        }
    }

    @Test void forgeProtocolRetainsPriorityAndItsFallbackCapturesTheCurrentJob() throws Exception {
        try (var forge = new Fixture(false, true)) {
            var adapter = forge.adapter();
            assertEquals(CORE + "NeoEcoBatchAdapter", adapter.getClass().getName());
            var endpoint = adapter.resolve(forge.provider);
            assertNotNull(endpoint);
            var inputs = forge.inputs();
            var prepared = endpoint.prepareBatch(null, inputs, 5, forge.job);
            assertEquals(1, prepared.capacity());
            UUID captured = forge.id;
            forge.id = UUID.randomUUID(); // The borrowed live job view must not be retained.
            assertEquals(0, prepared.push(1));
            assertEquals(captured, forge.receivedJob);
            assertEquals(2, inputs[0].get(forge.key));
            assertEquals(1, forge.contextualCalls);
            assertEquals(0, forge.directCalls);
            assertEquals(1, endpoint.getBatchCapacity(null));
            assertEquals(0, endpoint.prepareBatch(null, inputs, 1, forge.job).push(1));
            assertEquals(forge.id, forge.receivedJob);
        }
    }

    private static Object call(Method method, Object receiver, Object... arguments) throws Exception {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            if (failed.getCause() instanceof Error error) throw error;
            throw new AssertionError(failed.getCause());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader;
        final Class<?> compat, facade;
        final ICraftingProvider provider;
        final AEItemKey key = AEItemKey.of(Items.IRON_INGOT);
        UUID id = UUID.randomUUID(), receivedJob;
        int contextualCalls, directCalls;
        boolean acceptOrdinary = true;
        Throwable ordinaryFailure;
        final BatchJobView job = (BatchJobView) Proxy.newProxyInstance(BatchJobView.class.getClassLoader(),
                new Class<?>[] {BatchJobView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "craftingId" -> id;
                    case "level" -> null;
                    default -> throw new AssertionError(method.getName());
                });

        Fixture(boolean legacyFacade, boolean forgeProtocol) throws Exception {
            var urls = new ArrayList<URL>();
            if (legacyFacade) urls.add(oldFacade.toUri().toURL());
            urls.add(contracts.toUri().toURL());
            urls.add(NeoEcoFastPathCompat.class.getProtectionDomain().getCodeSource().getLocation());
            loader = new URLClassLoader(urls.toArray(URL[]::new), NeoEcoOptionalContractTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        if (!forgeProtocol && name.equals(API + "ECOBatchCapacityProvider")) throw new ClassNotFoundException(name);
                        if (name.startsWith(CORE) || name.startsWith("cn.dancingsnow.neoecoae.")) {
                            Class<?> type = findLoadedClass(name);
                            if (type == null) type = findClass(name);
                            if (resolve) resolveClass(type);
                            return type;
                        }
                        return super.loadClass(name, resolve);
                    }
                }
            };
            compat = loader.loadClass(CORE + "NeoEcoFastPathCompat");
            facade = loader.loadClass(API + "ECOFastPathFacade");
            var interfaces = new ArrayList<Class<?>>(List.of(loader.loadClass(API + "provider.ECOJobAwareCraftingProvider"),
                    loader.loadClass(API + "provider.ECOFastPathDispatchProvider")));
            if (forgeProtocol) interfaces.add(loader.loadClass(API + "ECOBatchCapacityProvider"));
            provider = (ICraftingProvider) Proxy.newProxyInstance(loader, interfaces.toArray(Class<?>[]::new),
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAvailablePatterns" -> List.of();
                        case "isBusy" -> false;
                        case "pushPattern" -> {
                            if (args.length == 3) { contextualCalls++; receivedJob = (UUID) args[2]; }
                            else directCalls++;
                            if (ordinaryFailure != null) throw ordinaryFailure;
                            if (acceptOrdinary && ((KeyCounter[]) args[1]).length > 0) ((KeyCounter[]) args[1])[0].reset();
                            yield acceptOrdinary;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("Unexpected provider call: " + method.getName());
                    });
        }

        BatchProviderResolver adapter() throws Exception {
            var load = compat.getDeclaredMethod("loadAdapter", ClassLoader.class);
            load.setAccessible(true);
            return (BatchProviderResolver) call(load, null, loader);
        }
        IBatchCraftingProvider endpoint() throws Exception { return adapter().resolve(provider); }
        KeyCounter[] inputs() { var counter = new KeyCounter(); counter.add(key, 2); return new KeyCounter[] {counter}; }
        Object get(String name) throws Exception { return facade.getField(name).get(null); }
        void set(String name, Object value) throws Exception { facade.getField(name).set(null, value); }
        boolean ordinary(KeyCounter[] inputs) throws Exception {
            return (boolean) call(compat.getMethod("pushPattern", ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, UUID.class), null, provider, null, inputs, id);
        }
        Object exact(BigInteger requested, KeyCounter[] inputs) throws Exception {
            return call(compat.getMethod("prepareExact", ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, BigInteger.class, Level.class, UUID.class), null, provider, null, inputs, requested, null, id);
        }
        void cancel() throws Exception { call(compat.getMethod("cancelJob", Level.class, UUID.class), null, null, id); }
        @Override public void close() throws Exception { loader.close(); }
    }
}
