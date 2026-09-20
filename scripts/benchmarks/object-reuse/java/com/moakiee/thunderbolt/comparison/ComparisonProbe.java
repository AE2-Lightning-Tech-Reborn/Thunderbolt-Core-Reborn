package com.moakiee.thunderbolt.comparison;

import appeng.api.stacks.AEItemKey;
import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Reports semantic observations; GameTest success only means this audit completed. */
@GameTestHolder("lean_comparison")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "thunderbolt", bus = EventBusSubscriber.Bus.MOD)
public final class ComparisonProbe {
    private static final AtomicInteger limit = new AtomicInteger(64);
    private static volatile CyclicBarrier barrier;
    private static Item dynamic, racing;

    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(Registries.ITEM, helper -> {
            dynamic = new Item(new Item.Properties()) {
                @Override public int getMaxStackSize(ItemStack stack) { return limit.get(); }
            };
            racing = new Item(new Item.Properties()) {
                @Override public int getMaxStackSize(ItemStack stack) {
                    var b = barrier;
                    if (b != null) try { b.await(10, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    return 64;
                }
            };
            helper.register(ResourceLocation.parse("thunderbolt:comparison_dynamic"), dynamic);
            helper.register(ResourceLocation.parse("thunderbolt:comparison_racing"), racing);
        });
    }

    private static void row(String name, Object result) {
        System.out.println("COMPARE_PROBE mode=" + System.getProperty("comparison.mode")
                + " name=" + name + " result=" + result);
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void compare(GameTestHelper h) throws Exception {
        if (Boolean.getBoolean("comparison.stackLimitOnly")) {
            StackLimitBenchmark.run(new ItemStack(dynamic));
            h.succeed();
            return;
        }
        if (Boolean.getBoolean("comparison.resourceColdOnly")) {
            ResourceLocationColdBenchmark.run();
            h.succeed();
            return;
        }
        if (Boolean.getBoolean("comparison.resourceOnly")) {
            ResourceLocationBenchmark.run();
            h.succeed();
            return;
        }
        if (Boolean.getBoolean("comparison.directFactoryOnly")) {
            DirectFactoryBenchmark.run();
            h.succeed();
            return;
        }
        try { row("air_itemlike_returns_null", AEItemKey.of(Items.AIR) == null); }
        catch (RuntimeException e) { row("air_itemlike_returns_null", e.getClass().getSimpleName() + ":" + e.getMessage()); }
        var raw = new ItemStack(Items.POTION);
        var defaultStack = Items.POTION.getDefaultInstance();
        var rawKey = AEItemKey.of(raw);
        var itemKey = AEItemKey.of(Items.POTION);
        var stackKey = AEItemKey.of(defaultStack);
        row("potion_raw_matches_input", rawKey.matches(raw));
        row("potion_itemlike_equals_default_stack", itemKey.equals(stackKey));
        row("potion_raw_distinct_from_water", !rawKey.equals(stackKey));
        row("potion_disk_roundtrip", itemKey.equals(AEItemKey.fromTag(h.getLevel().registryAccess(), itemKey.toTag(h.getLevel().registryAccess()))));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            itemKey.writeToPacket(buffer);
            var decoded = AEItemKey.fromPacket(buffer);
            row("potion_packet_roundtrip", itemKey.equals(decoded));
            row("potion_storage_lookup_after_packet", java.util.Map.of(itemKey, 42).get(decoded));
        } finally { buffer.release(); }

        // Force both first factories to reach construction before either can publish a key.
        barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> AEItemKey.of(new ItemStack(racing)));
            var b = pool.submit(() -> AEItemKey.of(new ItemStack(racing)));
            var x = a.get(15, TimeUnit.SECONDS); var y = b.get(15, TimeUnit.SECONDS);
            row("concurrent_first_keys_equal", x.equals(y));
            var map = new java.util.HashMap<AEItemKey, Integer>(); map.put(x, 1); map.merge(y, 1, Integer::sum);
            row("concurrent_storage_type_count", map.size());
        } finally { barrier = null; pool.shutdownNow(); }

        // Synthetic external-state mutation, not evidence of a real addon compatibility failure.
        var input = new ItemStack(dynamic);
        row("dynamic_limit_initial", AEItemKey.of(input).getMaxStackSize());
        limit.set(16);
        row("dynamic_limit_after_change", AEItemKey.of(input).getMaxStackSize());
        limit.set(64);
        var bulk = new ItemStack(Items.IRON_INGOT, 64); bulk.setPopTime(5);
        var readOnly = AEItemKey.of(bulk).getReadOnlyStack();
        row("native_stack_count_poptime", readOnly.getCount() + "," + readOnly.getPopTime());
        var wrongInputs = new java.util.ArrayList<String>();
        var unequalFactories = new java.util.ArrayList<String>();
        for (var item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            var s = new ItemStack(item);
            if (!AEItemKey.of(s).matches(s)) wrongInputs.add(BuiltInRegistries.ITEM.getKey(item).toString());
            if (!AEItemKey.of(item).equals(AEItemKey.of(item.getDefaultInstance())))
                unequalFactories.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        row("registry_input_mismatches", wrongInputs);
        row("registry_factory_inequality", unequalFactories);
        if (!Boolean.getBoolean("comparison.skipBenchmark")) ComparisonBenchmark.run();
        h.succeed();
    }
}
