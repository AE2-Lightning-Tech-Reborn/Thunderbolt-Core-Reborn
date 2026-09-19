package com.moakiee.thunderbolt.core.keys;

import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Standalone Java 21 probe of L2 lookup only; not a complete interner or write/GC benchmark. */
public final class WeakMapLookupBenchmark {
    private record Key(int id) {}
    private record Value(Key key) {}
    private record Sample(double ns, double bytes) {}
    private static volatile long sink;

    private interface Backend {
        void put(Key key, Value value);
        Value get(Key key);
        String name();
    }

    private static final class ContentKey extends WeakReference<Key> {
        final int hash;
        ContentKey(Key key) { super(key); hash = key.hashCode(); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ContentKey that) || hash != that.hash) return false;
            var a = get(); var b = that.get();
            return a != null && b != null && a.equals(b);
        }
    }

    private static final class Jdk implements Backend {
        final ConcurrentHashMap<ContentKey, WeakReference<Value>> map = new ConcurrentHashMap<>();
        public void put(Key key, Value value) { map.put(new ContentKey(key), new WeakReference<>(value)); }
        public Value get(Key key) { var value = map.get(new ContentKey(key)); return value != null ? value.get() : null; }
        public String name() { return "CHM_weak_lookup_wrapper"; }
    }

    private static final class Open implements Backend {
        final WeakOpenHashMap<Key, Value> map = new WeakOpenHashMap<>();
        public void put(Key key, Value value) { map.putIfAbsent(key, value); }
        public Value get(Key key) { return map.get(key); }
        public String name() { return "weak_open_addressing"; }
    }

    private static long loop(Backend map, Key[] keys, int count, int offset) {
        long sum = 0;
        for (int i = 0; i < count; i++) sum += map.get(keys[(i * 73 + offset) & (keys.length - 1)]).key.id;
        return sum;
    }

    private static Sample sample(Backend map, Key[] keys, int threads) throws Exception {
        int calls = 400_000;
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        var allocated = new long[threads];
        try (var workers = Executors.newFixedThreadPool(threads)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int t = 0; t < threads; t++) {
                int index = t;
                tasks.add(workers.submit(() -> {
                    sink = loop(map, keys, 50_000, index * 37);
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                    long thread = Thread.currentThread().threadId();
                    long before = bean.getThreadAllocatedBytes(thread);
                    long result = loop(map, keys, calls, index * 37);
                    allocated[index] = bean.getThreadAllocatedBytes(thread) - before;
                    return result;
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) throw new AssertionError("warmup timed out");
            long begin = System.nanoTime();
            start.countDown();
            for (var task : tasks) sink = task.get(30, TimeUnit.SECONDS);
            long elapsed = System.nanoTime() - begin;
            return new Sample(elapsed / (double) (calls * threads),
                    Arrays.stream(allocated).sum() / (double) (calls * threads));
        }
    }

    public static void main(String[] args) throws Exception {
        var held = new Value[4096];
        var queries = new Key[held.length];
        Backend[] backends = {new Jdk(), new Open()};
        for (int i = 0; i < held.length; i++) {
            held[i] = new Value(new Key(i)); queries[i] = new Key(i);
            for (var backend : backends) backend.put(held[i].key, held[i]);
        }
        for (int threads : new int[] {1, 4}) {
            double[][] time = new double[2][5], bytes = new double[2][5];
            for (int round = -3; round < 5; round++) for (int j = 0; j < backends.length; j++) {
                int index = Math.floorMod(round + j, backends.length);
                var sample = sample(backends[index], queries, threads);
                if (round >= 0) { time[index][round] = sample.ns; bytes[index][round] = sample.bytes; }
            }
            for (int i = 0; i < backends.length; i++) {
                Arrays.sort(time[i]); Arrays.sort(bytes[i]);
                System.out.printf(Locale.ROOT, "WEAK_LOOKUP name=%s threads=%d aggregate_ns=%.2f bytes=%.2f%n",
                        backends[i].name(), threads, time[i][2], bytes[i][2]);
            }
        }
        Reference.reachabilityFence(held);
        Reference.reachabilityFence(queries);
    }
}
