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
    private static final java.util.concurrent.atomic.AtomicInteger STACK_LIMIT_CALLS = new java.util.concurrent.atomic.AtomicInteger();
    private static Item componentLimitItem;
    private static Item countSensitiveItem;

    @SubscribeEvent
    public static void registerTestItem(RegisterEvent event) {
        event.register(Registries.ITEM, helper -> {
            componentLimitItem = new Item(new Item.Properties()) {
                @Override public int getMaxStackSize(ItemStack stack) {
                    STACK_LIMIT_CALLS.incrementAndGet();
                    // Deliberately differs from the component, proving that native hooks still run on creation.
                    return stack.getOrDefault(DataComponents.MAX_STACK_SIZE, 64) / 2;
                }
            };
            helper.register(ResourceLocation.fromNamespaceAndPath("thunderbolt", "key_reuse_test_item"), componentLimitItem);
            countSensitiveItem = new Item(new Item.Properties()) {
                @Override public int getMaxStackSize(ItemStack stack) { return stack.getCount() == 1 ? 16 : 64; }
            };
            helper.register(ResourceLocation.fromNamespaceAndPath("thunderbolt", "count_sensitive_key_test_item"), countSensitiveItem);
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
    public static void fluidFieldsDetachAndRejectLatePublication(GameTestHelper h) throws Exception {
        KeyConstructionCache.configure(true);
        var owner = (com.moakiee.thunderbolt.core.keys.FluidKeyCacheOwner) (Object) Fluids.WATER;
        var old = AEFluidKey.of(Fluids.WATER);
        var field = net.minecraft.world.level.material.Fluid.class.getDeclaredField("thunderbolt$fluidGeneration");
        field.setAccessible(true);
        var generation = (com.moakiee.thunderbolt.core.keys.PlainFluidKeyCache) field.get(Fluids.WATER);
        require(generation != null && owner.thunderbolt$plainFluidKey() == old, "plain Fluid slot missing");
        KeyConstructionCache.clear();
        require(owner.thunderbolt$plainFluidKey() == null && field.get(Fluids.WATER) == null,
                "reset retained the old Fluid generation");
        owner.thunderbolt$publishFluidKey(generation, old);
        require(owner.thunderbolt$plainFluidKey() == null, "old publication survived reset");
        var current = AEFluidKey.of(Fluids.WATER);
        require(current != old && current.equals(old) && current.hashCode() == old.hashCode(),
                "reset changed fluid value equality");
        owner.thunderbolt$publishFluidKey(generation, old);
        owner.thunderbolt$clearFluidKey(generation);
        require(owner.thunderbolt$plainFluidKey() == current, "stale operation replaced the current Fluid slot");
        try {
            KeyConstructionCache.configure(false);
            require(owner.thunderbolt$plainFluidKey() == null && field.get(Fluids.WATER) == null,
                    "disable retained the Fluid slot");
            require(AEFluidKey.of(Fluids.WATER) != AEFluidKey.of(Fluids.WATER), "disabled fluid still reused");
        } finally { KeyConstructionCache.configure(true); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fluidFastHitsRespectEmptyStacksAndCustomPrototypes(GameTestHelper h) throws Exception {
        var source = new FluidStack(Fluids.WATER, 1000);
        var plain = AEFluidKey.of(source);
        for (int amount : new int[]{1, 17, 1000, Integer.MAX_VALUE}) {
            source.setAmount(amount);
            require(AEFluidKey.of(source) == plain && source.getAmount() == amount, "fluid amount changed the key/caller");
        }
        for (int amount : new int[]{0, -1}) {
            source.setAmount(amount);
            require(AEFluidKey.of(source) == null, "empty fluid hit a populated ordinary slot");
        }
        require(AEFluidKey.of(Fluids.EMPTY) == null && AEFluidKey.of(FluidStack.EMPTY) == null, "EMPTY fluid accepted");
        var constructor = FluidStack.class.getDeclaredConstructor(net.minecraft.world.level.material.Fluid.class,
                int.class, net.minecraft.core.component.PatchedDataComponentMap.class);
        constructor.setAccessible(true);
        var prototype = net.minecraft.core.component.DataComponentMap.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal("custom prototype")).build();
        var custom = constructor.newInstance(Fluids.WATER, 1000,
                new net.minecraft.core.component.PatchedDataComponentMap(prototype));
        require(custom.isComponentsPatchEmpty(), "custom prototype fixture unexpectedly patched");
        var key = AEFluidKey.of(custom);
        require(!key.equals(plain) && key.matches(custom) && AEFluidKey.of(Fluids.WATER) == plain,
                "custom prototype was merged into the ordinary Fluid slot");
        source.setAmount(1000);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
        AEFluidKey.of(source);
        var named = AEFluidKey.of(source);
        require(named == AEFluidKey.of(source), "repeated fluid component snapshot missed");
        source.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        require(!named.equals(AEFluidKey.of(source)) && plain.get(DataComponents.CUSTOM_NAME) == null,
                "caller mutation leaked into a cached fluid key");
        try {
            KeyConstructionCache.configure(true, false);
            require(AEFluidKey.of(source) != AEFluidKey.of(source), "fluid component opt-out ignored");
            require(AEFluidKey.of(Fluids.WATER) == AEFluidKey.of(Fluids.WATER), "component opt-out disabled ordinary fluids");
        } finally { KeyConstructionCache.configure(true); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void allRegisteredFluidsKeepNativeValuesAndIndependentStacks(GameTestHelper h) {
        var baselines = new java.util.HashMap<net.minecraft.world.level.material.Fluid, AEFluidKey>();
        try {
            KeyConstructionCache.configure(false);
            for (var fluid : net.minecraft.core.registries.BuiltInRegistries.FLUID) {
                if (fluid != Fluids.EMPTY) baselines.put(fluid, AEFluidKey.of(fluid));
            }
        } finally { KeyConstructionCache.configure(true); }
        baselines.forEach((fluid, nativeKey) -> {
            var key = AEFluidKey.of(new FluidStack(fluid, 1000));
            require(key.equals(nativeKey) && key.hashCode() == nativeKey.hashCode(), "registered fluid semantics changed: " + fluid);
            require(key == AEFluidKey.of(fluid), "registered Fluid direct slot missing");
            var mutable = key.toStack(17);
            require(mutable.getAmount() == 17, "toStack ignored fluid amount");
            mutable.set(DataComponents.CUSTOM_NAME, Component.literal("output changed"));
            require(key.equals(nativeKey), "output mutation changed stored fluid key");
        });
        System.out.println("FLUID_REGISTRY_AUDIT matched=" + baselines.size());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void componentsAndCallerStacksSurviveKeyNormalization(GameTestHelper h) {
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
        require(key == original && key.getReadOnlyStack().getCount() == 1 && key.getReadOnlyStack().getPopTime() == 0,
                "plain key was not normalized and shared");
        require(large.getCount() == 64 && large.getPopTime() == 5, "normalization changed the caller stack");
        large.setPopTime(2);
        require(key == AEItemKey.of(large), "animation split the normalized key");
        large.setCount(32);
        require(key == AEItemKey.of(large), "quantity split the normalized key");
        require(large.getCount() == 32 && large.getPopTime() == 2, "cache hit changed the caller stack");
        var output = original.toStack(20);
        require(output.getCount() == 20, "toStack lost the explicitly requested output quantity");
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
    public static void customStackLimitsAreCapturedPerComponentSnapshot(GameTestHelper h) {
        var stack = new ItemStack(componentLimitItem, 64);
        var plain = AEItemKey.of(stack);
        require(plain.getMaxStackSize() == 32, "native custom stack limit was ignored");
        int calls = STACK_LIMIT_CALLS.get();
        require(plain == AEItemKey.of(stack) && STACK_LIMIT_CALLS.get() == calls,
                "plain cache hit reevaluated the stack limit");

        stack.set(DataComponents.MAX_STACK_SIZE, 32);
        var limited = AEItemKey.of(stack);
        require(limited.getMaxStackSize() == 16 && plain.getMaxStackSize() == 32 && !limited.equals(plain),
                "changed components reused stale metadata or mutated the old key");
        // Warm the identity alias before checking the hit and independent equal-content paths.
        limited = AEItemKey.of(stack);
        calls = STACK_LIMIT_CALLS.get();
        require(limited == AEItemKey.of(stack) && STACK_LIMIT_CALLS.get() == calls,
                "component identity hit reevaluated the stack limit");
        var equal = new ItemStack(componentLimitItem, 64);
        equal.set(DataComponents.MAX_STACK_SIZE, 32);
        equal.copy(); // Share the native COW snapshot so this lookup exercises content interning.
        require(limited == AEItemKey.of(equal) && STACK_LIMIT_CALLS.get() == calls,
                "equal component content reevaluated the stack limit or lost its representative");
        stack.set(DataComponents.MAX_STACK_SIZE, 16);
        var smaller = AEItemKey.of(stack);
        require(smaller.getMaxStackSize() == 8 && limited.getMaxStackSize() == 16 && !smaller.equals(limited),
                "a second component change reused stale stack limits");
        stack.set(DataComponents.MAX_STACK_SIZE, 64);
        require(AEItemKey.of(stack) == plain && stack.getCount() == 64,
                "restoring default components lost the plain key or changed the caller");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeStackLimitComponentsRemainDistinct(GameTestHelper h) {
        var stack = new ItemStack(Items.IRON_INGOT, 64);
        var plain = AEItemKey.of(stack);
        stack.set(DataComponents.MAX_STACK_SIZE, 16);
        var limited = AEItemKey.of(stack);
        require(plain.getMaxStackSize() == 64 && limited.getMaxStackSize() == 16 && !plain.equals(limited),
                "native MAX_STACK_SIZE component was ignored");
        limited = AEItemKey.of(stack); // First writable snapshots deliberately bypass cache admission.
        require(limited == AEItemKey.of(stack) && stack.getCount() == 64,
                "native component key was not reused or changed the caller");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void constructionBenchmark(GameTestHelper h) {
        if (Boolean.getBoolean("thunderbolt.keyReuseBenchmark")) KeyReuseBenchmark.run();
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void defaultPotionsAndEmptyPatchesRemainDistinct(GameTestHelper h) {
        for (var item : List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW)) {
            var raw = new ItemStack(item);
            var water = item.getDefaultInstance();
            var plain = AEItemKey.of(raw);
            var defaultKey = AEItemKey.of(item);
            require(plain.matches(raw), "empty patch became a default potion");
            require(defaultKey.equals(AEItemKey.of(water)) && !plain.equals(defaultKey), "potion factory identity changed");
            var decoded = AEItemKey.fromTag(h.getLevel().registryAccess(), defaultKey.toTag(h.getLevel().registryAccess()));
            require(defaultKey.equals(decoded), "potion disk roundtrip changed equality");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void independentComponentSnapshotsConvergeAndDetachOnMutation(GameTestHelper h) {
        KeyConstructionCache.configure(true);
        var a = new ItemStack(Items.DIAMOND);
        a.set(DataComponents.CUSTOM_NAME, Component.literal("canonical"));
        AEItemKey.of(a); // First writable snapshot deliberately bypasses admission.
        var first = AEItemKey.of(a);
        var b = new ItemStack(Items.DIAMOND);
        b.set(DataComponents.CUSTOM_NAME, Component.literal("canonical"));
        AEItemKey.of(b);
        require(first == AEItemKey.of(b), "content L2 did not reuse an independent equal patch");
        require(first == AEItemKey.of(b), "identity alias did not reuse the content representative");
        b.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        var changed = AEItemKey.of(b);
        require(!changed.equals(first) && first.get(DataComponents.CUSTOM_NAME).getString().equals("canonical"),
                "COW mutation poisoned the canonical key");
        var bulk = a.copyWithCount(64); bulk.setPopTime(3);
        var bulkKey = AEItemKey.of(bulk);
        require(bulkKey == first && bulkKey.getReadOnlyStack().getCount() == 1
                && bulkKey.getReadOnlyStack().getPopTime() == 0, "component quantity or animation split the canonical key");
        require(bulk.getCount() == 64 && bulk.getPopTime() == 3, "component normalization changed caller state");
        first.getReadOnlyStack().set(DataComponents.CUSTOM_NAME, Component.literal("bad mutation"));
        require(AEItemKey.of(a).matches(a), "invalid canonical representative poisoned an identity alias");
        h.succeed();
    }

    private record GcWitness(AEItemKey key, java.lang.ref.WeakReference<ItemStack> source,
                             java.lang.ref.WeakReference<Object> aliasPatch) {}

    private static ItemStack gcNamedStack(String name) {
        var stack = new ItemStack(Items.GOLD_INGOT, 64);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        // The first copy transitions a fresh writable patch to a reusable COW snapshot.
        AEItemKey.of(stack);
        return stack;
    }

    private static GcWitness prepareGcWitness() {
        var source = gcNamedStack("weak-key-live");
        var key = AEItemKey.of(source);
        var alias = gcNamedStack("weak-key-live");
        require(AEItemKey.of(alias) == key, "independent alias did not converge");
        var patch = ((com.moakiee.thunderbolt.core.keys.SharedComponentPatch) alias.getComponents())
                .thunderbolt$copyOnWritePatchIdentity();
        return new GcWitness(key, new java.lang.ref.WeakReference<>(source), new java.lang.ref.WeakReference<>(patch));
    }

    private static void awaitCollection(java.lang.ref.WeakReference<?> reference) throws Exception {
        for (int i = 0; i < 100 && reference.get() != null; i++) {
            System.gc();
            KeyConstructionCache.maintain();
            Thread.sleep(10);
        }
        require(reference.get() == null, "cache retained an otherwise unused object");
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void liveComponentKeySurvivesGcOfSourcesAndIdentityAliases(GameTestHelper h) throws Exception {
        var witness = prepareGcWitness();
        awaitCollection(witness.source());
        awaitCollection(witness.aliasPatch());
        var independent = gcNamedStack("weak-key-live");
        require(AEItemKey.of(independent) == witness.key(), "live key lost canonicality after GC of cache probes");
        h.succeed();
    }

    private static java.util.List<java.lang.ref.WeakReference<?>> unreferencedComponentKey() {
        var key = AEItemKey.of(gcNamedStack("weak-key-collectible"));
        var patch = ((com.moakiee.thunderbolt.core.keys.SharedComponentPatch) key.getReadOnlyStack().getComponents())
                .thunderbolt$copyOnWritePatchIdentity();
        return java.util.List.of(new java.lang.ref.WeakReference<>(key), new java.lang.ref.WeakReference<>(patch));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void unusedComponentKeyAndCanonicalSnapshotAreCollectible(GameTestHelper h) throws Exception {
        var references = unreferencedComponentKey();
        awaitCollection(references.get(0));
        awaitCollection(references.get(1));
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void itemFieldsAreDetachedWhenTheCacheGenerationEnds(GameTestHelper h) {
        var owner = (com.moakiee.thunderbolt.core.keys.ItemKeyCacheOwner) (Object) Items.IRON_INGOT;
        AEItemKey.of(Items.IRON_INGOT);
        var old = owner.thunderbolt$plainKeyCache();
        var oldSingle = owner.thunderbolt$plainKey();
        AEItemKey.of(new ItemStack(Items.IRON_INGOT, 64));
        var oldOther = owner.thunderbolt$plainKey();
        require(old != null, "Item field fast path was not installed");
        require(oldSingle != null && oldSingle == oldOther, "count variants did not share the direct Item slot");
        KeyConstructionCache.clear();
        require(owner.thunderbolt$plainKeyCache() == null, "old Item field retained its cache generation");
        require(owner.thunderbolt$plainKey() == null,
                "direct Item slots retained the old generation");
        owner.thunderbolt$plainKeyCache(old);
        owner.thunderbolt$publishPlainKey(old, oldSingle);
        owner.thunderbolt$publishPlainKey(old, oldOther);
        require(owner.thunderbolt$plainKeyCache() == null, "late old-generation publication was accepted");
        require(owner.thunderbolt$plainKey() == null,
                "late publication reattached a detached direct slot");
        var current = AEItemKey.of(Items.IRON_INGOT);
        require(owner.thunderbolt$plainKeyCache() != old, "new requests reattached the old generation");
        owner.thunderbolt$clearPlainKeyCache(old);
        owner.thunderbolt$publishPlainKey(old, oldSingle);
        require(owner.thunderbolt$plainKey() == current,
                "an old generation cleared or replaced the new direct slot");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void countSensitiveHooksUseTheNormalizedKeyStack(GameTestHelper h) {
        var source = new ItemStack(countSensitiveItem, 64);
        source.setPopTime(5);
        require(source.getMaxStackSize() == 64, "count-sensitive test item was not configured");
        var key = AEItemKey.of(source);
        require(key.getMaxStackSize() == 16 && key.getReadOnlyStack().getCount() == 1
                        && key.getReadOnlyStack().getPopTime() == 0, "key metadata was computed before normalization");
        require(key == AEItemKey.of(source) && key == AEItemKey.of(new ItemStack(countSensitiveItem)),
                "dynamic-hook probes compared the unnormalized caller metadata");
        require(source.getCount() == 64 && source.getPopTime() == 5 && source.getMaxStackSize() == 64,
                "normalized key construction changed the caller's metadata");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void constructorAndPacketNormalizeWithoutChangingInputs(GameTestHelper h) throws Exception {
        KeyConstructionCache.configure(true);
        var source = new ItemStack(Items.DIAMOND, 64);
        source.setPopTime(7);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("normalized codec input"));
        var constructor = AEItemKey.class.getDeclaredConstructor(ItemStack.class);
        constructor.setAccessible(true);
        var direct = constructor.newInstance(source);
        require(direct.getReadOnlyStack().getCount() == 1 && direct.getReadOnlyStack().getPopTime() == 0
                        && source.getCount() == 64 && source.getPopTime() == 7,
                "constructor normalization changed caller state or retained a non-unit stack");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buffer, source);
            var decoded = AEItemKey.fromPacket(buffer);
            require(decoded.equals(direct) && decoded.getReadOnlyStack().getCount() == 1
                            && decoded.getReadOnlyStack().getPopTime() == 0 && decoded.toStack(20).getCount() == 20,
                    "packet key was not normalized or explicit output quantity changed");
        } finally { buffer.release(); }
        try {
            KeyConstructionCache.configure(false);
            var nativeKey = AEItemKey.of(source);
            require(nativeKey.getReadOnlyStack().getCount() == 64 && nativeKey.getReadOnlyStack().getPopTime() == 7,
                    "disabled key reuse did not restore the native factory");
        } finally { KeyConstructionCache.configure(true); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void plainKeysWithDifferentComponentPrototypesDoNotAlias(GameTestHelper h) throws Exception {
        KeyConstructionCache.configure(true);
        // A custom prototype can contain data even when its patch is empty. Construct the same
        // stack shape as an addon replacing the default component map, without mutating the Item.
        var constructor = ItemStack.class.getDeclaredConstructor(net.minecraft.world.level.ItemLike.class,
                int.class, net.minecraft.core.component.PatchedDataComponentMap.class);
        constructor.setAccessible(true);
        var prototypeA = net.minecraft.core.component.DataComponentMap.builder().addAll(Items.IRON_INGOT.components())
                .set(DataComponents.CUSTOM_NAME, Component.literal("prototype A")).build();
        var prototypeB = net.minecraft.core.component.DataComponentMap.builder().addAll(Items.IRON_INGOT.components())
                .set(DataComponents.CUSTOM_NAME, Component.literal("prototype B")).build();
        var a = constructor.newInstance(Items.IRON_INGOT, 1,
                new net.minecraft.core.component.PatchedDataComponentMap(prototypeA));
        var b = constructor.newInstance(Items.IRON_INGOT, 1,
                new net.minecraft.core.component.PatchedDataComponentMap(prototypeB));
        require(a.isComponentsPatchEmpty() && b.isComponentsPatchEmpty(), "prototype test has a nonempty patch");
        for (int i = 0; i < 10; i++) {
            var keyA = AEItemKey.of(a);
            require(keyA == AEItemKey.of(a), "prototype A did not exercise the direct slot");
            var keyB = AEItemKey.of(b);
            require(keyB == AEItemKey.of(b), "prototype B did not exercise the direct slot");
            require(!keyA.equals(keyB) && keyA.matches(a) && keyB.matches(b),
                    "empty patches with distinct prototypes shared the wrong key");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void concurrentNormalizedKeysPreserveEachCallersStackState(GameTestHelper h) throws Exception {
        KeyConstructionCache.configure(true);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                final int offset = worker;
                jobs.add(pool.submit(() -> {
                    require(start.await(5, TimeUnit.SECONDS), "plain slot workers did not start");
                    var source = new ItemStack(Items.COPPER_INGOT);
                    for (int i = 0; i < 1000; i++) {
                        int variant = (i + offset) % 4;
                        int count = variant < 2 ? 1 : variant == 2 ? 2 : 64;
                        int popTime = variant == 1 ? 5 : variant == 3 ? 3 : 0;
                        source.setCount(count);
                        source.setPopTime(popTime);
                        var key = AEItemKey.of(source);
                        var stack = key.getReadOnlyStack();
                        require(key.matches(source) && stack.getCount() == 1 && stack.getPopTime() == 0
                                        && source.getCount() == count && source.getPopTime() == popTime,
                                "concurrent normalization changed the caller or published a non-unit key");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        } finally { start.countDown(); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void constructorCompletingAfterResetCannotOverwriteDirectItemFields(GameTestHelper h) throws Exception {
        KeyConstructionCache.configure(true);
        var source = new ItemStack(Items.COAL);
        var owner = (com.moakiee.thunderbolt.core.keys.ItemKeyCacheOwner) (Object) Items.COAL;
        var entered = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> KeyConstructionCache.item(source.copy(), args -> {
                entered.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("constructor resume timed out");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return AEItemKey.of((ItemStack) args[0]);
            }));
            try {
                require(entered.await(5, TimeUnit.SECONDS), "constructor did not reach the publication barrier");
                var old = owner.thunderbolt$plainKeyCache();
                KeyConstructionCache.clear();
                var current = AEItemKey.of(source);
                var field = owner.thunderbolt$plainKey();
                resume.countDown();
                require(result.get(5, TimeUnit.SECONDS).equals(current), "late constructor changed value semantics");
                require(owner.thunderbolt$plainKeyCache() != old && owner.thunderbolt$plainKey() == field,
                        "late constructor overwrote the current direct field");
            } finally { resume.countDown(); }
        }
        h.succeed();
    }
}
