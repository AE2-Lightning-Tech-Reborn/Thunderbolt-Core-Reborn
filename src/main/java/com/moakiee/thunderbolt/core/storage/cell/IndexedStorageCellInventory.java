package com.moakiee.thunderbolt.core.storage.cell;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;


/** Shared AE2 {@link StorageCell} wrapper for every {@link IIndexedStorageCellItem}. */
public final class IndexedStorageCellInventory implements StorageCell, com.moakiee.thunderbolt.api.storage.BigMEStorage {
    private final ItemStack stack;
    private final IIndexedStorageCellItem definition;
    private final @Nullable HolderLookup.Provider explicitRegistries;
    private final @Nullable ISaveProvider saveProvider;
    private final ResourceLocation storageType;
    private final String cellIdTag;
    private final IndexedStorage storage;
    private final ByteTracker byteTracker;
    private final long[] rebuildDivBuf = new long[2];

    private @Nullable UUID cellId;
    private @Nullable IndexedCellStorageSavedData.StorageKey storageKey;
    private long lastSyncModCount = -1;
    private int lastWrittenTypes = -1;
    private long lastWrittenBytes = -1;

    public IndexedStorageCellInventory(
            ItemStack stack,
            IIndexedStorageCellItem definition,
            @Nullable HolderLookup.Provider registries,
            @Nullable ISaveProvider saveProvider) {
        this.stack = stack;
        this.definition = definition;
        this.explicitRegistries = registries;
        this.saveProvider = saveProvider;
        this.storageType = definition.storageType(stack);
        this.cellIdTag = definition.cellIdTag(stack);
        this.cellId = readCellId();

        var savedData = IndexedCellStorageSavedData.getOrNull();
        this.storage = cellId != null && savedData != null
                ? savedData.getOrCreateStorage(storageType, cellId, resolveRegistries())
                : new IndexedStorage();
        if (cellId != null) this.storageKey = new IndexedCellStorageSavedData.StorageKey(storageType, cellId);
        if (definition.supportsBigAmounts(stack)) storage.enableArbitraryPrecision();
        this.byteTracker = definition.createByteTracker(stack, storage);
        syncByteTracker();
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !definition.accepts(stack, key, source)) return 0;
        boolean unlimited = definition.supportsBigAmounts(stack);
        if (unlimited && mode == Actionable.SIMULATE) {
            // Only the engine's exact quantity boundary can reject an unlimited insertion.
            // Simulation must not resynchronize byte state or touch the item summary.
            return storage.insert(key, amount, mode);
        }
        ensureSync();
        int typesBefore = storage.getTotalTypes();
        long accepted = unlimited ? amount
                : Math.min(amount, byteTracker.computeMaxInsertable(key.getType(), !storage.containsKey(key)));
        if (accepted <= 0) return 0;
        accepted = storage.insert(key, accepted, Actionable.SIMULATE);
        if (accepted == 0 || mode == Actionable.SIMULATE) return accepted;

