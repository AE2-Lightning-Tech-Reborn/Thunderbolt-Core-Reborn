import appeng.api.crafting.IPatternDetails;
import com.moakiee.thunderbolt.core.crafting.cpu.DynamicCraftingCpuClusterIndex;
import com.moakiee.thunderbolt.core.crafting.pattern.IBatchExecutionPattern;
import com.moakiee.thunderbolt.core.crafting.pattern.IProviderLookupPattern;
import com.moakiee.thunderbolt.core.crafting.support.CraftingPatternDelegates;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Locale;

/** Isolated hot-path measurements; these numbers do not measure Minecraft TPS. */
public final class RuntimeHotpathBenchmark {
    private static final long WARMUP_NS = Long.getLong("benchmark.warmupMillis", 1_000L) * 1_000_000L;
    private static final long SAMPLE_NS = Long.getLong("benchmark.sampleMillis", 100L) * 1_000_000L;
    private static volatile long sink;
    private static long added;

    @FunctionalInterface
    private interface Operation { int run(); }

    public static void main(String[] args) {
        for (int depth : new int[]{-1, 0, 1, 2, 8}) {
            var patterns = new IPatternDetails[32];
            // Exercise both wrapped and ordinary receivers before measuring homogeneous cases.
            for (int i = 0; i < patterns.length; i++) patterns[i] = chain(depth < 0 ? i % 3 : depth);
            var cursor = new int[1];
            measure("delegates-" + (depth < 0 ? "mixed" : depth), 1_024, () -> {
                var pattern = patterns[cursor[0]++ & 31];
                return 31 * System.identityHashCode(CraftingPatternDelegates.forBatchExecution(pattern))
                        + System.identityHashCode(CraftingPatternDelegates.forProviderLookup(pattern));
            });
        }
        for (int count : new int[]{0, 1, 64, 1_024, 4_096}) {
            clusterScenario(count, false);
        }
        clusterScenario(1_024, true);
    }

    private static void clusterScenario(int count, boolean changing) {
        var index = new DynamicCraftingCpuClusterIndex<Provider, Object>();
        var providers = new ArrayList<Provider>();
        for (int i = 0; i < count; i++) {
            var provider = new Provider();
            providers.add(provider);
            index.addProvider(provider);
        }
        index.refresh(provider -> provider.cluster, cluster -> added++);
        var cursor = new int[1];
        measure("clusters-" + count + (changing ? "-changing" : "-stable"),
                Math.max(1, 1_024 / Math.max(1, count)), () -> {
                    if (changing) {
                        var provider = providers.get(cursor[0]++ % count);
                        provider.cluster = provider.cluster == provider.first ? provider.second : provider.first;
                    }
                    boolean changed = index.refresh(provider -> provider.cluster, cluster -> added++);
                    if (changed != changing || index.clusters().size() != count) {
                        throw new AssertionError("Cluster refresh changed behavior");
                    }
                    return index.clusters().size() + (changed ? 1 : 0);
                });
    }

    private static IPatternDetails chain(int depth) {
        var current = (IPatternDetails) Proxy.newProxyInstance(
                RuntimeHotpathBenchmark.class.getClassLoader(),
                new Class<?>[]{IPatternDetails.class},
                (proxy, method, args) -> { throw new AssertionError(method); });
        for (int i = 0; i < depth; i++) {
            var next = current;
            current = (IPatternDetails) Proxy.newProxyInstance(
                    RuntimeHotpathBenchmark.class.getClassLoader(),
                    new Class<?>[]{IPatternDetails.class, IBatchExecutionPattern.class, IProviderLookupPattern.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "batchExecutionPattern", "providerLookupPattern" -> next;
                        default -> throw new AssertionError(method);
                    });
        }
        return current;
    }

    private static void measure(String name, int batch, Operation operation) {
        long warmupEnd = System.nanoTime() + WARMUP_NS;
        while (System.nanoTime() < warmupEnd) runBatch(batch, operation);
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int sample = 0; sample < 7; sample++) {
            long beforeBytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            long operations = 0;
            do {
                runBatch(batch, operation);
                operations += batch;
            } while (System.nanoTime() - start < SAMPLE_NS);
            long elapsed = System.nanoTime() - start;
            long allocated = bean.getThreadAllocatedBytes(thread) - beforeBytes;
            System.out.printf(Locale.ROOT,
                    "{\"scenario\":\"%s\",\"sample\":%d,\"operations\":%d,\"ns_per_op\":%.3f,\"bytes_per_op\":%.3f}%n",
                    name, sample, operations, (double) elapsed / operations, (double) allocated / operations);
        }
    }

    private static void runBatch(int batch, Operation operation) {
        long value = 0;
        for (int i = 0; i < batch; i++) value += operation.run();
        sink = value + added;
    }

    private static final class Provider {
        final Object first = new Object();
        final Object second = new Object();
        Object cluster = first;
    }
}
