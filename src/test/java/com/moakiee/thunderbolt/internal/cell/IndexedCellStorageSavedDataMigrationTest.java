package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;

import org.junit.jupiter.api.Test;

class IndexedCellStorageSavedDataMigrationTest {
    @Test
    void importsARealLegacyAe2ltInfiniteCellTagIntoTheNamespacedStore() {
        var validId = UUID.randomUUID();
        var serializedKey = new CompoundTag();
        serializedKey.putString("#c", "ae2:i");
        serializedKey.putString("id", "minecraft:stone");
        var keyEntry = new CompoundTag();
        keyEntry.put("key", serializedKey);
        var keys = new ListTag();
        keys.add(keyEntry);
        var legacyCell = new CompoundTag();
        legacyCell.put("keys", keys);
        legacyCell.put("lo", new LongArrayTag(new long[] { 123456789L }));
        legacyCell.put("hi", new LongArrayTag(new long[] { 0L }));
        legacyCell.putInt("totalTypes", 1);
        var legacyCells = new CompoundTag();
        legacyCells.put(validId.toString(), legacyCell);
        legacyCells.put("not-a-uuid", new CompoundTag());
        var legacyFile = new CompoundTag();
        legacyFile.put("cells", legacyCells);

        var decoded = IndexedCellStorageSavedData.decodeLegacyCells(legacyFile);
        assertEquals(1, decoded.size());
        assertEquals(123456789L, decoded.get(validId).getLongArray("lo").orElseThrow()[0]);

        var migrated = new IndexedCellStorageSavedData();
        migrated.importLegacyCells(decoded);
        var newFile = migrated.save(new CompoundTag(), null);
        var stores = newFile.getCompoundOrEmpty("Stores");
        assertTrue(stores.contains("ae2lt:infinite_cell"));
        var typeStore = stores.getCompoundOrEmpty("ae2lt:infinite_cell");
        assertTrue(typeStore.contains(validId.toString()));
        var migratedCell = typeStore.getCompoundOrEmpty(validId.toString());
        assertEquals(1, migratedCell.getIntOr("totalTypes", 0));
        assertEquals(123456789L, migratedCell.getLongArray("lo").orElseThrow()[0]);
        assertEquals("minecraft:stone", migratedCell.getListOrEmpty("keys")
                .getCompoundOrEmpty(0).getCompoundOrEmpty("key").getStringOr("id", ""));
        assertFalse(typeStore.contains("not-a-uuid"));
    }

    @Test
    void legacyDecodeCopiesPayloadInsteadOfAliasingTheInputTag() {
        var id = UUID.randomUUID();
        var payload = new CompoundTag();
        payload.putInt("value", 1);
        var cells = new CompoundTag();
        cells.put(id.toString(), payload);
        var legacy = new CompoundTag();
        legacy.put("cells", cells);

        var decoded = IndexedCellStorageSavedData.decodeLegacyCells(legacy);
        payload.putInt("value", 2);

        assertEquals(1, decoded.get(id).getIntOr("value", 0));
    }
}