        boolean projectedTotal = storage.isTypeTotalSaturated(key.getType());
        accepted = storage.insert(key, accepted, Actionable.MODULATE);
        if (accepted <= 0) return 0;
        if (projectedTotal || storage.isTypeTotalSaturated(key.getType())) {
            syncByteTracker();
        } else {
            byteTracker.onInsert(key.getType(), accepted, storage.getTotalTypes() > typesBefore);
        }
        lastSyncModCount = storage.getModCount();
        syncSummary();
        markChanged();
        return accepted;
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0) return 0;
        if (mode == Actionable.SIMULATE) {
            return storage.extract(key, amount, Actionable.SIMULATE);
        }
        ensureSync();
        int typesBefore = storage.getTotalTypes();
        boolean projectedTotal = storage.isTypeTotalSaturated(key.getType());
        long extracted = storage.extract(key, amount, Actionable.MODULATE);
        if (extracted > 0) {
            boolean keyRemoved = storage.getTotalTypes() < typesBefore;
            if (projectedTotal) syncByteTracker();
            else byteTracker.onExtract(key.getType(), extracted, keyRemoved);
            lastSyncModCount = storage.getModCount();
            syncSummary();
            markChanged();
        }
        return extracted;
    }

    @Override public void getAvailableStacks(KeyCounter out) { storage.getAvailableStacks(out); }

    @Override
    public boolean isPreferredStorageFor(AEKey key, IActionSource source) {
        return definition.isPreferred(stack, key, storage, source);
    }

    @Override public Component getDescription() { return stack.getHoverName(); }

    @Override
    public CellState getStatus() {
        ensureSync();
        if (storage.getTotalTypes() == 0) return CellState.EMPTY;
        if (definition.supportsBigAmounts(stack)) return CellState.NOT_EMPTY;
        if (byteTracker.isFull()) return CellState.FULL;
        if (byteTracker.isTypeFull()) return CellState.TYPES_FULL;
        return CellState.NOT_EMPTY;
    }

    @Override public double getIdleDrain() { return definition.idleDrain(stack); }

    @Override
    public boolean canFitInsideCell() {
        ensureSync();
        return storage.getTotalTypes() == 0;
    }

    @Override
    public void persist() {
        ensureSync();
        syncSummary();
        var savedData = IndexedCellStorageSavedData.getOrNull();
        if (savedData == null) return;
        if (storage.getTotalTypes() == 0) {
            if (storage.needsPersist()) storage.persist(null, resolveRegistries());
            if (cellId != null) {
                savedData.removeCell(storageType, cellId);
                clearCellId();
                cellId = null;
                storageKey = null;
            }
            syncSummary();
            return;
        }
        if (!storage.needsPersist()) return;
        ensureCellId();
        savedData.persistStorage(storageType, cellId, storage, resolveRegistries());
        ensureSync();
        syncSummary();
    }

    @Override public java.math.BigInteger insertBig(AEKey key, java.math.BigInteger amount, Actionable mode, IActionSource source) {
        com.moakiee.thunderbolt.core.storage.big.BigAmounts.nonNegative(amount);
        if (!definition.supportsBigAmounts(stack)) return java.math.BigInteger.valueOf(insert(key,
                com.moakiee.thunderbolt.core.storage.big.BigAmounts.project(amount), mode, source));
        if (!definition.accepts(stack, key, source)) return java.math.BigInteger.ZERO;
        var accepted = storage.insertExact(key, amount, mode);
        if (mode == Actionable.MODULATE && accepted.signum() > 0) { syncByteTracker(); syncSummary(); markChanged(); }
        return accepted;
    }

    @Override public java.math.BigInteger extractBig(AEKey key, java.math.BigInteger amount, Actionable mode, IActionSource source) {
        com.moakiee.thunderbolt.core.storage.big.BigAmounts.nonNegative(amount);
        if (!definition.supportsBigAmounts(stack)) return java.math.BigInteger.valueOf(extract(key,
                com.moakiee.thunderbolt.core.storage.big.BigAmounts.project(amount), mode, source));
        var taken = storage.extractExact(key, amount, mode);
        if (mode == Actionable.MODULATE && taken.signum() > 0) { syncByteTracker(); syncSummary(); markChanged(); }
        return taken;
    }

    @Override public java.util.Map<AEKey, java.math.BigInteger> snapshotBig(IActionSource source) {
        if (definition.supportsBigAmounts(stack)) return storage.snapshotExact();
        // The non-opted-in bridge can extract only one long chunk per endpoint.
        // Do not promise its larger legacy ledger as atomically available to an exact plan.
        var result = new java.util.LinkedHashMap<AEKey, java.math.BigInteger>();
        storage.forEachCappedAmount((key, amount) -> {
            long available = extract(key, amount, Actionable.SIMULATE, source);
            if (available > 0) result.put(key, java.math.BigInteger.valueOf(available));
        });
        return result.isEmpty() ? java.util.Map.of() : java.util.Collections.unmodifiableMap(result);
    }

    public IndexedStorage storage() { return storage; }

    public long usedBytes() {
        ensureSync();
        return byteTracker.getUsedBytes();
    }

    private void markChanged() {
        if (storage.getTotalTypes() == 0) {
            persist();
        } else {
            var savedData = IndexedCellStorageSavedData.getOrNull();
            if (savedData != null) {
                ensureCellId();
                if (storageKey != null) savedData.markStorageDirty(storageKey, storage);
                else savedData.markStorageDirty(storageType, cellId, storage);
            }
        }
        if (saveProvider != null) saveProvider.saveChanges();
    }

    private void ensureSync() {
        if (storage.getModCount() != lastSyncModCount || !byteTracker.hasCurrentUnits()) syncByteTracker();
    }

    private void syncByteTracker() {
        byteTracker.rebuild(storage, rebuildDivBuf);
        lastSyncModCount = storage.getModCount();
    }

    private void syncSummary() {
        int types = storage.getTotalTypes();
        long bytes = byteTracker.getUsedBytes();
        if (types == lastWrittenTypes && bytes == lastWrittenBytes) return;
        lastWrittenTypes = types;
        lastWrittenBytes = bytes;
        definition.writeSummary(stack, new IndexedCellSummary(types, bytes));
    }

    private @Nullable UUID readCellId() {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(cellIdTag) ? tag.getUUID(cellIdTag) : null;
    }

    private void ensureCellId() {
        if (cellId != null) return;
        cellId = UUID.randomUUID();
        storageKey = new IndexedCellStorageSavedData.StorageKey(storageType, cellId);
        stack.getOrCreateTag().putUUID(cellIdTag, cellId);
    }

    private void clearCellId() {
        stack.getOrCreateTag().remove(cellIdTag);
    }

    private HolderLookup.Provider resolveRegistries() {
        if (explicitRegistries != null) return explicitRegistries;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) return server.registryAccess();
        throw new IllegalStateException("No registries available for indexed cell persistence");
    }
}
