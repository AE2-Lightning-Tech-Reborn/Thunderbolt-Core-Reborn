package com.moakiee.thunderbolt.compat.ae2lt;

import static org.junit.jupiter.api.Assertions.*;

import appeng.util.inv.AppEngInternalInventory;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class InventoryLoadSnapshotTest {
    static { MinecraftTestBootstrap.ensureInitialized(); }

    @Test
    void missingOrEmptySaveClearsPreviouslyInstalledItemsWithoutEvents() {
        for (boolean explicitList : new boolean[]{false, true}) {
            var inventory = new QuietInventory(1);
            inventory.setItemDirect(0, new ItemStack(Items.DIAMOND, 8));
            inventory.changes = 0;
            var source = new CompoundTag();
            if (explicitList) source.put("matrix", new ListTag());
            var before = source.copy();
            inventory.readFromNBT(InventoryLoadSnapshot.withEmptySlots(source, "matrix", 1), "matrix");
            assertTrue(inventory.isEmpty());
            assertEquals(0, inventory.changes, "load must not trigger saves or wake the grid");
            assertEquals(before, source, "load must not rewrite its source save");
        }
    }

    @Test
    void sparseSnapshotReplacesEverySlotAndPreservesSavedItemData() {
        var inventory = new QuietInventory(3);
        for (int i = 0; i < 3; i++) inventory.setItemDirect(i, new ItemStack(Items.DIAMOND));
        var saved = new ItemStack(Items.STONE, 7);
        saved.getOrCreateTag().putString("marker", "keep");
        var entry = saved.save(new CompoundTag());
        entry.putInt("Slot", 1);
        var list = new ListTag(); list.add(entry);
        var source = new CompoundTag(); source.put("filterInventory", list);
        var before = source.copy();
        inventory.changes = 0;
        inventory.readFromNBT(InventoryLoadSnapshot.withEmptySlots(source, "filterInventory", 3), "filterInventory");
        assertTrue(inventory.getStackInSlot(0).isEmpty());
        assertTrue(ItemStack.matches(saved, inventory.getStackInSlot(1)));
        assertTrue(inventory.getStackInSlot(2).isEmpty());
        assertEquals(0, inventory.changes);
        assertEquals(before, source);
    }

    @Test
    void duplicateAndOutOfBoundsEntriesKeepNativeReaderSemantics() {
        var inventory = new AppEngInternalInventory(1);
        var entries = new ListTag();
        for (int slot : new int[]{0, -1, 9, 0}) {
            var entry = new ItemStack(slot == 0 && !entries.isEmpty() ? Items.STONE : Items.DIAMOND)
                    .save(new CompoundTag());
            entry.putInt("Slot", slot); entries.add(entry);
        }
        var source = new CompoundTag(); source.put("matrix", entries);
        inventory.readFromNBT(InventoryLoadSnapshot.withEmptySlots(source, "matrix", 1), "matrix");
        assertTrue(inventory.getStackInSlot(0).is(Items.STONE));
    }

    private static final class QuietInventory extends AppEngInternalInventory {
        int changes;
        QuietInventory(int size) { super(size); }
        @Override protected void onContentsChanged(int slot) { changes++; }
    }
}
