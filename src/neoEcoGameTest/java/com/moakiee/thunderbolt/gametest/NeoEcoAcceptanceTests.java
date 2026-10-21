package com.moakiee.thunderbolt.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.*;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathCompat;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.*;
import net.minecraftforge.registries.ForgeRegistries;

/** Requires the real older NeoECO mod; no NeoECO classes are supplied by test fixtures. */
@GameTestHolder("thunderbolt")
@PrefixGameTestTemplate(false)
public final class NeoEcoAcceptanceTests {
    public static ICraftingProvider observedBus;
    public static UUID observedJob;
    public static int observedCalls;

    private static void requireRuntime(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("neoecoae"), "Real NeoECO mod must be loaded");
        helper.assertTrue(ModList.get().isLoaded("ldlib"), "Real LDLib dependency must be loaded");
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void nativeAdapterRegistersForTheRealMod(GameTestHelper helper) {
        requireRuntime(helper);
        var adapter = BatchProviderAdapters.entries().stream()
                .filter(e -> e.id().equals(new ResourceLocation("thunderbolt", "neoeco")))
                .findFirst().orElseThrow();
        helper.assertTrue(adapter.adapter().getClass().getName().endsWith("NeoEcoBatchAdapter"),
                "Native Forge adapter must register during common setup");
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void realUnformedBusBlocksBatchesAndNativeJobCallPreservesInputs(GameTestHelper helper) {
        requireRuntime(helper);
        var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("neoecoae", "crafting_pattern_bus"));
        helper.assertTrue(block != null, "Real pattern bus block must exist");
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.defaultBlockState());
        var bus = (ICraftingProvider) helper.getBlockEntity(pos);
        var adapter = (BatchProviderResolver) NeoEcoFastPathCompat.createAdapter();
        helper.assertTrue(adapter != null, "Real native adapter must link");
        var endpoint = adapter.resolve(bus);
        helper.assertTrue(endpoint != null, "Real bus must be resolved by the native adapter");
        var key = AEItemKey.of(Items.IRON_BLOCK);
        var inputs = new KeyCounter[] {new KeyCounter()};
        inputs[0].add(key, 2);
        UUID jobId = UUID.randomUUID();
        var job = (BatchJobView) Proxy.newProxyInstance(BatchJobView.class.getClassLoader(),
                new Class<?>[] {BatchJobView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "level" -> helper.getLevel();
                    case "craftingId" -> jobId;
                    default -> throw new AssertionError(method.getName());
                });
        var recipe = helper.getLevel().getRecipeManager()
                .byKey(new ResourceLocation("minecraft", "iron_ingot_from_iron_block")).orElseThrow();
        helper.assertTrue(recipe instanceof CraftingRecipe, "Vanilla unpacking recipe must be available");
        var grid = new ItemStack[9];
        Arrays.fill(grid, ItemStack.EMPTY);
        grid[0] = new ItemStack(Items.IRON_BLOCK);
        var encoded = PatternDetailsHelper.encodeCraftingPattern((CraftingRecipe) recipe, grid,
                new ItemStack(Items.IRON_INGOT, 9), false, false);
        var pattern = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(pattern instanceof IMolecularAssemblerSupportedPattern,
                "Use a real decoded crafting pattern supported by the bus");
        observedCalls = 0;
        observedBus = null;
        observedJob = null;
        helper.assertTrue(bus.isBusy(), "Unformed real bus must report busy");
        var prepared = endpoint.prepareBatch(pattern, inputs, 5, job);
        helper.assertTrue(prepared.capacity() == 0, "Busy real bus must not admit a prepared batch");
        helper.assertTrue(endpoint.pushBatch(pattern, inputs, 5, job) == 5,
                "Busy real bus must leave all offered copies unaccepted");
        helper.assertTrue(observedCalls == 0 && inputs[0].get(key) == 2,
                "Busy batch path must not dispatch or consume borrowed inputs");
        helper.assertTrue(!NeoEcoFastPathCompat.pushPattern(bus, pattern, inputs, jobId),
                "Direct native submission must reject without a formed machine");
        helper.assertTrue(observedCalls == 1 && observedBus == bus && jobId.equals(observedJob),
                "Real UUID overload must receive the supplied job exactly once");
        helper.assertTrue(inputs[0].get(key) == 2, "Rejected native submission must preserve inputs");
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void olderRealModKeepsExactAndCancellationOptional(GameTestHelper helper) throws Exception {
        requireRuntime(helper);
        try {
            Class.forName("cn.dancingsnow.neoecoae.api.me.ECOExactBatchProvider");
            helper.fail("This acceptance case requires the older real NeoECO artifact");
            return;
        } catch (ClassNotFoundException expected) {
            // Assert that the older mod stays usable without synthesizing the new API.
        }
        var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("neoecoae", "crafting_pattern_bus"));
        var pos = new BlockPos(2, 1, 1);
        helper.setBlock(pos, block.defaultBlockState());
        var bus = (ICraftingProvider) helper.getBlockEntity(pos);
        helper.assertTrue(NeoEcoFastPathCompat.prepareExact(bus, null, new KeyCounter[0],
                BigInteger.ONE.shiftLeft(100), helper.getLevel(), UUID.randomUUID()) == null,
                "Missing new exact API must decline before reading pattern or inputs");
        NeoEcoFastPathCompat.cancelJob(helper.getLevel(), UUID.randomUUID());
        helper.succeed();
    }
}
