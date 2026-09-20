package com.moakiee.thunderbolt.comparison;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;

/** Same public factory calls on BASE, Lean and TB. Input generation is outside timed cold inserts. */
final class ResourceLocationBenchmark {
    private static volatile Object sink;
    private static volatile int number;
    private static final String INPUT = "thunderbolt:object_reuse_benchmark";
    private static final String PATH = "object_reuse_benchmark";
    private static final String[] NAMES = {"parse_shared", "pair_shared", "parse_equal_string", "parse_8192_live",
            "unique_pairs", "hash_code", "hash_map"};

    static void run() throws Exception {
        String mode = System.getProperty("comparison.mode");
        if (mode.startsWith("OURS")) Class.forName("com.moakiee.thunderbolt.core.keys.ResourceConstructionCache")
                .getMethod("configure", boolean.class, boolean.class).invoke(null, !mode.equals("OURS_OFF"), true);
        var retained = ResourceLocation.parse(INPUT);
        String[] inputs = new String[8192];
        ResourceLocation[] ids = new ResourceLocation[8192];
        var map = new HashMap<ResourceLocation, Integer>();
        for (int i = 0; i < inputs.length; i++) {
            inputs[i] = "rl_bench:retained_" + i;
            ids[i] = ResourceLocation.parse(inputs[i]); map.put(ids[i], i);
        }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        double[][] times = new double[NAMES.length][9], bytes = new double[NAMES.length][9];
        for (int round = -6; round < 9; round++) for (int offset = 0; offset < NAMES.length; offset++) {
            int scenario = Math.floorMod(round + offset, NAMES.length);
            int count = scenario == 4 ? 20_000 : 200_000;
            String[] cold = null;
            if (scenario == 4) {
                cold = new String[count];
                for (int i = 0; i < count; i++) cold[i] = "cold_" + (round + 6) + "_" + i;
            }
            long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            loop(scenario, count, inputs, ids, map, cold);
            long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(thread) - before;
            if (round >= 0) { times[scenario][round] = elapsed / (double) count; bytes[scenario][round] = allocated / (double) count; }
        }
        java.lang.ref.Reference.reachabilityFence(retained);
        java.lang.ref.Reference.reachabilityFence(ids);
        for (int i = 0; i < NAMES.length; i++) {
            Arrays.sort(times[i]); Arrays.sort(bytes[i]);
            System.out.printf(Locale.ROOT, "RL_FACTORY mode=%s scenario=%s ns=%.3f min=%.3f max=%.3f bytes=%.2f%n",
                    mode, NAMES[i], times[i][4], times[i][0], times[i][8], bytes[i][4]);
        }
    }

    private static void loop(int scenario, int count, String[] inputs, ResourceLocation[] ids,
                             HashMap<ResourceLocation, Integer> map, String[] cold) {
        switch (scenario) {
            case 0 -> { for (int i = 0; i < count; i++) sink = ResourceLocation.parse(INPUT); }
            case 1 -> { for (int i = 0; i < count; i++) sink = ResourceLocation.fromNamespaceAndPath("thunderbolt", PATH); }
            case 2 -> { for (int i = 0; i < count; i++) sink = ResourceLocation.parse(new String(INPUT)); }
            case 3 -> { for (int i = 0; i < count; i++) sink = ResourceLocation.parse(inputs[i & 8191]); }
            case 4 -> { for (int i = 0; i < count; i++) sink = ResourceLocation.fromNamespaceAndPath("rl_bench", cold[i]); }
            case 5 -> { int total = 0; for (int i = 0; i < count; i++) total += ids[i & 8191].hashCode(); number = total; }
            case 6 -> { int total = 0; for (int i = 0; i < count; i++) total += map.get(ids[i & 8191]); number = total; }
            default -> throw new AssertionError(scenario);
        }
    }
}
