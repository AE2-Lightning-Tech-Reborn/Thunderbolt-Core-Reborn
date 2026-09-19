package com.moakiee.thunderbolt.comparison;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.function.IntFunction;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Factory microbenchmark, not a planner/TPS benchmark. Every result escapes to the same sink. */
final class ComparisonBenchmark {
    private static volatile Object sink;
    private record Sample(double nsPerCall, double bytesPerCall) {}

    static void run() {
        var single = new ItemStack(Items.IRON_INGOT);
        var bulk = new ItemStack(Items.IRON_INGOT, 64);
        var named = single.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
        var fluid = new FluidStack(Fluids.WATER, 1000);
        var nbt = new CompoundTag();
        var list = new ListTag();
        for (int i = 0; i < 64; i++) {
            nbt.putString("key_" + i, "value_" + i);
            var child = new CompoundTag(); child.putInt("value", i); list.add(child);
        }
        var complex = single.copy(); complex.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
        AEItemKey.of(complex); // Admit a repeated snapshot where the implementation supports it.
        var canonicalKey = AEItemKey.of(complex);
        var componentLookup = java.util.Map.of(canonicalKey, "found");
        var independentlyEqual = new ItemStack[256];
        for (int i = 0; i < independentlyEqual.length; i++) {
            independentlyEqual[i] = single.copy();
            independentlyEqual[i].set(DataComponents.CUSTOM_DATA, CustomData.of(nbt.copy()));
        }
        int matched = 0;
        for (var input : independentlyEqual) if ("found".equals(componentLookup.get(AEItemKey.of(input)))) matched++;
        System.out.printf("COMPARE_PROBE mode=%s name=equal_component_lookup_hits result=%d/256%n",
                System.getProperty("comparison.mode"), matched);
        var componentVariants = new ItemStack[256];
        for (int i = 0; i < componentVariants.length; i++) {
            componentVariants[i] = single.copy();
            componentVariants[i].set(DataComponents.CUSTOM_NAME, Component.literal("variant_" + i));
        }
        var id = ResourceLocation.parse("thunderbolt:object_reuse_benchmark");
        var lookup = java.util.Map.of(id, "present");
        var distinct = BuiltInRegistries.ITEM.stream().filter(i -> i != Items.AIR)
                .map(ItemStack::new).toArray(ItemStack[]::new);
        {
            scenario("item_count_1", i -> AEItemKey.of(single));
            scenario("item_count_64", i -> AEItemKey.of(bulk));
            scenario("item_components", i -> AEItemKey.of(named));
            scenario("item_deep_components", i -> AEItemKey.of(complex));
            scenario("item_256_variants", i -> AEItemKey.of(componentVariants[i % componentVariants.length]));
            scenario("equal_component_storage_lookup", i -> componentLookup.get(
                    AEItemKey.of(independentlyEqual[i % independentlyEqual.length])));
            scenario("item_fresh_components", i -> {
                var fresh = single.copy();
                fresh.set(DataComponents.CUSTOM_NAME, Component.literal("fresh_" + i));
                return AEItemKey.of(fresh);
            });
            scenario("fluid_plain", i -> AEFluidKey.of(fluid));
            scenario("item_many_types_" + distinct.length, i -> AEItemKey.of(distinct[i % distinct.length]));
            scenario("resource_location", i -> ResourceLocation.parse("thunderbolt:object_reuse_benchmark"));
            scenario("resource_unique", i -> ResourceLocation.fromNamespaceAndPath("thunderbolt", "unique_" + i));
            scenario("resource_lookup", i -> lookup.get(id));
            scenario("tag_key", i -> TagKey.create(Registries.ITEM, id));
            scenario("nbt_compound_64", i -> nbt.copy(), 5_000);
            scenario("nbt_list_64", i -> list.copy(), 5_000);
        }
    }

    private static void scenario(String name, IntFunction<?> factory) { scenario(name, factory, 200_000); }

    private static void scenario(String name, IntFunction<?> factory, int calls) {
        for (int i = 0; i < 6; i++) sample(factory, calls);
        double[] times = new double[7], bytes = new double[7];
        for (int i = 0; i < 7; i++) {
            Sample s = sample(factory, calls); times[i] = s.nsPerCall; bytes[i] = s.bytesPerCall;
        }
        System.out.printf(java.util.Locale.ROOT,
                "COMPARE_BENCH mode=%s name=%s ns=%.2f bytes=%.2f calls=%d rounds=7%n",
                System.getProperty("comparison.mode"), name, median(times), median(bytes), calls);
    }

    private static Sample sample(IntFunction<?> factory, int calls) {
        for (int i = 0; i < Math.min(calls, 10_000); i++) sink = factory.apply(i);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long bytes = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        for (int i = 0; i < calls; i++) sink = factory.apply(i);
        long elapsed = System.nanoTime() - start;
        return new Sample((double) elapsed / calls,
                (double) (bean.getThreadAllocatedBytes(thread) - bytes) / calls);
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }
}
