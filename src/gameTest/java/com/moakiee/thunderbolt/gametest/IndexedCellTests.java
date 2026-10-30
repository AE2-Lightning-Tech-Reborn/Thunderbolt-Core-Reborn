package com.moakiee.thunderbolt.gametest;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.UUID;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.moakiee.thunderbolt.core.storage.cell.ByteTracker;
import com.moakiee.thunderbolt.core.storage.cell.IIndexedStorageCellItem;
import com.moakiee.thunderbolt.core.storage.cell.IndexedCellStorageRegistry;
import com.moakiee.thunderbolt.core.storage.cell.IndexedCellStorageSavedData;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorage;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorageCellInventory;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("thunderbolt_nbt")
@PrefixGameTestTemplate(false)
public final class IndexedCellTests {
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void forgeFileSavePreservesNbtVariantsFluidsAndWideStock(GameTestHelper helper) throws Exception {
        var data = new IndexedCellStorageSavedData();
        var type = new ResourceLocation("thunderbolt_test", "file_cell");
        var id = UUID.randomUUID();
        var registries = helper.getLevel().registryAccess();
        var store = data.getOrCreateStorage(type, id, registries);
        store.enableArbitraryPrecision();
        var stack = new ItemStack(Items.IRON_INGOT);
        stack.getOrCreateTag().putInt("variant", 17);
        var item = AEItemKey.of(stack);
        var fluid = AEFluidKey.of(Fluids.WATER);
        var wide = BigInteger.ONE.shiftLeft(512).add(BigInteger.valueOf(37));
        store.insertExact(item, wide, Actionable.MODULATE);
        store.insert(fluid, Long.MAX_VALUE, Actionable.MODULATE);
        var expected = store.snapshotExact();
        var directory = Files.createTempDirectory("thunderbolt-cell-gametest-");
        var file = directory.resolve("cells.dat");
        try {
            data.markStorageDirty(type, id, store);
            data.save(file.toFile());
            helper.assertTrue(!data.isDirty() && Files.isRegularFile(file), "Forge save did not finish synchronously");
            var root = NbtIo.readCompressed(file.toFile());
            helper.assertTrue(root.contains("DataVersion"), "vanilla save envelope was lost");
            var restored = new IndexedStorage();
            restored.load(root.getCompound("data").getCompound("Stores").getCompound(type.toString())
                    .getCompound(id.toString()), registries);
            helper.assertTrue(expected.equals(restored.snapshotExact()), "Forge/AE2 codec roundtrip changed stock");
            var previous = data.save(new CompoundTag());
            store.extractExact(item, wide, Actionable.MODULATE);
            data.persistStorage(type, id, store, registries);
            var old = new IndexedStorage();
            old.load(previous.getCompound("Stores").getCompound(type.toString()).getCompound(id.toString()), registries);
            helper.assertTrue(expected.equals(old.snapshotExact()), "later persistence mutated an older world snapshot");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void worldBackedObserversResynchronizeAfterCacheReset(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var type = new ResourceLocation("thunderbolt_test", "observer_cell");
        var definition = new IIndexedStorageCellItem() {
            public ResourceLocation storageType(ItemStack stack) { return type; }
            public boolean supportsBigAmounts(ItemStack stack) { return true; }
            public double idleDrain(ItemStack stack) { return 0; }
            public ByteTracker createByteTracker(ItemStack stack, IndexedStorage storage) {
                var tracker = new ByteTracker(storage::getTotalTypes);
                tracker.configure(8, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
                return tracker;
            }
        };
        var stack = new ItemStack(Items.PAPER);
        var registries = helper.getLevel().registryAccess();
        var source = IActionSource.empty();
        var first = new IndexedStorageCellInventory(stack, definition, registries, null);
        var key = AEItemKey.of(Items.GOLD_INGOT);
        UUID id = null;
        try {
            helper.assertTrue(first.insert(key, Long.MAX_VALUE, Actionable.MODULATE, source) == Long.MAX_VALUE,
                    "initial world-backed insertion failed");
            id = stack.getTag().getUUID(definition.cellIdTag(stack));
            var before = IndexedCellStorageSavedData.get(server);
            IndexedCellStorageSavedData.clearServerCache();
            helper.assertTrue(IndexedCellStorageSavedData.getOrNull() == before, "cache reset replaced the world's saved data");
            var second = new IndexedStorageCellInventory(stack.copy(), definition, registries, null);
            helper.assertTrue(first.storage() == second.storage(), "observers did not resolve one world-backed engine");
            second.insert(key, 17, Actionable.MODULATE, source);
            helper.assertTrue(first.storage().getAmountExact(key).equals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(17))),
                    "shared engine lost a carry");
            helper.assertTrue(first.usedBytes() == second.usedBytes(), "observer byte cache stayed stale");
            first.persist();
            helper.assertTrue(IndexedCellStorageRegistry.get(server).getOrCreateStorage(type, id, registries) == first.storage(),
                    "public registry no longer resolves the cached engine");
        } finally {
            if (id != null) IndexedCellStorageRegistry.get(server).removeCell(type, id);
        }
        helper.succeed();
    }
}
