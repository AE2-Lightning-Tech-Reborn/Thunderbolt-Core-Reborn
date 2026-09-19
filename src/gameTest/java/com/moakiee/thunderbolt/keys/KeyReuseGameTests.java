package com.moakiee.thunderbolt.keys;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlannerV2;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@GameTestHolder("thunderbolt_keys")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "thunderbolt", bus = EventBusSubscriber.Bus.MOD)
public final class KeyReuseGameTests {
    private static final java.util.concurrent.atomic.AtomicInteger STACK_LIMIT = new java.util.concurrent.atomic.AtomicInteger(64);
    private static Item dynamicItem;

    @SubscribeEvent
    public static void registerTestItem(RegisterEvent event) {
        event.register(Registries.ITEM, helper -> {
            dynamicItem = new Item(new Item.Properties()) {
                @Override public int getMaxStackSize(ItemStack stack) { return STACK_LIMIT.get(); }
            };
            helper.register(ResourceLocation.fromNamespaceAndPath("thunderbolt", "key_reuse_test_item"), dynamicItem);
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void factoryHooksReusePlainKeysAndAcceptBlocks(GameTestHelper h) {
        require(KeyConstructionCache.enabled(), "common config did not enable reuse");
        var stone = AEItemKey.of(Blocks.STONE);
        require(stone == AEItemKey.of(Items.STONE), "native item constructor hook did not apply");
        require(stone == AEItemKey.of(new ItemStack(Items.STONE)), "stack factory differs");
        require(stone == stone.dropSecondary(), "dropSecondary differs");
        require(AEItemKey.of(ItemStack.EMPTY) == null, "empty item accepted");
        require(AEFluidKey.of(FluidStack.EMPTY) == null, "empty fluid accepted");
        var water = AEFluidKey.of(new FluidStack(Fluids.WATER, 1000));
        require(water == AEFluidKey.of(new FluidStack(Fluids.WATER, 17)),
                "native fluid constructor hook did not apply");
        require(water == AEFluidKey.of(Fluids.WATER), "fluid factory differs");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void componentsCountsAndNativeCopiesArePreserved(GameTestHelper h) {
        var source = new ItemStack(Items.IRON_INGOT);
        var original = AEItemKey.of(source);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
        AEItemKey.of(source); // First copy is deliberately not admitted.
        var named = AEItemKey.of(source);
        require(original.get(DataComponents.CUSTOM_NAME) == null, "caller mutation leaked");
        require(!named.equals(original), "named stack merged with plain stack");
        require(named == AEItemKey.of(source), "shared component snapshot was not reused");
        var removed = new ItemStack(Items.IRON_INGOT);
        removed.remove(DataComponents.RARITY);
        require(!original.equals(AEItemKey.of(removed)), "removed default was lost");
        var tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(tool.getMaxDamage() - 1);
        require(AEItemKey.of(tool).getFuzzySearchValue() == tool.getDamageValue(), "damage lost");
        var large = new ItemStack(Items.IRON_INGOT, 64);
        large.setPopTime(5);
        var key = AEItemKey.of(large);
        require(key.getReadOnlyStack().getCount() == 64 && key.getReadOnlyStack().getPopTime() == 5,
                "native read-only stack normalized");
        var output = original.toStack(20);
        output.set(DataComponents.CUSTOM_NAME, Component.literal("mutated copy"));
        require(original.get(DataComponents.CUSTOM_NAME) == null, "toStack was not independent");
        var fluid = new FluidStack(Fluids.WATER, 1000);
        fluid.set(DataComponents.CUSTOM_NAME, Component.literal("named fluid"));
        AEFluidKey.of(fluid);
        var fluidKey = AEFluidKey.of(fluid);
        require(fluidKey == AEFluidKey.of(fluid) && !fluidKey.equals(AEFluidKey.of(Fluids.WATER)),
                "fluid variant lost");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void packetAndDiskKeysStillFindExistingStorage(GameTestHelper h) {
        var named = new ItemStack(Items.DIAMOND);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("named diamond"));
        var fluid = new FluidStack(Fluids.LAVA, 1000);
        fluid.set(DataComponents.CUSTOM_NAME, Component.literal("named lava"));
        var keys = List.of(AEItemKey.of(Items.DIAMOND), AEItemKey.of(named),
                AEFluidKey.of(Fluids.LAVA), AEFluidKey.of(fluid));
        var storage = new IndexedStorage();
        storage.enableArbitraryPrecision();
        var amount = BigInteger.TEN.pow(100);
        for (var key : keys) {
            var lookup = Map.of(key, "found");
            var disk = key instanceof AEItemKey
                    ? AEItemKey.fromTag(h.getLevel().registryAccess(), key.toTag(h.getLevel().registryAccess()))
                    : AEFluidKey.fromTag(h.getLevel().registryAccess(), key.toTag(h.getLevel().registryAccess()));
            require("found".equals(lookup.get(disk)), "disk key lost value equality");
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
            try {
                key.writeToPacket(buffer);
                AEKey packet = key instanceof AEItemKey ? AEItemKey.fromPacket(buffer) : AEFluidKey.fromPacket(buffer);
                require(buffer.readableBytes() == 0 && "found".equals(lookup.get(packet)),
                        "packet key lost equality or codec alignment");
                storage.insertExact(key, amount, Actionable.MODULATE);
                require(storage.extractExact(packet, BigInteger.ONE, Actionable.MODULATE).equals(BigInteger.ONE),
                        "decoded key could not extract original storage");
                require(storage.getAmountExact(disk).equals(amount.subtract(BigInteger.ONE)), "storage types split");
            } finally { buffer.release(); }
        }
        var saved = storage.persist(null, h.getLevel().registryAccess());
        var restored = new IndexedStorage();
        restored.enableArbitraryPrecision();
        restored.load(saved, h.getLevel().registryAccess());
        require(restored.getTotalTypes() == keys.size(), "disk reload merged/split types");
        for (var key : keys) require(restored.getAmountExact(key).equals(amount.subtract(BigInteger.ONE)),
                "exact amount changed after reload");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void concurrentFactoriesAndCacheResetsPreserveEquality(GameTestHelper h) throws Exception {
        KeyConstructionCache.clear();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<List<AEKey>>>();
            for (int t = 0; t < 8; t++) jobs.add(pool.submit(() -> {
                var result = new java.util.ArrayList<AEKey>();
                for (int i = 0; i < 250; i++) {
                    result.add(AEItemKey.of(new ItemStack(Items.COPPER_INGOT)));
                    result.add(AEFluidKey.of(new FluidStack(Fluids.WATER, 1000)));
                }
                return result;
            }));
            var distinct = new HashMap<AEKey, Integer>();
            for (var job : jobs) for (var key : job.get(15, TimeUnit.SECONDS)) distinct.merge(key, 1, Integer::sum);
            require(distinct.size() == 2 && distinct.values().stream().allMatch(n -> n == 2000),
                    "concurrency split a semantic key");
            KeyConstructionCache.clear();
            require(distinct.containsKey(AEItemKey.of(Items.COPPER_INGOT)), "reset changed equality");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void disablingCacheRestoresFreshConstruction(GameTestHelper h) {
        try {
            var cached = AEItemKey.of(Items.STONE);
            KeyConstructionCache.configure(false);
            var first = AEItemKey.of(Items.STONE);
            require(first != AEItemKey.of(Items.STONE) && first.equals(cached), "disable failed");
            var fluid = AEFluidKey.of(Fluids.WATER);
            require(fluid != AEFluidKey.of(Fluids.WATER), "fluid disable failed");
        } finally { KeyConstructionCache.configure(true); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void plannerMatchesDecodedStockWithoutMergingVariants(GameTestHelper h) {
        var raw = AEItemKey.of(Items.IRON_INGOT);
        var target = AEItemKey.of(Items.DIAMOND);
        var decoded = AEItemKey.fromTag(h.getLevel().registryAccess(), raw.toTag(h.getLevel().registryAccess()));
        var namedStack = new ItemStack(Items.IRON_INGOT);
        namedStack.set(DataComponents.CUSTOM_NAME, Component.literal("separate"));
        var named = AEItemKey.of(namedStack);
        for (boolean enabled : new boolean[]{false, true}) {
            KeyConstructionCache.configure(enabled);
            var graph = CraftGraph.<AEKey>builder()
                    .pattern(target, 1, List.of(CraftInput.of(raw, 2), CraftInput.of(named, 1)))
                    .stock(decoded, 2).stock(named, 1).build();
            var plan = CraftPlannerV2.plan(graph, target, 1);
            require(plan.feasible() && plan.missing().isEmpty() && plan.usedStock().get(decoded) == 2
                    && plan.usedStock().get(named) == 1, "decoded stock or component identity lost");
            var shortage = CraftPlannerV2.plan(CraftGraph.<AEKey>builder()
                    .pattern(target, 1, List.of(CraftInput.of(named, 1)))
                    .stock(decoded, 1000).build(), target, 1);
            require(!shortage.feasible() && shortage.missing().get(named) == 1,
                    "ordinary stock falsely satisfied a component requirement");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void dynamicModdedStackLimitIsRecheckedOnAHit(GameTestHelper h) {
        try {
            STACK_LIMIT.set(64);
            var stack = new ItemStack(dynamicItem);
            var first = AEItemKey.of(stack);
            require(first == AEItemKey.of(stack), "custom plain item was not cached");
            STACK_LIMIT.set(16);
            var second = AEItemKey.of(stack);
            require(first != second && second.getMaxStackSize() == 16 && first.equals(second),
                    "stack-sensitive NeoForge hook was ignored or changed equality");
        } finally { STACK_LIMIT.set(64); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void constructionBenchmark(GameTestHelper h) {
        if (Boolean.getBoolean("thunderbolt.keyReuseBenchmark")) KeyReuseBenchmark.run();
        h.succeed();
    }
}
