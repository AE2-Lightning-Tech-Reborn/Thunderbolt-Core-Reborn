package com.moakiee.thunderbolt.comparison;

import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;

/** Includes first-seen IDs that stay live, so GC of discarded values cannot hide indexing costs. */
final class ResourceLocationColdBenchmark {
    private static volatile Object sink;
    private static final String[] NAMES = {"pair_discarded", "pair_retained", "parse_discarded", "parse_retained"};

    static void run() throws Exception {
        String mode = System.getProperty("comparison.mode");
        if (mode.startsWith("OURS")) Class.forName("com.moakiee.thunderbolt.core.keys.ResourceConstructionCache")
                .getMethod("configure", boolean.class, boolean.class).invoke(null, true, true);
        var retained = new ArrayList<ResourceLocation[]>(30);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        int count = 20_000;
        double[][] times = new double[4][9], bytes = new double[4][9];
        for (int round = -6; round < 9; round++) for (int offset = 0; offset < 4; offset++) {
            int scenario = Math.floorMod(round + offset, 4);
            String[] inputs = new String[count];
            ResourceLocation[] outputs = (scenario & 1) != 0 ? new ResourceLocation[count] : null;
            for (int i = 0; i < count; i++) inputs[i] = (scenario >= 2 ? "rl_cold:" : "")
                    + "s" + scenario + "_r" + (round + 6) + "_id" + i;
            long allocated = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            switch (scenario) {
                case 0 -> { for (int i = 0; i < count; i++) sink = ResourceLocation.fromNamespaceAndPath("rl_cold", inputs[i]); }
                case 1 -> { for (int i = 0; i < count; i++) outputs[i] = ResourceLocation.fromNamespaceAndPath("rl_cold", inputs[i]); }
                case 2 -> { for (int i = 0; i < count; i++) sink = ResourceLocation.parse(inputs[i]); }
                case 3 -> { for (int i = 0; i < count; i++) outputs[i] = ResourceLocation.parse(inputs[i]); }
                default -> throw new AssertionError(scenario);
            }
            long elapsed = System.nanoTime() - start, used = bean.getThreadAllocatedBytes(thread) - allocated;
            if (outputs != null) {
                if (!outputs[count - 1].getNamespace().equals("rl_cold")) throw new AssertionError("wrong namespace");
                retained.add(outputs);
            }
            if (round >= 0) { times[scenario][round] = elapsed / (double) count; bytes[scenario][round] = used / (double) count; }
        }
        Reference.reachabilityFence(retained);
        for (int scenario = 0; scenario < 4; scenario++) {
            Arrays.sort(times[scenario]); Arrays.sort(bytes[scenario]);
            System.out.printf(Locale.ROOT, "RL_COLD mode=%s scenario=%s ns=%.3f min=%.3f max=%.3f bytes=%.2f%n",
                    mode, NAMES[scenario], times[scenario][4], times[scenario][0], times[scenario][8], bytes[scenario][4]);
        }
    }
}
