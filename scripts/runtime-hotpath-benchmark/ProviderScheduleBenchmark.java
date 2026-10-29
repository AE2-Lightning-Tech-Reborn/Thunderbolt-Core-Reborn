package com.moakiee.thunderbolt.core.crafting.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Measures real candidate queues and schedules; no Minecraft tick-rate claim is implied. */
public final class ProviderScheduleBenchmark {
    private static final long WARMUP_NS = Long.getLong("benchmark.warmupMillis", 1_000L) * 1_000_000L;
    private static final long SAMPLE_NS = Long.getLong("benchmark.sampleMillis", 100L) * 1_000_000L;
    private static volatile long sink;

    @FunctionalInterface
    private interface Operation { long run(); }

    public static void main(String[] args) {
        for (int size : new int[]{0, 1, 16, 256, 1_024}) scan(size, false);
        scan(256, true);
        mostlyBlocked();
        blockAndRestore(16);
        blockAndRestore(256);
        for (int size : new int[]{0, 1, 16, 256, 1_024}) schedule(size, true);
        schedule(16, false);
        schedule(256, false);
    }

    private static void scan(int size, boolean preferred) {
        var providers = providers(size);
        var queue = new IdentityCandidateQueue<>(providers);
        if (preferred) queue.markSuccess(providers.getLast());
        measure("queue-" + (preferred ? "preferred-" : "scan-") + size,
                batch(size), total(size), () -> sum(queue));
    }

    private static void mostlyBlocked() {
        var providers = providers(1_024);
        var queue = new IdentityCandidateQueue<>(providers);
        for (int i = 0; i < 1_023; i++) queue.block(providers.get(i));
        queue.markSuccess(providers.getLast());
        if (queue.activeCount() != 1 || queue.blockedCount() != 1_023) throw new AssertionError();
        measure("queue-1024-only-one-active", 256, 1_024, () -> sum(queue));
    }

    private static void blockAndRestore(int size) {
        var providers = providers(size);
        var queue = new IdentityCandidateQueue<>(providers);
        int failed = size / 2;
        measure("queue-block-restore-" + size, batch(size), total(size) - total(failed), () -> {
            long result = 0;
            for (var candidate : queue) {
                var provider = (Provider) candidate;
                if (provider.id <= failed) queue.block(provider);
                else result += provider.id;
            }
            if (queue.activeCount() != size - failed || queue.blockedCount() != failed) {
                throw new AssertionError("Removal counts changed");
            }
            queue.restoreBlocked();
            if (queue.activeCount() != size || queue.blockedCount() != 0) throw new AssertionError();
            return result;
        });
    }

    private static void schedule(int size, boolean rebuild) {
        var providers = providers(size);
        var schedule = new TickProviderDispatchSchedule();
        var service = new Object();
        var pattern = (IPatternDetails) Proxy.newProxyInstance(
                IPatternDetails.class.getClassLoader(), new Class<?>[]{IPatternDetails.class},
                (proxy, method, arguments) -> { throw new AssertionError(method); });
        var tick = new long[1];
        var enumerations = new long[1];
        Supplier<Iterable<ICraftingProvider>> source = () -> {
            enumerations[0]++;
            return providers;
        };
        measure("schedule-" + (rebuild ? "rebuild-" : "stable-ticks-") + size,
                batch(size), total(size), () -> {
                    long currentTick = ++tick[0];
                    schedule.beginTick(currentTick);
                    var candidates = schedule.candidates(service, rebuild ? currentTick : 0, pattern, source);
                    if (enumerations[0] != (rebuild ? currentTick : 1)) {
                        throw new AssertionError("Provider source invalidation changed");
                    }
                    return sum(candidates);
                });
    }

    private static List<ICraftingProvider> providers(int count) {
        var providers = new ArrayList<ICraftingProvider>();
        for (int i = 1; i <= count; i++) providers.add(new Provider(i));
        return providers;
    }

    private static long sum(Iterable<? extends ICraftingProvider> candidates) {
        long sum = 0;
        for (var provider : candidates) sum += ((Provider) provider).id;
        return sum;
    }

    private static long total(int count) { return (long) count * (count + 1) / 2; }
    private static int batch(int count) { return Math.max(1, 256 / Math.max(1, count)); }

    private static void measure(String name, int batch, long expected, Operation operation) {
        long warmupEnd = System.nanoTime() + WARMUP_NS;
        while (System.nanoTime() < warmupEnd) runBatch(batch, expected, operation);
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int sample = 0; sample < 7; sample++) {
            long beforeBytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            long operations = 0;
            do {
                runBatch(batch, expected, operation);
                operations += batch;
            } while (System.nanoTime() - start < SAMPLE_NS);
            long elapsed = System.nanoTime() - start;
            long allocated = bean.getThreadAllocatedBytes(thread) - beforeBytes;
            System.out.printf(Locale.ROOT,
                    "{\"scenario\":\"%s\",\"sample\":%d,\"operations\":%d,\"ns_per_op\":%.3f,\"bytes_per_op\":%.3f,\"signature\":\"checksum:%d\"}%n",
                    name, sample, operations, (double) elapsed / operations, (double) allocated / operations, expected);
        }
    }

    private static void runBatch(int batch, long expected, Operation operation) {
        long value = 0;
        for (int i = 0; i < batch; i++) value += operation.run();
        if (value != expected * batch) throw new AssertionError("Provider traversal changed: " + value);
        sink = value;
    }

    private static final class Provider implements ICraftingProvider {
        final int id;
        Provider(int id) { this.id = id; }
        public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        public boolean isBusy() { return false; }
        public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) { return false; }
    }
}
