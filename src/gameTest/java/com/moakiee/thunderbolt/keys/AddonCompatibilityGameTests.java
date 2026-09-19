package com.moakiee.thunderbolt.keys;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import com.moakiee.thunderbolt.core.keys.ObjectReuseOptions;
import com.moakiee.thunderbolt.core.keys.ResourceConstructionCache;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Optional real-addon contracts. Reflection is test-only; the production jar has no addon dependency. */
@GameTestHolder("thunderbolt_keys")
@PrefixGameTestTemplate(false)
public final class AddonCompatibilityGameTests {
    private static final List<String> ADDONS = List.of("extendedae", "advanced_ae", "neoecoae",
            "ae2lt", "data_energistics", "extendedae_plus", "useless_mod");

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void enable(boolean value) {
        KeyConstructionCache.configure(value);
        ResourceConstructionCache.configure(value);
        ObjectReuseOptions.cacheHashes = value;
        ObjectReuseOptions.fastNbtCopies = value;
    }

    private static List<String> loadedAddons() {
        for (String id : System.getProperty("thunderbolt.expectedCompatMods", "").split(",")) {
            if (!id.isBlank()) require(ModList.get().isLoaded(id.trim()), "Expected addon not loaded: " + id);
        }
        return ADDONS.stream().filter(id -> ModList.get().isLoaded(id)).toList();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void addonItemsPreserveKeysComponentsAndCodecs(GameTestHelper h) {
        try {
            for (String addon : loadedAddons()) {
                int tested = 0;
                int reused = 0;
                for (var item : BuiltInRegistries.ITEM) {
                    var id = BuiltInRegistries.ITEM.getKey(item);
                    if (!id.getNamespace().equals(addon)) continue;
                    var source = item.getDefaultInstance();
                    if (source.isEmpty()) continue;
                    var variant = source.copy();
                    var tag = new CompoundTag();
                    tag.putString("variant", id.toString());
                    tag.putIntArray("array", new int[]{1, 2, 3});
                    variant.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                    variant.set(DataComponents.CUSTOM_NAME, Component.literal("key reuse variant"));
                    enable(false);
                    var baseline = AEItemKey.of(source);
                    var baselineVariant = AEItemKey.of(variant);
                    enable(true);
                    var cached = AEItemKey.of(source);
                    var repeated = AEItemKey.of(source);
                    require(repeated.equals(baseline) && repeated.hashCode() == baseline.hashCode(),
                            id + ": repeated factory changed identity");
                    if (repeated == cached) reused++;
                    var changed = AEItemKey.of(variant);
                    require(AEItemKey.of(variant).equals(baselineVariant), id + ": repeated component factory differs");
                    require(cached.equals(baseline) && cached.hashCode() == baseline.hashCode(), id + ": plain differs");
                    require(changed.equals(baselineVariant) && changed.hashCode() == baselineVariant.hashCode(),
                            id + ": component key differs");
                    require(cached.getMaxStackSize() == baseline.getMaxStackSize()
                                    && changed.getFuzzySearchValue() == baselineVariant.getFuzzySearchValue(),
                            id + ": stack-sensitive metadata differs");
                    require(!cached.equals(changed), id + ": component variants merged");
                    verifyRoundTrip(h, cached);
                    verifyRoundTrip(h, changed);
                    var decodedVariant = AEKey.fromTagGeneric(h.getLevel().registryAccess(),
                            changed.toTagGeneric(h.getLevel().registryAccess()));
                    var storage = new IndexedStorage();
                    storage.enableArbitraryPrecision();
                    var count = BigInteger.TEN.pow(60);
                    storage.insertExact(cached, count, Actionable.MODULATE);
                    storage.insertExact(changed, BigInteger.TEN, Actionable.MODULATE);
                    require(storage.extractExact(decodedVariant, BigInteger.ONE, Actionable.MODULATE)
                            .equals(BigInteger.ONE), id + ": decoded variant extraction failed");
                    require(storage.getAmountExact(cached).equals(count)
                                    && storage.getAmountExact(changed).equals(BigInteger.valueOf(9)),
                            id + ": storage merged variants");
                    variant.set(DataComponents.CUSTOM_NAME, Component.literal("new snapshot"));
                    require(changed.equals(baselineVariant) && !changed.equals(AEItemKey.of(variant)),
                            id + ": caller mutation changed retained key");
                    tested++;
                }
                require(tested > 0, addon + ": no registered items tested");
                require(reused > 0, addon + ": no actual cache reuse exercised");
                System.out.printf("OBJECT_REUSE_ADDON_ITEMS %s version=%s items=%d reused=%d%n", addon,
                        ModList.get().getModContainerById(addon).orElseThrow().getModInfo().getVersion(), tested, reused);
            }
        } finally { enable(true); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void addonMaterialsSurvivePatternEncodingAndDecode(GameTestHelper h) {
        try {
            for (String addon : loadedAddons()) {
                var item = BuiltInRegistries.ITEM.stream()
                        .filter(i -> BuiltInRegistries.ITEM.getKey(i).getNamespace().equals(addon)).findFirst().orElseThrow();
                for (boolean enabled : new boolean[]{false, true}) {
                    enable(enabled);
                    var plain = AEItemKey.of(item.getDefaultInstance());
                    var variant = item.getDefaultInstance();
                    variant.set(DataComponents.CUSTOM_NAME, Component.literal("pattern variant"));
                    var named = AEItemKey.of(variant);
                    var encoded = PatternDetailsHelper.encodeProcessingPattern(
                            List.of(new GenericStack(plain, 2), new GenericStack(named, 3)),
                            List.of(new GenericStack(AEItemKey.of(Items.DIAMOND), 1)));
                    var key = AEItemKey.of(encoded);
                    verifyRoundTrip(h, key);
                    var disk = (AEItemKey) AEKey.fromTagGeneric(h.getLevel().registryAccess(),
                            key.toTagGeneric(h.getLevel().registryAccess()));
                    var pattern = PatternDetailsHelper.decodePattern(disk, h.getLevel());
                    require(pattern != null && pattern.getInputs().length == 2, addon + ": pattern inputs merged");
                    var required = new java.util.HashMap<AEKey, Long>();
                    for (var input : pattern.getInputs()) {
                        var choice = input.getPossibleInputs()[0];
                        required.put(choice.what(), choice.amount() * input.getMultiplier());
                    }
                    require(required.equals(Map.of(plain, 2L, named, 3L)), addon + ": pattern amounts/components changed");
                }
            }
        } finally { enable(true); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void addonCustomKeyTypesKeepGenericCodecs(GameTestHelper h) throws Exception {
        var keys = new ArrayList<AEKey>();
        if (loadedAddons().contains("data_energistics")) {
            for (String type : List.of("DataKey", "DataFlowKey", "EchoKey")) keys.add((AEKey) Class.forName(
                    "com.fish_dan_.data_energistics.ae2.key." + type).getField("INSTANCE").get(null));
        }
        if (ModList.get().isLoaded("ae2lt")) {
            var type = Class.forName("com.moakiee.ae2lt.me.key.LightningKey");
            keys.add((AEKey) type.getField("HIGH_VOLTAGE").get(null));
            keys.add((AEKey) type.getField("EXTREME_HIGH_VOLTAGE").get(null));
        }
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                enable(enabled);
                for (var key : keys) verifyRoundTrip(h, key);
                require(new java.util.HashSet<>(keys).size() == keys.size(), "Custom key types merged");
            }
        } finally { enable(true); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void addonCellsExtractDecodedKeysAndKeepVariantsSeparate(GameTestHelper h) throws Exception {
        var loaded = loadedAddons();
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                enable(enabled);
                if (loaded.contains("neoecoae")) {
                    var factory = Class.forName("cn.dancingsnow.neoecoae.api.storage.ECOStorageCells")
                            .getMethod("getCellInventory", ItemStack.class, ISaveProvider.class);
                    var stack = item("neoecoae:eco_item_storage_cell_16m");
                    checkCell(h, stack, s -> (StorageCell) factory.invoke(null, s, null));
                }
                if (loaded.contains("ae2lt")) checkCell(h, item("ae2lt:infinite_storage_cell"),
                        s -> StorageCells.getCellInventory(s, null));
                if (loaded.contains("extendedae")) {
                    var cell = StorageCells.getCellInventory(item("extendedae:infinity_cobblestone_cell"), null);
                    require(cell != null, "ExtendedAE cell handler absent");
                    var cobble = AEItemKey.of(Items.COBBLESTONE);
                    var decoded = AEKey.fromTagGeneric(h.getLevel().registryAccess(),
                            cobble.toTagGeneric(h.getLevel().registryAccess()));
                    require(cell.extract(decoded, 7, Actionable.MODULATE, IActionSource.empty()) == 7,
                            "ExtendedAE infinite cell could not match a decoded key");
                }
            }
        } finally { enable(true); }
        h.succeed();
    }

    @FunctionalInterface
    private interface CellFactory { StorageCell create(ItemStack stack) throws Exception; }

    private static void checkCell(GameTestHelper h, ItemStack stack, CellFactory factory) throws Exception {
        var cell = factory.create(stack);
        require(cell != null, "Cell handler absent: " + stack);
        var plain = AEItemKey.of(Items.IRON_INGOT);
        var variant = new ItemStack(Items.IRON_INGOT);
        variant.set(DataComponents.CUSTOM_NAME, Component.literal("stored variant"));
        var named = AEItemKey.of(variant);
        require(cell.insert(plain, 20, Actionable.MODULATE, IActionSource.empty()) == 20, "Plain insertion failed");
        require(cell.insert(named, 3, Actionable.MODULATE, IActionSource.empty()) == 3, "Variant insertion failed");
        cell.persist();
        var decodedStack = ItemStack.parse(h.getLevel().registryAccess(), stack.save(h.getLevel().registryAccess())).orElseThrow();
        var restored = factory.create(decodedStack);
        var decoded = AEKey.fromTagGeneric(h.getLevel().registryAccess(), named.toTagGeneric(h.getLevel().registryAccess()));
        require(restored.extract(decoded, 99, Actionable.MODULATE, IActionSource.empty()) == 3, "Variant count changed on reload");
        require(restored.extract(plain, 99, Actionable.MODULATE, IActionSource.empty()) == 20, "Plain and named storage merged");
    }

    private static ItemStack item(String name) {
        var id = ResourceLocation.parse(name);
        require(BuiltInRegistries.ITEM.containsKey(id), "Missing addon item: " + id);
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    private static void verifyRoundTrip(GameTestHelper h, AEKey key) {
        var lookup = Map.of(key, "found");
        var disk = AEKey.fromTagGeneric(h.getLevel().registryAccess(), key.toTagGeneric(h.getLevel().registryAccess()));
        require("found".equals(lookup.get(disk)), key + ": generic disk codec changed identity");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            AEKey.writeKey(buffer, key);
            var packet = AEKey.readKey(buffer);
            require(buffer.readableBytes() == 0 && "found".equals(lookup.get(packet)),
                    key + ": generic packet codec changed identity/alignment");
        } finally { buffer.release(); }
    }
}
