package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Exercises ordinary 1.20.1 signatures in isolation, without simulating a real ECO machine. */
class NeoEcoNativeContractTest {
    private static final String CORE = "com.moakiee.thunderbolt.compat.neoeco.";
    private static final String ECO = "cn.dancingsnow.neoecoae.";
    @TempDir static Path temporary;
    private static Path contracts;
    private Fixture fixture;

    @BeforeAll static void compileContracts() throws Exception {
        MinecraftTestBootstrap.ensureInitialized();
        var paths = new LinkedHashSet<>(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        for (ClassLoader loader = NeoEcoNativeContractTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) for (URL url : urls.getURLs())
                if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
        }
        contracts = Files.createDirectory(temporary.resolve("contracts"));
        var sources = Path.of(NeoEcoNativeContractTest.class.getResource("/neoeco-native-contract").toURI());
        var args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "--release", "17",
                "-classpath", String.join(File.pathSeparator, paths), "-d", contracts.toString()));
        try (var files = Files.walk(sources)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                () -> diagnostics.toString(StandardCharsets.UTF_8));
    }

    @BeforeEach void open() throws Exception { fixture = new Fixture(); }
    @AfterEach void close() throws Exception { fixture.close(); }

    @Test void nativeOrdinaryDispatchRetainsTheJob() throws Exception {
        assertTrue(fixture.ordinary(fixture.inputs()));
        assertEquals(fixture.job, fixture.get("ordinaryJob"));
        assertEquals(1, fixture.get("nativePushes"));
        assertEquals(0, fixture.get("directPushes"));
    }

    @Test void nativeRejectionAndFailureDoNotRetryAnotherDispatch() throws Exception {
        fixture.set("accept", false);
        assertFalse(fixture.ordinary(fixture.inputs()));
        var failure = new IllegalStateException("ownership uncertain");
        fixture.set("ordinaryFailure", failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.ordinary(fixture.inputs())));
        assertEquals(2, fixture.get("nativePushes"));
        assertEquals(0, fixture.get("directPushes"));
    }

    @Test void deferredFallbackOwnsInputsAndCapturesThePreparationJob() throws Exception {
        var resolver = (BatchProviderResolver) fixture.call("loadAdapter", new Class<?>[]{ClassLoader.class}, fixture.loader);
        assertNotNull(resolver);
        var endpoint = resolver.resolve(fixture.provider);
        assertNotNull(endpoint);
        var template = fixture.inputs();
        var job = (BatchJobView) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BatchJobView.class},
                (p, m, a) -> switch (m.getName()) {
                    case "level" -> null;
                    case "craftingId" -> fixture.job;
                    default -> throw new AssertionError(m.getName());
                });
        UUID captured = fixture.job;
        var prepared = endpoint.prepareBatch(null, template, 5, job);
        assertEquals(1, prepared.capacity());
        fixture.job = UUID.randomUUID();
        assertEquals(0, prepared.push(1));
        assertEquals(captured, fixture.get("ordinaryJob"));
        ((KeyCounter[]) fixture.get("ordinaryInputs"))[0].reset();
        assertEquals(2, template[0].get(fixture.key));
    }

    @Test void exactPreparationPreservesHugeCountsAndOwnsItsContext() throws Exception {
        var requested = BigInteger.ONE.shiftLeft(126).add(BigInteger.TEN);
        fixture.set("admitted", requested.subtract(BigInteger.ONE));
        var template = fixture.inputs();
        var batch = fixture.exact(requested, template);
        assertNotNull(batch);
        assertEquals(requested.subtract(BigInteger.ONE), invoke(batch.getClass().getMethod("craftCount"), batch));
        assertEquals(requested, fixture.get("requested"));
        assertEquals(0, fixture.get("submissions"));
        var context = fixture.get("context");
        assertEquals(fixture.job, invoke(context.getClass().getMethod("craftingJobId"), context));
        template[0].reset();
        assertEquals(2, ((KeyCounter[]) invoke(context.getClass().getMethod("inputCounters"), context))[0].get(fixture.key));
        assertEquals(true, invoke(batch.getClass().getMethod("submit"), batch));
        assertEquals(1, fixture.get("submissions"));
    }

    @Test void invalidAdmissionsAreRejectedBeforeSubmission() throws Exception {
        for (var count : Arrays.asList(null, BigInteger.ZERO, BigInteger.ONE.negate(), BigInteger.valueOf(11))) {
            fixture.set("admitted", count);
            assertThrows(IllegalArgumentException.class, () -> fixture.exact(BigInteger.TEN, fixture.inputs()));
        }
        assertEquals(0, fixture.get("submissions"));
    }

    @Test void nonPositiveRequestsDoNotEnterTheProvider() throws Exception {
        for (var count : List.of(BigInteger.ZERO, BigInteger.ONE.negate()))
            assertNull(fixture.exact(count, fixture.inputs()));
        assertEquals(0, fixture.get("preparations"));
    }

    @Test void preparationDeclineAndFailureNeverSubmitOrFallBack() throws Exception {
        fixture.set("decline", true);
        assertNull(fixture.exact(BigInteger.TEN, fixture.inputs()));
        fixture.set("decline", false);
        fixture.set("prepareFailure", new IllegalStateException("preparation failed"));
        assertThrows(IllegalStateException.class, () -> fixture.exact(BigInteger.TEN, fixture.inputs()));
        assertEquals(0, fixture.get("submissions"));
        assertEquals(0, fixture.get("nativePushes"));
        assertEquals(0, fixture.get("directPushes"));
    }

    @Test void missingDispatchAndEmptyContextsDeclineBeforeOwnershipTransfers() throws Exception {
        fixture.set("missingDispatch", true);
        assertThrows(IllegalArgumentException.class, () -> fixture.exact(BigInteger.TEN, fixture.inputs()));
        fixture.loader.loadClass(ECO + "crafting.execution.fastpath.ECOExtractedPatternExecution")
                .getField("emptyOutputs").set(null, true);
        assertNull(fixture.exact(BigInteger.TEN, fixture.inputs()));
        assertEquals(1, fixture.get("preparations"));
        assertEquals(0, fixture.get("submissions"));
    }

    @Test void submissionRejectionAndUncertainFailureRemainDistinct() throws Exception {
        fixture.set("accept", false);
        var rejected = fixture.exact(BigInteger.TEN, fixture.inputs());
        assertEquals(false, invoke(rejected.getClass().getMethod("submit"), rejected));
        var failure = new IllegalStateException("submission ownership uncertain");
        fixture.set("submitFailure", failure);
        var uncertain = fixture.exact(BigInteger.TEN, fixture.inputs());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> invoke(uncertain.getClass().getMethod("submit"), uncertain)));
        assertEquals(2, fixture.get("submissions"));
        assertEquals(0, fixture.get("directPushes"));
    }

    @Test void nativeCancellationRetainsIdentityAndContainsOptionalFailure() throws Exception {
        var lifecycle = fixture.loader.loadClass(ECO + "crafting.execution.worker.ECOCraftingJobLifecycle");
        fixture.cancel();
        assertEquals(fixture.job, lifecycle.getField("job").get(null));
        lifecycle.getField("failure").set(null, new IllegalStateException("worker unavailable"));
        assertDoesNotThrow(fixture::cancel);
        assertEquals(2, lifecycle.getField("calls").get(null));
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
        final AEItemKey key = AEItemKey.of(Items.IRON_INGOT);
        UUID job = UUID.randomUUID();
        Fixture() throws Exception {
            loader = new URLClassLoader(new URL[]{contracts.toUri().toURL(),
                    NeoEcoFastPathCompat.class.getProtectionDomain().getCodeSource().getLocation()},
                    NeoEcoNativeContractTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        if (name.startsWith(CORE) || name.startsWith(ECO)) {
                            var type = findLoadedClass(name);
                            if (type == null) type = findClass(name);
                            if (resolve) resolveClass(type);
                            return type;
                        }
                        return super.loadClass(name, resolve);
                    }
                }
            };
            compat = loader.loadClass(CORE + "NeoEcoFastPathCompat");
            provider = (ICraftingProvider) loader.loadClass(ECO + "blocks.entity.crafting.ECOCraftingPatternBusBlockEntity")
                    .getConstructor().newInstance();
        }
        KeyCounter[] inputs() { var counter = new KeyCounter(); counter.add(key, 2); return new KeyCounter[]{counter}; }
        Object get(String name) throws Exception { return provider.getClass().getField(name).get(provider); }
        void set(String name, Object value) throws Exception { provider.getClass().getField(name).set(provider, value); }
        Object call(String name, Class<?>[] types, Object... args) throws Exception {
            var method = compat.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return invoke(method, null, args);
        }
        boolean ordinary(KeyCounter[] inputs) throws Exception {
            return (boolean) call("pushPattern", new Class<?>[]{ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, UUID.class}, provider, null, inputs, job);
        }
        Object exact(BigInteger requested, KeyCounter[] inputs) throws Exception {
            return call("prepareExact", new Class<?>[]{ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, BigInteger.class, Level.class, UUID.class},
                    provider, null, inputs, requested, null, job);
        }
        void cancel() throws Exception { call("cancelJob", new Class<?>[]{Level.class, UUID.class}, null, job); }
        public void close() throws Exception { loader.close(); }
    }
}
