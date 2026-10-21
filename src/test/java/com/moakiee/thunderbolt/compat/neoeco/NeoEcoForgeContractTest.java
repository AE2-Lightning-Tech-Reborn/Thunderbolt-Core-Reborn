package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.*;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Native Forge contracts are isolated from the facade fixtures and real optional mod jars. */
class NeoEcoForgeContractTest {
    private static final String CORE = "com.moakiee.thunderbolt.compat.neoeco.";
    private static final String API = "cn.dancingsnow.neoecoae.api.me.";
    private static final String BUS = "cn.dancingsnow.neoecoae.blocks.entity.crafting.ECOCraftingPatternBusBlockEntity";
    private static final String LIFECYCLE = "cn.dancingsnow.neoecoae.crafting.execution.worker.ECOCraftingJobLifecycle";
    @TempDir static Path temporary;
    private static Path contracts;
    private Fixture fixture;

    @BeforeAll static void compileContracts() throws Exception {
        MinecraftTestBootstrap.ensureInitialized();
        var paths = new LinkedHashSet<String>();
        paths.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        for (ClassLoader loader = NeoEcoForgeContractTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls)
                for (URL url : urls.getURLs())
                    if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
        }
        contracts = Files.createDirectory(temporary.resolve("contracts"));
        var sources = new ArrayList<Path>();
        Path common = Path.of(NeoEcoForgeContractTest.class.getResource("/neoeco-contract").toURI());
        try (var files = Files.walk(common)) {
            files.filter(p -> p.toString().endsWith(".java")
                    && !p.getFileName().toString().equals("ECOExtractedPatternExecution.java")).forEach(sources::add);
        }
        Path nativeSources = Path.of(NeoEcoForgeContractTest.class.getResource("/neoeco-forge-contract").toURI());
        try (var files = Files.walk(nativeSources)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "--release", "17",
                "-classpath", String.join(File.pathSeparator, paths), "-d", contracts.toString()));
        sources.forEach(source -> args.add(source.toString()));
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                () -> diagnostics.toString(StandardCharsets.UTF_8));
    }

    @BeforeEach void open() throws Exception { fixture = new Fixture(false); }
    @AfterEach void close() throws Exception { fixture.close(); }

    @Test void nativeOrdinaryDispatchRetainsIdentityWithoutTheFacade() throws Exception {
        assertTrue(fixture.ordinary(fixture.inputs()));
        assertEquals(fixture.id, fixture.get("ordinaryJob"));
        assertEquals(1, fixture.get("ordinaryCalls"));
        assertEquals(0, fixture.get("directCalls"));
    }

    @Test void nativeOrdinaryRejectionAndFailuresNeverRetryAnotherContract() throws Exception {
        try (var both = new Fixture(true)) {
            both.set("accepted", false);
            assertFalse(both.ordinary(both.inputs()));
            var failure = new IllegalStateException("ownership uncertain");
            both.set("ordinaryFailure", failure);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> both.ordinary(both.inputs())));
            var error = new AssertionError("provider error");
            both.set("ordinaryFailure", error);
            assertSame(error, assertThrows(AssertionError.class, () -> both.ordinary(both.inputs())));
            assertEquals(3, both.get("ordinaryCalls"));
            assertEquals(0, both.get("directCalls"));
            assertEquals(0, both.facade("ordinaryCalls"));
        }
    }

    @Test void nativeFallbackOwnsInputsAndCapturesThePreparationJob() throws Exception {
        var endpoint = fixture.adapter().resolve(fixture.provider);
        assertNotNull(endpoint);
        var inputs = fixture.inputs();
        var prepared = endpoint.prepareBatch(fixture.pattern, inputs, 5, fixture.job);
        assertEquals(1, prepared.capacity());
        UUID captured = fixture.id;
        fixture.id = UUID.randomUUID();
        assertEquals(0, prepared.push(1));
        assertEquals(captured, fixture.get("ordinaryJob"));
        assertEquals(2, inputs[0].get(fixture.key));
        assertEquals(1, fixture.get("ordinaryCalls"));
    }

    @Test void nativeExactPreparationRetainsHugeCountsAndOwnsItsContext() throws Exception {
        var requested = BigInteger.ONE.shiftLeft(150).add(BigInteger.TEN);
        var admitted = requested.subtract(BigInteger.ONE);
        fixture.set("admitted", admitted);
        var inputs = fixture.inputs();
        var batch = fixture.exact(requested, inputs);
        assertNotNull(batch);
        assertEquals(admitted, call(batch.getClass().getMethod("craftCount"), batch));
        assertEquals(requested, fixture.get("requested"));
        Object context = fixture.get("context");
        assertEquals(fixture.id, call(context.getClass().getMethod("jobId"), context));
        var slots = (List<?>) call(context.getClass().getMethod("inputs"), context);
        inputs[0].reset();
        assertEquals(new GenericStack(fixture.key, 2), ((List<?>) slots.get(0)).get(0));
        assertEquals(0, fixture.get("submissions"));
        assertEquals(true, call(batch.getClass().getMethod("submit"), batch));
        assertEquals(1, fixture.get("submissions"));
    }

    @Test void invalidNativeAdmissionsAndMissingDispatchFailBeforeSubmission() throws Exception {
        for (var amount : Arrays.asList(null, BigInteger.ZERO, BigInteger.ONE.negate(), BigInteger.valueOf(11))) {
            fixture.set("admitted", amount);
            assertThrows(IllegalArgumentException.class, () -> fixture.exact(BigInteger.TEN, fixture.inputs()));
        }
        fixture.set("admitted", BigInteger.ONE);
        fixture.set("missingDispatch", true);
        assertThrows(IllegalArgumentException.class, () -> fixture.exact(BigInteger.TEN, fixture.inputs()));
        assertEquals(0, fixture.get("submissions"));
    }

    @Test void nonPositiveRequestsDoNotEnterEitherPreparationContract() throws Exception {
        try (var both = new Fixture(true)) {
            assertNull(both.exact(BigInteger.ZERO, both.inputs()));
            assertNull(both.exact(BigInteger.ONE.negate(), both.inputs()));
            assertEquals(0, both.get("preparations"));
            assertEquals(0, both.facade("preparations"));
        }
    }

    @Test void nativeDeclineAndPreparationFailuresDoNotTryTheFacade() throws Exception {
        try (var both = new Fixture(true)) {
            both.setFacade("exactCount", BigInteger.TEN);
            both.set("decline", true);
            assertNull(both.exact(BigInteger.TEN, both.inputs()));
            both.set("decline", false);
            var failure = new IllegalStateException("preparation failed");
            both.set("prepareFailure", failure);
            var thrown = assertThrows(IllegalStateException.class, () -> both.exact(BigInteger.TEN, both.inputs()));
            assertSame(failure, thrown.getCause());
            var error = new AssertionError("preparation error");
            both.set("prepareFailure", error);
            assertSame(error, assertThrows(AssertionError.class, () -> both.exact(BigInteger.TEN, both.inputs())));
            assertEquals(0, both.facade("preparations"));
            assertEquals(0, both.get("submissions"));
        }
    }

    @Test void nativeSubmissionRejectionAndFailuresKeepAtomicOwnershipSemantics() throws Exception {
        try (var both = new Fixture(true)) {
            both.set("accepted", false);
            var batch = both.exact(BigInteger.TEN, both.inputs());
            assertEquals(false, call(batch.getClass().getMethod("submit"), batch));
            for (Throwable failure : List.of(new IllegalStateException("uncertain"), new AssertionError("failed"))) {
                both.set("submitFailure", failure);
                var next = both.exact(BigInteger.TEN, both.inputs());
                assertSame(failure, assertThrows(failure.getClass(), () -> call(next.getClass().getMethod("submit"), next)));
            }
            assertEquals(3, both.get("submissions"));
            assertEquals(0, both.get("ordinaryCalls"));
            assertEquals(0, both.facade("preparations"));
        }
    }

    @Test void nativeCancellationTakesPriorityAndContainsOptionalFailure() throws Exception {
        try (var both = new Fixture(true)) {
            both.cancel();
            assertEquals(both.id, both.lifecycle("cancelledJob"));
            assertEquals(1, both.lifecycle("cancellations"));
            both.loader.loadClass(LIFECYCLE).getField("cancelFailure")
                    .set(null, new IllegalStateException("worker unavailable"));
            assertDoesNotThrow(both::cancel);
            assertEquals(2, both.lifecycle("cancellations"));
            assertEquals(0, both.facade("cancellations"));
        }
    }

    @Test void oldNativeProtocolStillLinksWithoutOptionalExactAndLifecycleTypes() throws Exception {
        try (var old = new Fixture(false, Set.of(API + "ECOExactBatchProvider", LIFECYCLE), false)) {
            assertEquals(CORE + "NeoEcoBatchAdapter", old.adapter().getClass().getName());
            assertNull(old.exact(BigInteger.TEN, old.inputs()));
            assertDoesNotThrow(old::cancel);
        }
    }

    @Test void facadeRemainsAvailableWhenNativeOptionalTypesAreMissing() throws Exception {
        try (var fallback = new Fixture(true, Set.of(API + "ECOExactBatchProvider", LIFECYCLE), false)) {
            fallback.setFacade("exactCount", BigInteger.valueOf(3));
            var batch = fallback.exact(BigInteger.TEN, fallback.inputs());
            assertEquals(BigInteger.valueOf(3), call(batch.getClass().getMethod("craftCount"), batch));
            assertEquals(true, call(batch.getClass().getMethod("submit"), batch));
            assertEquals(1, fallback.facade("preparations"));
            fallback.cancel();
            assertEquals(fallback.id, fallback.facade("cancelledJob"));
            assertEquals(1, fallback.facade("cancellations"));
        }
    }

    @Test void emptyNativeContextDeclinesWithoutCallingTheFacade() throws Exception {
        try (var both = new Fixture(true)) {
            both.emptyOutputs = true;
            assertNull(both.exact(BigInteger.TEN, both.inputs()));
            assertEquals(0, both.get("preparations"));
            assertEquals(0, both.facade("preparations"));
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
        final Class<?> compat;
        final ICraftingProvider provider;
        final AEItemKey key = AEItemKey.of(Items.IRON_INGOT);
        UUID id = UUID.randomUUID();
        boolean emptyOutputs;
        final IPatternDetails pattern = (IPatternDetails) Proxy.newProxyInstance(IPatternDetails.class.getClassLoader(),
                new Class<?>[] {IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getOutputs" -> emptyOutputs ? new GenericStack[0] : new GenericStack[] {new GenericStack(key, 3)};
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                });
        final BatchJobView job = (BatchJobView) Proxy.newProxyInstance(BatchJobView.class.getClassLoader(),
                new Class<?>[] {BatchJobView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "craftingId" -> id;
                    case "level" -> null;
                    default -> throw new AssertionError(method.getName());
                });

        Fixture(boolean facade) throws Exception { this(facade, Set.of(), true); }
        Fixture(boolean facade, Set<String> unavailable, boolean patternBus) throws Exception {
            var urls = new URL[] {contracts.toUri().toURL(),
                    NeoEcoFastPathCompat.class.getProtectionDomain().getCodeSource().getLocation()};
            loader = new URLClassLoader(urls, NeoEcoForgeContractTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        if (unavailable.contains(name) || !facade && name.startsWith(API + "ECOFastPathFacade"))
                            throw new ClassNotFoundException(name);
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
            provider = patternBus ? (ICraftingProvider) loader.loadClass(BUS).getConstructor().newInstance()
                    : (ICraftingProvider) Proxy.newProxyInstance(loader, new Class<?>[] {
                            loader.loadClass(API + "ECOBatchCapacityProvider"),
                            loader.loadClass(API + "provider.ECOFastPathDispatchProvider")},
                            (proxy, method, args) -> { throw new AssertionError("Unexpected call: " + method.getName()); });
        }

        Object get(String name) throws Exception { return provider.getClass().getField(name).get(provider); }
        void set(String name, Object value) throws Exception { provider.getClass().getField(name).set(provider, value); }
        Object facade(String name) throws Exception { return loader.loadClass(API + "ECOFastPathFacade").getField(name).get(null); }
        void setFacade(String name, Object value) throws Exception { loader.loadClass(API + "ECOFastPathFacade").getField(name).set(null, value); }
        Object lifecycle(String name) throws Exception { return loader.loadClass(LIFECYCLE).getField(name).get(null); }
        KeyCounter[] inputs() { var counter = new KeyCounter(); counter.add(key, 2); return new KeyCounter[] {counter}; }
        BatchProviderResolver adapter() throws Exception {
            var method = compat.getDeclaredMethod("loadAdapter", ClassLoader.class);
            method.setAccessible(true);
            return (BatchProviderResolver) call(method, null, loader);
        }
        boolean ordinary(KeyCounter[] inputs) throws Exception {
            return (boolean) call(compat.getMethod("pushPattern", ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, UUID.class), null, provider, pattern, inputs, id);
        }
        Object exact(BigInteger requested, KeyCounter[] inputs) throws Exception {
            return call(compat.getMethod("prepareExact", ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, BigInteger.class, Level.class, UUID.class),
                    null, provider, pattern, inputs, requested, null, id);
        }
        void cancel() throws Exception { call(compat.getMethod("cancelJob", Level.class, UUID.class), null, null, id); }
        @Override public void close() throws Exception { loader.close(); }
    }
}
