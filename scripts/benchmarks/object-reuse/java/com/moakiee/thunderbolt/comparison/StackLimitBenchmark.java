package com.moakiee.thunderbolt.comparison;

import appeng.api.stacks.AEItemKey;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.function.IntFunction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Same public factory workload before/after removing stack-limit rechecks; no cache internals. */
final class StackLimitBenchmark {
    private static volatile AEItemKey sink;

    static void run(ItemStack custom) {
        var ordinary = new ItemStack(Items.IRON_INGOT);
        var bulk = custom.copyWithCount(64);
        var named = bulk.copy();
        var name = Component.literal("stack-limit-benchmark");
        named.set(DataComponents.CUSTOM_NAME, name);
        scenario("ordinary_plain", i -> AEItemKey.of(ordinary));
        scenario("custom_plain", i -> AEItemKey.of(custom));
        scenario("custom_plain_64", i -> AEItemKey.of(bulk));
        scenario("custom_component_hit_64", i -> AEItemKey.of(named));
        scenario("custom_equal_component_new_stack", i -> {
            var fresh = custom.copyWithCount(64);
            fresh.set(DataComponents.CUSTOM_NAME, name);
            return AEItemKey.of(fresh);
        });
    }

    private static void scenario(String name, IntFunction<AEItemKey> factory) {
        int calls = 200_000;
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        double[] times = new double[9], bytes = new double[9];
        // First writable component snapshots may bypass reuse; warm before retaining a representative.
        for (int i = 0; i < 1_000; i++) sink = factory.apply(i);
        var expected = factory.apply(0);
        for (int round = -8; round < 9; round++) {
            long allocated = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            for (int i = 0; i < calls; i++) sink = factory.apply(i);
            long elapsed = System.nanoTime() - start, used = bean.getThreadAllocatedBytes(thread) - allocated;
            if (!sink.equals(expected) || sink.getMaxStackSize() != 64)
                throw new AssertionError("wrong key for " + name);
            if (round >= 0) { times[round] = elapsed / (double) calls; bytes[round] = used / (double) calls; }
        }
        Arrays.sort(times);
        Arrays.sort(bytes);
        System.out.printf(java.util.Locale.ROOT,
                "STACK_LIMIT_BENCH mode=%s scenario=%s ns=%.3f min=%.3f max=%.3f bytes=%.2f%n",
                System.getProperty("comparison.mode"), name, times[4], times[0], times[8], bytes[4]);
    }
}
