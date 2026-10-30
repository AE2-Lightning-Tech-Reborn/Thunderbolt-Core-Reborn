package com.moakiee.thunderbolt.gametest;

import java.lang.reflect.Method;
import java.util.Arrays;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Exercises actual transformed AE2 caches, including both lookup and insertion guards. */
@GameTestHolder("thunderbolt_nbt")
@PrefixGameTestTemplate(false)
public final class AeKeyIdentityGameTests {
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void craftingCacheSeparatesNbtAndCapabilityVariants(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var recipe = (CraftingRecipe) level.getRecipeManager()
                .byKey(new ResourceLocation("minecraft", "oak_planks")).orElseThrow();
        var inputs = new ItemStack[9];
        Arrays.fill(inputs, ItemStack.EMPTY);
        inputs[0] = new ItemStack(Items.OAK_LOG);
        var encoded = PatternDetailsHelper.encodeCraftingPattern(
                recipe, inputs, new ItemStack(Items.OAK_PLANKS, 4), true, false);
        var pattern = new AECraftingPattern(AEItemKey.of(encoded), level);
        verifyCache(pattern, AEItemKey.of(Items.OAK_LOG), true);
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void stonecuttingCacheSeparatesNbtAndCapabilityVariants(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var recipe = level.getRecipeManager().getAllRecipesFor(RecipeType.STONECUTTING).stream()
                .filter(r -> r.getIngredients().get(0).test(new ItemStack(Items.STONE)))
                .findFirst().orElseThrow();
        var input = AEItemKey.of(Items.STONE);
        var output = AEItemKey.of(recipe.getResultItem(level.registryAccess()));
        var encoded = PatternDetailsHelper.encodeStonecuttingPattern(recipe, input, output, true);
        var pattern = new AEStonecuttingPattern(AEItemKey.of(encoded), level);
        verifyCache(pattern, input, false);
        helper.succeed();
    }

    private static void verifyCache(Object pattern, AEItemKey plain, boolean crafting) throws Exception {
        var type = pattern.getClass();
        var get = crafting ? type.getDeclaredMethod("getTestResult", int.class, AEItemKey.class)
                : type.getDeclaredMethod("getTestResult", AEItemKey.class);
        var set = crafting ? type.getDeclaredMethod("setTestResult", int.class, AEItemKey.class, boolean.class)
                : type.getDeclaredMethod("setTestResult", AEItemKey.class, boolean.class);
        get.setAccessible(true);
        set.setAccessible(true);
        check(read(get, pattern, crafting, plain) == null, "new cache should be empty");
        write(set, pattern, crafting, plain, true);
        check(Boolean.TRUE.equals(read(get, pattern, crafting, plain)), "plain input no longer uses cache");

        var data = new CompoundTag();
        data.putInt("state", 7);
        var nbt = AEItemKey.of(plain.getItem(), data);
        var encoded = plain.toTag();
        var caps = new CompoundTag();
        caps.put("thunderbolt_test:state", data);
        encoded.put("caps", caps);
        var capabilityOnly = AEItemKey.fromTag(encoded);
        check(capabilityOnly != null && !capabilityOnly.hasTag(), "test requires a capability-only key");
        for (var variant : new AEItemKey[]{nbt, capabilityOnly}) {
            check(read(get, pattern, crafting, variant) == null, "variant reused a plain input cache entry");
            write(set, pattern, crafting, variant, false);
            check(Boolean.TRUE.equals(read(get, pattern, crafting, plain)), "variant poisoned plain input cache");
            check(read(get, pattern, crafting, variant) == null, "variant was inserted into item-only cache");
        }
        check(read(get, pattern, crafting, null) == null, "null lookup should bypass cache");
        write(set, pattern, crafting, null, false);
        write(set, pattern, crafting, plain, false);
        check(Boolean.FALSE.equals(read(get, pattern, crafting, plain)), "plain negative results are not cached");
    }

    private static Object read(Method method, Object pattern, boolean crafting, AEItemKey key) throws Exception {
        return method.invoke(pattern, crafting ? new Object[]{0, key} : new Object[]{key});
    }

    private static void write(Method method, Object pattern, boolean crafting, AEItemKey key, boolean result)
            throws Exception {
        method.invoke(pattern, crafting ? new Object[]{0, key, result} : new Object[]{key, result});
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
