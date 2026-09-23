package com.moakiee.thunderbolt.core.storage.cell;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import com.moakiee.thunderbolt.core.LegacySavedDataReader;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorage;

/** Internal world persistence behind the public indexed-cell registry. */
public final class IndexedCellStorageSavedData extends SavedData {
    private static final String DATA_NAME = "thunderbolt_indexed_cells";
    private static final String LEGACY_DATA_NAME = "ae2lt_infinite_cells";
    private static final String TAG_STORES = "Stores";
    private static final String TAG_LEGACY_MIGRATION_COMPLETE = "LegacyMigrationComplete";
    private static final Identifier LEGACY_AE2LT_TYPE =
            Identifier.fromNamespaceAndPath("ae2lt", "infinite_cell");

    private record StorageKey(Identifier type, UUID id) {}

    private final Map<StorageKey, CompoundTag> cells = new HashMap<>();
    private final transient Map<StorageKey, IndexedStorage> storageCache = new HashMap<>();
    private boolean legacyMigrationComplete;

    public static IndexedCellStorageSavedData get(MinecraftServer server) {
        var storage = server.overworld().getDataStorage();
        var registries = server.registryAccess();
        var type = new SavedDataType<>(
                Identifier.fromNamespaceAndPath("thunderbolt", "indexed_cells"),
                IndexedCellStorageSavedData::new,
                CompoundTag.CODEC.xmap(
                        tag -> load(tag, registries),
                        data -> data.save(new CompoundTag(), registries)));
        var data = storage.get(type);
        if (data == null) {
            var old = LegacySavedDataReader.read(server, DATA_NAME);
            data = old == null ? new IndexedCellStorageSavedData() : load(old, registries);
            storage.set(type, data);
        }
        data.migrateLegacyIfNeeded(server);
        return data;
    }

    public IndexedStorage getOrCreateStorage(
            Identifier type,
            UUID id,
            HolderLookup.Provider registries) {
        var key = new StorageKey(type, id);
        var cached = storageCache.get(key);
        if (cached != null) return cached;
        var storage = new IndexedStorage();
        var encoded = cells.get(key);
        if (encoded != null) storage.load(encoded, registries);
        storageCache.put(key, storage);
        return storage;
    }

    public void persistStorage(
            Identifier type,
            UUID id,
            IndexedStorage storage,
            HolderLookup.Provider registries) {
        if (storage == null) return;
        var key = new StorageKey(type, id);
        storageCache.put(key, storage);
        cells.put(key, storage.persist(cells.get(key), registries));
        setDirty();
    }

    public void markStorageDirty(Identifier type, UUID id, IndexedStorage storage) {
        if (type == null || id == null || storage == null) return;
        storageCache.put(new StorageKey(type, id), storage);
        setDirty();
    }

    public void removeCell(Identifier type, UUID id) {
        var key = new StorageKey(type, id);
        boolean changed = cells.remove(key) != null;
        storageCache.remove(key);
        if (changed) setDirty();
    }

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (var entry : storageCache.entrySet()) {
            if (entry.getValue().needsPersist()) {
                cells.put(entry.getKey(), entry.getValue().persist(cells.get(entry.getKey()), registries));
            }
        }
        var storesTag = new CompoundTag();
        for (var entry : cells.entrySet()) {
            var typeTag = storesTag.getCompoundOrEmpty(entry.getKey().type().toString());
            typeTag.put(entry.getKey().id().toString(), entry.getValue());
            storesTag.put(entry.getKey().type().toString(), typeTag);
        }
        tag.put(TAG_STORES, storesTag);
        tag.putBoolean(TAG_LEGACY_MIGRATION_COMPLETE, legacyMigrationComplete);
        return tag;
    }

    private void migrateLegacyIfNeeded(MinecraftServer server) {
        if (legacyMigrationComplete) return;
        var legacy = LegacyInfiniteCellSavedData.get(server);
        importLegacyCells(legacy.cells);
        legacyMigrationComplete = true;
        setDirty();
    }

    void importLegacyCells(Map<UUID, CompoundTag> legacyCells) {
        for (var entry : legacyCells.entrySet()) {
            cells.putIfAbsent(
                    new StorageKey(LEGACY_AE2LT_TYPE, entry.getKey()),
                    entry.getValue().copy());
        }
    }

    static Map<UUID, CompoundTag> decodeLegacyCells(CompoundTag tag) {
        var result = new HashMap<UUID, CompoundTag>();
        var cellsTag = tag.getCompoundOrEmpty("cells");
        for (var idString : cellsTag.keySet()) {
            try {
                result.put(UUID.fromString(idString), cellsTag.getCompoundOrEmpty(idString).copy());
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    private static IndexedCellStorageSavedData load(
            CompoundTag tag, HolderLookup.Provider registries) {
        var data = new IndexedCellStorageSavedData();
        data.legacyMigrationComplete = tag.getBooleanOr(TAG_LEGACY_MIGRATION_COMPLETE, false);
        var storesTag = tag.getCompoundOrEmpty(TAG_STORES);
        for (var typeString : storesTag.keySet()) {
            Identifier type;
            try {
                type = Identifier.parse(typeString);
            } catch (RuntimeException ignored) {
                continue;
            }
            var typeTag = storesTag.getCompoundOrEmpty(typeString);
            for (var idString : typeTag.keySet()) {
                try {
                    data.cells.put(
                            new StorageKey(type, UUID.fromString(idString)),
                            typeTag.getCompoundOrEmpty(idString));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return data;
    }

    /** Read-only loader for the original AE2LT file. It is deliberately never marked dirty. */
    private static final class LegacyInfiniteCellSavedData {
        private final Map<UUID, CompoundTag> cells = new HashMap<>();

        private static LegacyInfiniteCellSavedData get(MinecraftServer server) {
            var data = new LegacyInfiniteCellSavedData();
            var old = LegacySavedDataReader.read(server, LEGACY_DATA_NAME);
            if (old != null) data.cells.putAll(decodeLegacyCells(old));
            return data;
        }
    }
}
