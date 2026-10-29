package com.moakiee.thunderbolt.core;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathCompat;
import com.moakiee.thunderbolt.core.storage.cell.ByteTracker;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorage;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
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

/** Opt-in storage and facade microbenchmarks. Copy this same harness to each comparison checkout. */
public final class IntegrationHotPathBenchmark {
    private static final int WARMUP = 20, SAMPLES = 20;
    private static volatile Object sink;
    private static volatile long amountSink;
    private static long lastInvocations;

    public static void main(String[] args) throws Exception {
        MinecraftTestBootstrap.ensureInitialized();
        var rows = new ArrayList<String>();
        var type = AEKeyType.items();
        var tracker = new ByteTracker(() -> 1);
        tracker.configure(8, 63, 1_000_000, 0);
        tracker.onInsert(type, 64, true);
        rows.add(measure("byte-existing-key", 100_000, () -> {
            tracker.onInsert(type, 17, false);
            amountSink = tracker.getUsedBytes();
            tracker.onExtract(type, 17, false);
            if (tracker.getUsedBytes() != 8 + (64 + type.getAmountPerByte() - 1) / type.getAmountPerByte())
                throw new AssertionError("byte conservation");
        }));
        var newKeys = new ByteTracker(() -> 0);
        newKeys.configure(8, 63, 1_000_000, 0);
        rows.add(measure("byte-new-key", 100_000, () -> {
            newKeys.onInsert(type, 17, true);
            amountSink = newKeys.getUsedBytes();
            newKeys.onExtract(type, 17, true);
            if (newKeys.getUsedBytes() != 0) throw new AssertionError("key charge conservation");
        }));
        long expectedCapacity = (1_000_000 - tracker.getUsedBytes()) * type.getAmountPerByte();
        rows.add(measure("byte-capacity-existing", 100_000, () -> {
            long capacity = tracker.computeMaxInsertable(type, false);
            if (capacity != expectedCapacity) throw new AssertionError("capacity changed");
            amountSink = capacity;
        }));
        var key = AEItemKey.of(Items.IRON_INGOT);
        var store = new IndexedStorage();
        store.insert(key, 1000, Actionable.MODULATE);
        rows.add(measure("indexed-long-transfer", 100_000, () -> {
            if (store.insert(key, 17, Actionable.MODULATE) != 17) throw new AssertionError("long insertion");
            amountSink = store.getAmount(key);
            if (store.extract(key, 17, Actionable.MODULATE) != 17
                    || store.getAmount(key) != 1000) throw new AssertionError("long conservation");
        }));
        store.enableArbitraryPrecision();
        var quantity = BigInteger.valueOf(17);
        rows.add(measure("indexed-small-exact-transfer", 100_000, () -> {
            if (!store.insertExact(key, quantity, Actionable.MODULATE).equals(quantity)) throw new AssertionError("small exact insertion");
            amountSink = store.getAmount(key);
            if (!store.extractExact(key, quantity, Actionable.MODULATE).equals(quantity)
                    || store.getAmount(key) != 1000) throw new AssertionError("small exact conservation");
        }));
        var wide = BigInteger.ONE.shiftLeft(130);
        store.insertExact(key, wide, Actionable.MODULATE);
        var expectedWide = wide.add(BigInteger.valueOf(1000));
        rows.add(measure("indexed-wide-exact-transfer", 5000, () -> {
            if (!store.insertExact(key, quantity, Actionable.MODULATE).equals(quantity)) throw new AssertionError("wide insertion");
            sink = store.getAmountExact(key);
            if (!store.extractExact(key, quantity, Actionable.MODULATE).equals(quantity)
                    || !store.getAmountExact(key).equals(expectedWide)) throw new AssertionError("wide conservation");
        }));

        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Path sources = args.length > 1 ? Path.of(args[1])
                : Path.of(IntegrationHotPathBenchmark.class.getResource("/neoeco-facade-contract").toURI());
        Path contracts = Files.createTempDirectory(output.getParent(), "benchmark-contracts-");
        var compilerArgs = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "--release", "17",
                "-classpath", System.getProperty("java.class.path"), "-d", contracts.toString()));
        try (var files = Files.walk(sources)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> compilerArgs.add(p.toString()));
        }
        var diagnostics = new ByteArrayOutputStream();
        if (ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics,
                compilerArgs.toArray(String[]::new)) != 0)
            throw new AssertionError(diagnostics.toString(StandardCharsets.UTF_8));
        try (var loader = new URLClassLoader(new URL[]{contracts.toUri().toURL(),
                NeoEcoFastPathCompat.class.getProtectionDomain().getCodeSource().getLocation()},
                IntegrationHotPathBenchmark.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("com.moakiee.thunderbolt.compat.neoeco.")
                            || name.startsWith("cn.dancingsnow.neoecoae.")) {
                        var loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        }) {
            var provider = (ICraftingProvider) loader.loadClass(
                    "cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade$Provider").getConstructor().newInstance();
            var resolver = (BatchProviderResolver) loader.loadClass(
                    "com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathBatchAdapter").getConstructor().newInstance();
            var endpoint = Objects.requireNonNull(resolver.resolve(provider));
            var input = new KeyCounter();
            input.add(key, 2);
            var inputs = new KeyCounter[]{input};
            var pattern = pattern(key);
            var job = new BatchJobView() {
                private final UUID id = new UUID(0, 1);
                public Level level() { return null; }
                public UUID craftingId() { return id; }
                public Iterator<BatchTaskHandle> taskIterator() { return Collections.emptyIterator(); }
                public ListCraftingInventory waitingFor() { throw new UnsupportedOperationException(); }
                public void addContainerMaxItems(long amount, AEKeyType type) { throw new UnsupportedOperationException(); }
            };
            for (long offered : new long[]{8, 3}) {
                long before = provider.getClass().getField("accepted").getLong(provider);
                rows.add(measure("neoeco-allocated-offer-" + offered, 3000, () -> {
                    var prepared = endpoint.prepareBatch(pattern, inputs, 8, job);
                    if (prepared == null || prepared.capacity() != 8 || prepared.push(offered) != 0
                            || input.get(key) != 2) throw new AssertionError("allocated dispatch conservation");
                    sink = prepared;
                }));
                long accepted = provider.getClass().getField("accepted").getLong(provider) - before;
                if (accepted != offered * lastInvocations) throw new AssertionError("accepted count");
            }
        }
        String json = "{\"java\":\"" + System.getProperty("java.version") + "\",\"samples\":["
                + String.join(",", rows) + "]}";
        Files.writeString(output, json);
        System.out.println(json);
    }

    private static IPatternDetails pattern(AEItemKey key) {
        return new IPatternDetails() {
            public AEItemKey getDefinition() { return key; }
            public IInput[] getInputs() { return new IInput[0]; }
            public GenericStack[] getOutputs() { return new GenericStack[]{new GenericStack(key, 1)}; }
            public boolean supportsPushInputsToExternalInventory() { return true; }
        };
    }

    private static String measure(String name, int iterations, Runnable action) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation metrics unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        long warmupStarted = System.nanoTime();
        int warmupBatches = 0;
        do {
            for (int i = 0; i < iterations; i++) action.run();
            warmupBatches++;
        } while (warmupBatches < WARMUP || System.nanoTime() - warmupStarted < 1_000_000_000L);
        lastInvocations = ((long) warmupBatches + SAMPLES) * iterations;
        double[] nanos = new double[SAMPLES], bytes = new double[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            long allocated = bean.getThreadAllocatedBytes(thread), started = System.nanoTime();
            for (int i = 0; i < iterations; i++) action.run();
            nanos[sample] = (System.nanoTime() - started) / (double) iterations;
            bytes[sample] = (bean.getThreadAllocatedBytes(thread) - allocated) / (double) iterations;
        }
        Arrays.sort(nanos); Arrays.sort(bytes);
        return String.format(Locale.ROOT,
                "{\"scenario\":\"%s\",\"iterations\":%d,\"warmup_iterations\":%d,\"p50_ns\":%.1f,\"p95_ns\":%.1f,\"bytes_per_op\":%.1f}",
                name, iterations, (long) warmupBatches * iterations, nanos[9], nanos[18], bytes[9]);
    }
}
