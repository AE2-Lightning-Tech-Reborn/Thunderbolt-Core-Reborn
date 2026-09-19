package com.moakiee.thunderbolt.keys;

import java.util.Map;
import appeng.api.stacks.AEItemKey;
import com.moakiee.thunderbolt.core.keys.ObjectReuseOptions;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import com.moakiee.thunderbolt.core.keys.ResourceConstructionCache;
import com.moakiee.thunderbolt.core.keys.SharedComponentPatch;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("thunderbolt_keys")
@PrefixGameTestTemplate(false)
public final class ObjectReuseGameTests {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void resourceFactoriesKeepValidationAndHashContracts(GameTestHelper h) {
        ResourceConstructionCache.configure(true);
        var id = ResourceLocation.fromNamespaceAndPath("thunderbolt", "object_reuse_test");
        int hash = id.hashCode();
        require(id == ResourceLocation.parse("thunderbolt:object_reuse_test"), "resource constructor reuse absent");
        require(id.equals(ResourceLocation.tryBuild("thunderbolt", "object_reuse_test")), "tryBuild differs");
        require(id.equals(ResourceLocation.parse("thunderbolt:other").withPath("object_reuse_test")), "withPath differs");
        require(ResourceLocation.tryBuild("thunderbolt", "INVALID") == null, "invalid path accepted");
        require(ResourceLocation.tryParse("BAD:path") == null, "invalid namespace accepted");
        boolean rejected = false;
        try { ResourceLocation.fromNamespaceAndPath("BAD", "path"); }
        catch (net.minecraft.ResourceLocationException expected) { rejected = true; }
        require(rejected, "throwing factory lost validation");
        try {
            ResourceConstructionCache.configure(false);
            ObjectReuseOptions.cacheHashes = false;
            var independent = ResourceLocation.parse("thunderbolt:object_reuse_test");
            require(independent.equals(id) && independent.hashCode() == hash, "resource equality/hash changed");
            require(Map.of(id, 1).get(independent) == 1, "resource lookup failed after disabling cache");
        } finally { ResourceConstructionCache.configure(true); ObjectReuseOptions.cacheHashes = true; }
        ResourceConstructionCache.configure(false, true);
        ResourceConstructionCache.clear();
        var constructions = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 2; i++) ResourceConstructionCache.location("thunderbolt", "separate_flags", args -> {
            constructions.incrementAndGet();
            return ResourceLocation.fromNamespaceAndPath((String) args[0], (String) args[1]);
        });
        require(constructions.get() == 2, "tag-only configuration or reset enabled resource-location caching");
        h.succeed();
    }

    @SuppressWarnings("deprecation")
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void tagCandidatesKeepVanillaCanonicalIdentityAcrossResets(GameTestHelper h) {
        var id = ResourceLocation.parse("thunderbolt:object_reuse_tag");
        var canonical = TagKey.create(Registries.ITEM, id);
        ResourceConstructionCache.clear();
        require(canonical == TagKey.create(Registries.ITEM, id), "cache replaced vanilla canonical tag");
        require(!canonical.equals(TagKey.create(Registries.BLOCK, id)), "different tag registries merged");
        var direct = new TagKey<Item>(Registries.ITEM, id);
        boolean equalBefore = direct.equals(canonical);
        int directHash, canonicalHash;
        ObjectReuseOptions.cacheHashes = false;
        try { directHash = direct.hashCode(); canonicalHash = canonical.hashCode(); }
        finally { ObjectReuseOptions.cacheHashes = true; }
        require(direct.equals(canonical) == equalBefore && direct.hashCode() == directHash
                && canonical.hashCode() == canonicalHash, "changed native/addon tag semantics");
        var resource = ResourceKey.create(Registries.ITEM, id);
        ResourceConstructionCache.clear();
        require(resource == ResourceKey.create(Registries.ITEM, ResourceLocation.parse(id.toString())),
                "native resource-key interning changed");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void writableComponentMapsAreNotSnapshotTokens(GameTestHelper h) {
        var map = new PatchedDataComponentMap(DataComponentMap.EMPTY);
        map.set(DataComponents.CUSTOM_NAME, Component.literal("before"));
        var access = (SharedComponentPatch) (Object) map;
        require(access.thunderbolt$sharedPatchIdentity() == null, "writable map exposed stable token");
        var firstCopy = map.copy();
        require(((SharedComponentPatch) (Object) firstCopy).thunderbolt$sharedPatchIdentity() == null,
                "one-off snapshot should bypass cache indexing");
        var snapshot = map.copy();
        Object token = ((SharedComponentPatch) (Object) snapshot).thunderbolt$sharedPatchIdentity();
        require(token != null && token == ((SharedComponentPatch) (Object) map.copy()).thunderbolt$sharedPatchIdentity(),
                "native COW snapshot identity missing");
        map.set(DataComponents.CUSTOM_NAME, Component.literal("after"));
        require(access.thunderbolt$sharedPatchIdentity() == null, "write did not detach snapshot token");
        require(snapshot.get(DataComponents.CUSTOM_NAME).getString().equals("before"), "snapshot was mutated");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nestedComponentsReuseSnapshotsWithoutMergingDistinctContent(GameTestHelper h) {
        var data = new CompoundTag();
        data.putString("value", "before");
        var stack = new ItemStack(Items.DIAMOND, 64);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        AEItemKey.of(stack);
        var first = AEItemKey.of(stack);
        require(first == AEItemKey.of(stack), "component snapshot was not reused");
        require(first.getReadOnlyStack().getCount() == 1 && stack.getCount() == 64, "component key was not normalized independently");
        int oldHash = first.hashCode();
        data.putString("value", "after");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        var second = AEItemKey.of(stack);
        require(!first.equals(second) && first.hashCode() == oldHash, "component mutation poisoned cached key");
        var decoded = AEItemKey.fromTag(h.getLevel().registryAccess(), first.toTag(h.getLevel().registryAccess()));
        require(Map.of(first, 1).get(decoded) == 1, "equal independent patch not recognized");
        try {
            KeyConstructionCache.configure(true, false);
            require(AEItemKey.of(stack) != AEItemKey.of(stack), "component-only opt-out failed");
            require(AEItemKey.of(Items.STONE) == AEItemKey.of(Items.STONE), "component opt-out disabled plain reuse");
        } finally { KeyConstructionCache.configure(true); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nbtCopiesKeepDeepCopyBehaviorAndBackingTypes(GameTestHelper h) throws Exception {
        var root = new CompoundTag();
        var entries = new ListTag();
        for (int i = 0; i < 70; i++) {
            var row = new CompoundTag();
            row.putInt("n", i);
            row.putIntArray("array", new int[]{1, 2, 3});
            entries.add(row);
        }
        root.put("rows", entries);
        root.putString("label", "original");
        CompoundTag off;
        ObjectReuseOptions.fastNbtCopies = false;
        try { off = root.copy(); } finally { ObjectReuseOptions.fastNbtCopies = true; }
        var on = root.copy();
        require(on.equals(off) && on.hashCode() == off.hashCode(), "NBT value changed");
        var tagsField = CompoundTag.class.getDeclaredField("tags"); tagsField.setAccessible(true);
        var listField = ListTag.class.getDeclaredField("list"); listField.setAccessible(true);
        require(tagsField.get(on).getClass() == tagsField.get(off).getClass(), "compound backing type changed");
        require(listField.get(on.getList("rows", Tag.TAG_COMPOUND)).getClass()
                == listField.get(off.getList("rows", Tag.TAG_COMPOUND)).getClass(), "list backing type changed");
        on.getList("rows", Tag.TAG_COMPOUND).getCompound(0).getIntArray("array")[0] = 99;
        on.getList("rows", Tag.TAG_COMPOUND).getCompound(1).putInt("n", -1);
        require(entries.getCompound(0).getIntArray("array")[0] == 1 && entries.getCompound(1).getInt("n") == 1,
                "mutable descendants were shared");
        require(root.copy().equals(root) && new CompoundTag().copy().isEmpty()
                && new ListTag().copy().isEmpty(), "empty/copy path differs");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nbtCopiesHonorOverriddenTagsAndCustomBackingCollections(GameTestHelper h) throws Exception {
        var calls = new java.util.ArrayList<Integer>();
        var list = new ListTag();
        for (int i = 0; i < 3; i++) {
            final int id = i;
            list.add(new CompoundTag() {
                @Override public CompoundTag copy() {
                    calls.add(id);
                    var copy = new CompoundTag(); copy.putInt("transformed", id); return copy;
                }
            });
        }
        var copy = list.copy();
        require(calls.equals(java.util.List.of(0, 1, 2)), "Tag.copy override/order/call count changed");
        for (int i = 0; i < 3; i++) require(copy.getCompound(i).getInt("transformed") == i, "overridden copy value lost");
        var values = new ListTag();
        values.add(net.minecraft.nbt.IntTag.valueOf(500));
        var valueCopy = values.copy();
        require(valueCopy.get(0) == values.get(0), "immutable value tags no longer shared as vanilla does");
        valueCopy.add(net.minecraft.nbt.IntTag.valueOf(600));
        require(values.size() == 1, "list backing was shared");

        var mapCtor = CompoundTag.class.getDeclaredConstructor(Map.class); mapCtor.setAccessible(true);
        var customMap = new java.util.LinkedHashMap<String, Tag>();
        var row = new CompoundTag(); row.putIntArray("values", new int[]{1, 2});
        customMap.put("child", row);
        var customCompound = mapCtor.newInstance(customMap);
        var listCtor = ListTag.class.getDeclaredConstructor(java.util.List.class, byte.class); listCtor.setAccessible(true);
        var customList = listCtor.newInstance(new java.util.LinkedList<>(java.util.List.of(customCompound)), Tag.TAG_COMPOUND);
        ListTag expected;
        ObjectReuseOptions.fastNbtCopies = false;
        try { expected = customList.copy(); } finally { ObjectReuseOptions.fastNbtCopies = true; }
        var actual = customList.copy();
        require(actual.equals(expected), "custom backing container changed copy behavior");
        var listField = ListTag.class.getDeclaredField("list"); listField.setAccessible(true);
        var mapField = CompoundTag.class.getDeclaredField("tags"); mapField.setAccessible(true);
        require(listField.get(actual).getClass() == listField.get(expected).getClass()
                && mapField.get(actual.getCompound(0)).getClass() == mapField.get(expected.getCompound(0)).getClass(),
                "custom-input fallback changed output container types");
        actual.getCompound(0).getCompound("child").getIntArray("values")[0] = 9;
        require(row.getIntArray("values")[0] == 1, "custom-input fallback shared mutable descendants");
        h.succeed();
    }
}
