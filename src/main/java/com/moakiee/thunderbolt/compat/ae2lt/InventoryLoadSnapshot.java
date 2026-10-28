package com.moakiee.thunderbolt.compat.ae2lt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Makes a sparse AE2 inventory save replace every slot without firing inventory events. */
public final class InventoryLoadSnapshot {
    private InventoryLoadSnapshot() {
    }

    public static CompoundTag withEmptySlots(CompoundTag source, String key, int slots) {
        var snapshot = source.copy();
        var entries = new ListTag();
        for (int slot = 0; slot < slots; slot++) {
            var empty = new CompoundTag();
            empty.putInt("Slot", slot);
            empty.putString("id", "minecraft:air");
            empty.putByte("Count", (byte) 0);
            entries.add(empty);
        }
        // Native readFromNBT applies entries in order; saved entries replace the empty defaults.
        entries.addAll(snapshot.getList(key, Tag.TAG_COMPOUND));
        snapshot.put(key, entries);
        return snapshot;
    }
}
