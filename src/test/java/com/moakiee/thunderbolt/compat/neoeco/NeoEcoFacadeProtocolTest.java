package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Runs the real supplied NeoECO facade/planner/transaction, with proxy providers and no physical machine. */
@EnabledIfSystemProperty(named = "thunderbolt.neoecoProtocolPath", matches = ".+")
class NeoEcoFacadeProtocolTest {
    private static final String CORE = "com.moakiee.thunderbolt.compat.neoeco.";
    private static final String ECO = "cn.dancingsnow.neoecoae.";
    private static URLClassLoader loader;
    private static Class<?> compat, fastPath, preparation, exactPreparation;
    private static BatchProviderResolver resolver;
    private static AEKey raw, output;
    private static final ResourceLocation OPTIMIZATION = new ResourceLocation("thunderbolt_test", "facade");

    @BeforeAll static void setup() throws Exception {
        MinecraftTestBootstrap.ensureInitialized();
        raw = AEItemKey.of(Items.IRON_INGOT);
        output = AEItemKey.of(Items.GOLD_INGOT);
        var urls = new ArrayList<URL>();
        for (var path : System.getProperty("thunderbolt.neoecoProtocolPath").split(java.io.File.pathSeparator))
            urls.add(Path.of(path).toUri().toURL());
        urls.add(NeoEcoFastPathCompat.class.getProtectionDomain().getCodeSource().getLocation());
        loader = new URLClassLoader(urls.toArray(URL[]::new), NeoEcoFacadeProtocolTest.class.getClassLoader()) {
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
        try { fastPath = loader.loadClass(ECO + "api.me.provider.ECOFastPathDispatchProvider"); }
        catch (ClassNotFoundException legacy) { Assumptions.abort("Legacy native protocol is exercised by NeoEcoBatchProtocolTest"); }
        compat = loader.loadClass(CORE + "NeoEcoFastPathCompat");
        var load = compat.getDeclaredMethod("loadAdapter", ClassLoader.class);
        load.setAccessible(true);
        resolver = (BatchProviderResolver) call(load, null, loader);
        assertNotNull(resolver, "Real NeoECO allocated facade must link");
        assertEquals(CORE + "NeoEcoFastPathBatchAdapter", resolver.getClass().getName());
        preparation = loader.loadClass(fastPath.getName() + "$Preparation");
        exactPreparation = loader.loadClass(fastPath.getName() + "$ExactPreparation");
        var optimization = loader.loadClass(ECO + "api.me.pattern.ECOExecutionOptimization");
        var adapterType = loader.loadClass(ECO + "api.me.pattern.ECOExecutionOptimizationAdapter");
        var verified = optimization.getField("VERIFIED_ASSEMBLER").get(null);
        var adapter = Proxy.newProxyInstance(loader, new Class<?>[]{adapterType}, (p, m, a) -> switch (m.getName()) {
            case "supports" -> true;
            case "describe" -> verified;
            default -> throw new AssertionError(m);
        });
        call(loader.loadClass(ECO + "api.me.pattern.ECOExecutionOptimizationRegistry")
                .getMethod("register", ResourceLocation.class, int.class, adapterType), null, OPTIMIZATION, 100, adapter);
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void realFacadeCommitsOnlyTheAllocatedBatchAndPreservesJobAndUnitInputs() throws Exception {
        var f = new Fixture(false);
        var inputs = f.inputs();
        var admitted = f.endpoint().prepareBatch(f.pattern, inputs, 8, f.job);
        assertEquals(8, admitted.capacity());
        assertEquals(0, f.pushes);
        assertEquals(0, admitted.push(8));
        assertEquals(8, f.offered);
        assertEquals(16, f.totalInput);
        assertEquals(8, f.totalOutput);
        assertEquals(f.job.craftingId(), f.contextJob);
        assertEquals(2, inputs[0].get(raw));
        assertThrows(IllegalStateException.class, () -> admitted.push(8));
    }

    @Test void realFacadeRollbackOnOrdinaryExceptionReturnsEveryOfferedCopy() throws Exception {
        var f = new Fixture(false);
        f.submitFailure = new IllegalStateException("not accepted");
        assertEquals(8, f.endpoint().prepareBatch(f.pattern, f.inputs(), 8, f.job).push(8));
        assertEquals(1, f.pushes);
        assertEquals(0, f.ordinary);
    }

    @Test void realIndeterminateExceptionStopsBeforeOrdinaryFallback() throws Exception {
        var f = new Fixture(false);
        f.submitFailure = (RuntimeException) loader.loadClass(ECO + "api.me.provider.ECOIndeterminateBatchException")
                .getConstructor(String.class, Throwable.class).newInstance("uncertain", null);
        assertThrows(RuntimeException.class,
                () -> f.endpoint().prepareBatch(f.pattern, f.inputs(), 8, f.job).push(8));
        assertEquals(1, f.pushes);
        assertEquals(0, f.ordinary);
    }

    @Test void realExactAllocatedFacadeNeverNarrowsTheRequestedCount() throws Exception {
        var f = new Fixture(false);
        var huge = BigInteger.ONE.shiftLeft(130).add(BigInteger.TEN);
        var inputs = f.inputs();
        var batch = call(compat.getMethod("prepareExact", ICraftingProvider.class, IPatternDetails.class,
                KeyCounter[].class, BigInteger.class, Level.class, UUID.class), null,
                f.provider, f.pattern, inputs, huge, null, f.job.craftingId());
        assertNotNull(batch);
        assertEquals(huge, call(batch.getClass().getMethod("craftCount"), batch));
        assertEquals(huge, f.exactRequest);
        assertEquals(f.job.craftingId(), f.contextJob);
        assertEquals(true, call(batch.getClass().getMethod("submit"), batch));
        assertThrows(IllegalStateException.class, () -> call(batch.getClass().getMethod("submit"), batch));
        assertEquals(1, f.pushes);
        assertEquals(2, inputs[0].get(raw));
    }

    @Test void realParallelProtocolUsesIntCapacityAndOwnedTotalInputs() throws Exception {
        var f = new Fixture(true);
        var inputs = f.inputs();
        var batch = f.endpoint().prepareBatch(f.pattern, inputs, 8, f.job);
        assertEquals(8, batch.capacity());
        f.capacity = 3;
        assertEquals(5, batch.push(8));
        assertEquals(3, f.offered);
        assertEquals(6, f.totalInput);
        assertEquals(2, inputs[0].get(raw));
        assertEquals(f.job.craftingId(), f.contextJob);
    }

    private static Object call(Method method, Object receiver, Object... arguments) throws Exception {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    private static final class Fixture implements InvocationHandler {
        final ICraftingProvider provider;
        final IPatternDetails pattern;
        final BatchJobView job;
        long capacity = 8, offered, totalInput, totalOutput;
        int pushes, ordinary;
        UUID contextJob;
        BigInteger exactRequest;
        RuntimeException submitFailure;

        Fixture(boolean parallel) throws Exception {
            var input = (IPatternDetails.IInput) Proxy.newProxyInstance(loader,
                    new Class<?>[]{IPatternDetails.IInput.class}, (p, m, a) -> switch (m.getName()) {
                        case "getPossibleInputs" -> new GenericStack[]{new GenericStack(raw, 2)};
                        case "getMultiplier" -> 1L;
                        case "isValid" -> raw.equals(a[0]);
                        case "getRemainingKey" -> null;
                        default -> throw new AssertionError(m);
                    });
            pattern = (IPatternDetails) Proxy.newProxyInstance(loader, new Class<?>[]{IPatternDetails.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getInputs" -> new IPatternDetails.IInput[]{input};
                        case "getOutputs" -> new GenericStack[]{new GenericStack(output, 1)};
                        case "getPrimaryOutput" -> new GenericStack(output, 1);
                        case "getDefinition" -> AEItemKey.of(Items.PAPER);
                        case "supportsPushInputsToExternalInventory" -> true;
                        case "equals" -> p == a[0];
                        case "hashCode" -> System.identityHashCode(p);
                        default -> m.getReturnType() == boolean.class ? false : null;
                    });
            var type = parallel ? loader.loadClass(ECO + "api.me.provider.ECOParallelCraftingProvider") : fastPath;
            provider = (ICraftingProvider) Proxy.newProxyInstance(loader,
                    new Class<?>[]{ICraftingProvider.class, type}, this);
            var id = UUID.randomUUID();
            job = new BatchJobView() {
                public Level level() { return null; }
                public UUID craftingId() { return id; }
                public Iterator<BatchTaskHandle> taskIterator() { return Collections.emptyIterator(); }
                public ListCraftingInventory waitingFor() { return new ListCraftingInventory(k -> {}); }
                public void addContainerMaxItems(long count, AEKeyType type) {}
            };
        }
        KeyCounter[] inputs() { var c = new KeyCounter(); c.add(raw, 2); return new KeyCounter[]{c}; }
        IBatchCraftingProvider endpoint() { return Objects.requireNonNull(resolver.resolve(provider)); }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "real-neoeco-protocol-provider";
                case "isBusy" -> false;
                case "getAvailablePatterns" -> List.of(pattern);
                case "pushPattern" -> { ordinary++; yield false; }
                case "eco$getAvailableParallelSlots" -> (int) capacity;
                case "eco$pushPatternBatch" -> {
                    pushes++;
                    totalInput = ((KeyCounter[]) args[1])[0].get(raw);
                    ((KeyCounter[]) args[1])[0].reset();
                    offered = (long) args[2];
                    contextJob = (UUID) args[3];
                    yield true;
                }
                case "eco$prepareFastPath" -> {
                    var context = args[0];
                    contextJob = (UUID) call(context.getClass().getMethod("craftingJobId"), context);
                    var stateful = loader.loadClass(ECO + "crafting.execution.fastpath.ECOStatefulBatchCalculator");
                    yield preparation.getConstructor(long.class, stateful, boolean.class, Predicate.class)
                            .newInstance(capacity, null, false, (Predicate<Object>) batch -> {
                                try {
                                    offered = (long) call(batch.getClass().getMethod("craftCount"), batch);
                                    totalInput = ((GenericStack) ((List<?>) call(batch.getClass().getMethod("inputTotal"), batch)).get(0)).amount();
                                    totalOutput = ((GenericStack) ((List<?>) call(batch.getClass().getMethod("outputTotal"), batch)).get(0)).amount();
                                    pushes++;
                                    if (submitFailure != null) throw submitFailure;
                                    return true;
                                } catch (RuntimeException e) { throw e; }
                                catch (Exception e) { throw new AssertionError(e); }
                            });
                }
                case "eco$prepareExactFastPath" -> {
                    exactRequest = (BigInteger) args[1];
                    contextJob = (UUID) call(args[0].getClass().getMethod("craftingJobId"), args[0]);
                    yield exactPreparation.getConstructor(BigInteger.class, java.util.function.BooleanSupplier.class)
                            .newInstance(exactRequest, (java.util.function.BooleanSupplier) () -> { pushes++; return true; });
                }
                default -> throw new AssertionError(method);
            };
        }
    }
}
