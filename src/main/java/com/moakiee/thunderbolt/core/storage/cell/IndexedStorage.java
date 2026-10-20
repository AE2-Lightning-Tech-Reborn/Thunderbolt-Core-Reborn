package com.moakiee.thunderbolt.core.storage.cell;

import java.util.Arrays;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import net.minecraft.core.HolderLookup;
import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;


/**
 * Array-indexed storage engine for the infinite cell. Live state, including query caches,
 * is confined to the server thread; background I/O may only read detached snapshots.
 * <p>
 * Each {@link AEKey} is assigned a stable integer id. The id doubles as
 * the position in the persisted {@link ListTag} / {@link LongArrayTag},
 * so incremental persist only touches changed positions.
 * <p>
 * A dirty queue ({@code dirtyQueue}) records which ids changed since the
 * last persist, giving O(changed) persist with no bitset scanning.
 * A per-id boolean {@code isStructDirty} distinguishes key add/remove
 * (needs key-tag write) from amount-only changes (just long[] writes).
 * <p>
 * Per-{@link AEKeyType} aggregates live in identity-indexed slots: a transfer
 * resolves its type slot once, then all bookkeeping is primitive array access
 * instead of repeated hash lookups.
 * <p>
 * When the number of free (hole) slots exceeds {@code totalTypes * COMPACT_THRESHOLD},
 * a deferred compaction is scheduled and executed at the next {@link #persist},
 * reassigning contiguous ids and forcing a full rewrite.
 */
public final class IndexedStorage {
    private static final java.math.BigInteger MASK_63 = java.math.BigInteger.valueOf(Long.MAX_VALUE);
    private static final java.math.BigInteger MAX_126 = java.math.BigInteger.ONE.shiftLeft(126).subtract(java.math.BigInteger.ONE);
    private static final java.math.BigInteger MAX_EXACT = java.math.BigInteger.ONE
            .shiftLeft(com.moakiee.thunderbolt.core.storage.big.BigAmounts.MAX_BITS).subtract(java.math.BigInteger.ONE);
    private boolean arbitraryPrecision;
    // Set once any type could reach the 126-bit projection ceiling (or holds a wide value).
    // Ordinary transfers can then skip the per-type probe entirely.
    private boolean exactPathPossible;
    // Only quantities beyond the legacy 126-bit encoding need an additional serialized value.
    private final java.util.Map<AEKey, java.math.BigInteger> wideAmounts = new java.util.HashMap<>();
    // Sparse overflow ledger: ordinary totals live only in the primitive aggregate arrays.
    private final java.util.Map<AEKeyType, java.math.BigInteger> exactTypeTotals = new java.util.HashMap<>();

    public void enableArbitraryPrecision() {
        if (arbitraryPrecision) return;
        arbitraryPrecision = true;
        rebuildExactTotals();
    }

    public java.math.BigInteger getAmountExact(AEKey key) {
        int id = resolveId(key);
        if (id < 0) return java.math.BigInteger.ZERO;
        var wide = wideAmounts.get(key);
        return wide != null ? wide : exactAmount(hi[id], lo[id]);
    }

    private static java.math.BigInteger exactAmount(long hi, long lo) {
        return hi == 0 ? java.math.BigInteger.valueOf(lo)
                : java.math.BigInteger.valueOf(hi).shiftLeft(63).add(java.math.BigInteger.valueOf(lo));
    }

    private java.math.BigInteger typeTotalExact(AEKeyType type) {
        var wide = exactTypeTotals.get(type);
        if (wide != null) return wide;
        int slot = typeToSlot.getInt(type);
        return slot < 0 ? java.math.BigInteger.ZERO : exactAmount(slotHi[slot], slotLo[slot]);
    }

    public java.util.Map<AEKey, java.math.BigInteger> snapshotExact() {
        if (totalTypes == 0) return java.util.Map.of();
        // Clone the existing index through fastutil's public API, without rehashing NBT keys.
        // Stable IDs address detached quantities; sparse inventories use a compact map instead.
        if (totalTypes >= 256 && nextId <= (long) totalTypes * 2) {
            var amounts = new java.math.BigInteger[nextId];
            for (int id = 0; id < nextId; id++) {
                var key = idToKey[id];
                if (key == null) continue;
                var wide = wideAmounts.get(key);
                amounts[id] = wide != null ? wide : exactAmount(hi[id], lo[id]);
            }
            return java.util.Collections.unmodifiableMap(new ExactCellSnapshot(keyToId, amounts));
        }
        var result = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<AEKey, java.math.BigInteger>(
                totalTypes);
        for (int id = 0; id < nextId; id++) {
            var key = idToKey[id];
            if (key == null) continue;
            var wide = wideAmounts.get(key);
            result.put(key, wide != null ? wide : exactAmount(hi[id], lo[id]));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public java.math.BigInteger insertExact(AEKey key, java.math.BigInteger amount, Actionable mode) {
        com.moakiee.thunderbolt.core.storage.big.BigAmounts.nonNegative(amount);
        if (!arbitraryPrecision) throw new IllegalStateException("Exact insertion requires an opted-in cell");
        var current = getAmountExact(key);
        var next = current.add(amount).min(MAX_EXACT);
        var accepted = next.subtract(current);
        if (accepted.signum() > 0 && mode == Actionable.MODULATE) setAmountExact(key, current, next);
        return accepted;
    }

    public java.math.BigInteger extractExact(AEKey key, java.math.BigInteger amount, Actionable mode) {
        com.moakiee.thunderbolt.core.storage.big.BigAmounts.nonNegative(amount);
        if (!arbitraryPrecision) throw new IllegalStateException("Exact extraction requires an opted-in cell");
        var current = getAmountExact(key);
        var taken = current.min(amount);
        if (taken.signum() > 0 && mode == Actionable.MODULATE) setAmountExact(key, current, current.subtract(taken));
        return taken;
    }

    private void setAmountExact(AEKey key, java.math.BigInteger before, java.math.BigInteger amount) {
        AEKeyType kt = key.getType();
        var total = typeTotalExact(kt).add(amount.subtract(before));
        int id = resolveId(key);
        int slot;
        if (id < 0) {
            id = allocateId(key);
            totalTypes++;
            slot = typeSlot(kt);
            slotCount[slot]++;
            slotOfId[id] = slot;
        } else {
            slot = slotOfId[id];
        }
        if (amount.signum() == 0) {
            wideAmounts.remove(key);
            recycleId(id, key);
            totalTypes--;
            if (--slotCount[slot] <= 0) {
                slotCount[slot] = 0;
                slotLo[slot] = 0;
                slotHi[slot] = 0;
            }
            if (freeCount > Math.max(totalTypes, 1) * COMPACT_THRESHOLD) needsCompact = true;
        } else {
            var projection = amount.min(MAX_126);
            lo[id] = projection.and(MASK_63).longValueExact(); hi[id] = projection.shiftRight(63).longValueExact();
            if (amount.compareTo(MAX_126) > 0) wideAmounts.put(key, amount); else wideAmounts.remove(key);
            enqueueDirty(id);
        }
        projectTypeTotal(kt, total);
        needsPersist = true; modCount++;
    }

    private void projectTypeTotal(AEKeyType type, java.math.BigInteger amount) {
        int slot = typeToSlot.getInt(type);
        if (amount.signum() == 0) {
            exactTypeTotals.remove(type);
            if (slot >= 0) {
                slotLo[slot] = 0;
                slotHi[slot] = 0;
                slotExact[slot] = false;
            }
            return;
        }
        boolean exact = amount.compareTo(MAX_126) > 0;
        if (exact) exactTypeTotals.put(type, amount);
        else exactTypeTotals.remove(type);
        if (slot < 0) slot = typeSlot(type);
        slotExact[slot] = exact;
        var n = amount.min(MAX_126);
        slotLo[slot] = n.and(MASK_63).longValueExact();
        slotHi[slot] = n.shiftRight(63).longValueExact();
        if (exact || slotHi[slot] == Long.MAX_VALUE) exactPathPossible = true;
    }

    private void rebuildExactTotals() {
        exactTypeTotals.clear();
        exactPathPossible = !wideAmounts.isEmpty();
        for (int slot = 0; slot < usedTypeSlots; slot++) {
            slotLo[slot] = 0;
            slotHi[slot] = 0;
            slotExact[slot] = false;
        }
        for (int id = 0; id < nextId; id++) {
            var key = idToKey[id];
            if (key == null) continue;
            var type = key.getType();
            var wide = wideAmounts.get(key);
            int slot = typeSlot(type);
            if (wide != null || exactTypeTotals.containsKey(type)) {
                projectTypeTotal(type, typeTotalExact(type).add(
                        wide != null ? wide : exactAmount(hi[id], lo[id])));
                continue;
            }
            long beforeLo = slotLo[slot];
            long beforeHi = slotHi[slot];
            long sumLo = beforeLo + lo[id];
            long sumHi = beforeHi + hi[id];
            if (sumLo < 0) { sumLo &= Long.MAX_VALUE; sumHi++; }
            if (sumHi < 0) {
                projectTypeTotal(type, exactAmount(beforeHi, beforeLo).add(exactAmount(hi[id], lo[id])));
            } else {
                slotLo[slot] = sumLo;
                if (sumHi != beforeHi) slotHi[slot] = sumHi;
                if (sumHi == Long.MAX_VALUE) exactPathPossible = true;
            }
        }
    }

    private void persistWide(CompoundTag root) {
        if (!arbitraryPrecision) {
            root.remove("arbitraryPrecision");
            root.remove("bigAmounts");
            return;
        }
        root.putBoolean("arbitraryPrecision", true);
        var wide = new CompoundTag();
        wideAmounts.forEach((key, n) -> wide.putByteArray(Integer.toString(keyToId.getInt(key)), n.toByteArray()));
        root.put("bigAmounts", wide);
    }


    private static final int INITIAL_CAPACITY = 256;
    private static final int COMPACT_THRESHOLD = 2;

    // Key registry — id is stable and doubles as ListTag position
    private final Object2IntOpenHashMap<AEKey> keyToId = new Object2IntOpenHashMap<>();
    private @Nullable AEKey lastLookupKey;
    private int lastLookupId = -1;
    private AEKey[] idToKey;
    private int nextId;
    private int[] freeIds;
    private int freeCount;

    // Amount arrays (63+63 bit)
    private long[] lo;
    private long[] hi;
    // Type slot for each key id: a transfer reaches its aggregate without a second hash lookup.
    private int[] slotOfId;

    // Cached key serialization — set once per key lifetime, avoids repeated toTagGeneric
    private CompoundTag[] serializedKey;
    // The {"key": tag} wrapper is immutable for a key's lifetime; persist reuses one instance.
    private CompoundTag[] keyEntries;
    private final CellNbtSnapshot writeSnapshot = new CellNbtSnapshot();

    /** Called on the server thread; only the returned detached payload may reach the I/O worker. */
    CompoundTag snapshotForWrite(CompoundTag encoded) {
        return writeSnapshot.copy(encoded);
    }

    // Dirty tracking: queue + per-id flags (replaces bitset scanning)
    private int[] dirtyQueue;
    private int dirtyCount;
    private boolean[] inQueue;
    private boolean[] isStructDirty;

    private int totalTypes;
    private boolean needsPersist;
    private boolean needsCompact;
    private long modCount;

    // Per-AEKeyType aggregates — identity-indexed slots, maintained incrementally.
    private final Reference2IntOpenHashMap<AEKeyType> typeToSlot = new Reference2IntOpenHashMap<>();
    private AEKeyType[] slotTypes = new AEKeyType[4];
    private long[] slotLo = new long[4];
    private long[] slotHi = new long[4];
    private int[] slotCount = new int[4];
    // A saturated or wide type total needs the exact path; keep that decision in the slot.
    private boolean[] slotExact = new boolean[4];
    private int usedTypeSlots;

    public IndexedStorage() {
        keyToId.defaultReturnValue(-1);
        typeToSlot.defaultReturnValue(-1);
        initArrays(INITIAL_CAPACITY);
    }

    public int getTotalTypes() { return totalTypes; }

    private int resolveId(AEKey key) {
        if (key == lastLookupKey) return lastLookupId;
        int id = keyToId.getInt(key);
        lastLookupKey = key;
        lastLookupId = id;
        return id;
    }

    private void invalidateLookup() {
        lastLookupKey = null;
        lastLookupId = -1;
    }

    public boolean needsPersist() { return needsPersist; }

    public long getModCount() { return modCount; }

    /** Cold-path copy of the per-type low words for external inspection and tests. */
    public Object2LongOpenHashMap<AEKeyType> getTypeAmountLo() {
        var result = new Object2LongOpenHashMap<AEKeyType>(Math.max(usedTypeSlots, 1));
        for (int slot = 0; slot < usedTypeSlots; slot++) {
            if (slotCount[slot] > 0) result.put(slotTypes[slot], slotLo[slot]);
        }
        return result;
    }

    /** Cold-path copy of the per-type high words for external inspection and tests. */
    public Object2LongOpenHashMap<AEKeyType> getTypeAmountHi() {
        var result = new Object2LongOpenHashMap<AEKeyType>(Math.max(usedTypeSlots, 1));
        for (int slot = 0; slot < usedTypeSlots; slot++) {
            if (slotCount[slot] > 0) result.put(slotTypes[slot], slotHi[slot]);
        }
        return result;
    }

    /** Cold-path copy of the per-type key counts for external inspection and tests. */
    public Object2IntOpenHashMap<AEKeyType> getTypeCounts() {
        var result = new Object2IntOpenHashMap<AEKeyType>(Math.max(usedTypeSlots, 1));
        for (int slot = 0; slot < usedTypeSlots; slot++) {
            if (slotCount[slot] > 0) result.put(slotTypes[slot], slotCount[slot]);
        }
        return result;
    }

    /** True when the 126-bit projection of this type can no longer track the exact total. */
    public boolean isTypeTotalSaturated(AEKeyType type) {
        if (arbitraryPrecision && !exactPathPossible) return false;
        int slot = typeToSlot.getInt(type);
        return slot >= 0 && slotHi[slot] == Long.MAX_VALUE;
    }

    int usedTypeSlots() { return usedTypeSlots; }

    AEKeyType typeAt(int slot) { return slotTypes[slot]; }

    long typeLoAt(int slot) { return slotLo[slot]; }

    long typeHiAt(int slot) { return slotHi[slot]; }

    int typeCountAt(int slot) { return slotCount[slot]; }

    private int typeSlot(AEKeyType type) {
        int slot = typeToSlot.getInt(type);
        if (slot >= 0) return slot;
        if (usedTypeSlots == slotTypes.length) {
            int capacity = slotTypes.length * 2;
            slotTypes = Arrays.copyOf(slotTypes, capacity);
            slotLo = Arrays.copyOf(slotLo, capacity);
            slotHi = Arrays.copyOf(slotHi, capacity);
            slotCount = Arrays.copyOf(slotCount, capacity);
            slotExact = Arrays.copyOf(slotExact, capacity);
        }
        slot = usedTypeSlots++;
        slotTypes[slot] = type;
        slotLo[slot] = 0;
        slotHi[slot] = 0;
        slotCount[slot] = 0;
        slotExact[slot] = false;
        typeToSlot.put(type, slot);
        return slot;
    }

    private void resetTypeSlots() {
        for (int slot = 0; slot < usedTypeSlots; slot++) {
            slotTypes[slot] = null;
            slotLo[slot] = 0;
            slotHi[slot] = 0;
            slotCount[slot] = 0;
            slotExact[slot] = false;
        }
        usedTypeSlots = 0;
        typeToSlot.clear();
    }

    private void enqueueDirty(int id) {
        if (!inQueue[id]) {
            inQueue[id] = true;
            if (dirtyCount == dirtyQueue.length) {
                dirtyQueue = Arrays.copyOf(dirtyQueue, dirtyQueue.length * 2);
            }
            dirtyQueue[dirtyCount++] = id;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  insert
    // ══════════════════════════════════════════════════════════════════════

    public long insert(AEKey key, long amount, Actionable mode) {
        if (amount <= 0) return 0;
        if (mode == Actionable.SIMULATE && arbitraryPrecision && !exactPathPossible) return amount;
        int id = resolveId(key);
        int slot = id < 0 ? -1 : slotOfId[id];
        // Legacy keys remain bounded to their two persisted 63-bit limbs.
        if (!arbitraryPrecision && id >= 0 && hi[id] == Long.MAX_VALUE) {
            amount = Math.min(amount, Long.MAX_VALUE - lo[id]);
            if (amount == 0) return 0;
        }
        // Only genuine 126-bit overflow needs BigInteger arithmetic. A normal AE transfer
        // must not allocate arbitrary-precision quantities merely because the cell is unlimited.
        // Until some type reaches the projection ceiling this check can be skipped entirely,
        // which also keeps simulated unlimited inserts to a single lookup.
        if (arbitraryPrecision && exactPathPossible) {
            // A key that is new to this cell has no id yet; resolve its type slot directly.
            int probe = slot >= 0 ? slot : typeToSlot.getInt(key.getType());
            if (probe >= 0 && (slotExact[probe]
                    || (slotHi[probe] == Long.MAX_VALUE && slotLo[probe] > Long.MAX_VALUE - amount))) {
                return insertExact(key, java.math.BigInteger.valueOf(amount), mode).longValueExact();
            }
            slot = probe;
        }
        if (mode == Actionable.SIMULATE) return amount;
        boolean isNewKey = (id == -1);
        if (isNewKey) {
            id = allocateId(key);
            totalTypes++;
        } else {
            enqueueDirty(id);
        }

        long newLo = lo[id] + amount;
        if (newLo < 0) { newLo &= Long.MAX_VALUE; hi[id]++; }
        lo[id] = newLo;

        if (isNewKey) {
            if (slot < 0) slot = typeSlot(key.getType());
            slotCount[slot]++;
            slotOfId[id] = slot;
        }
        if (slotExact[slot]) {
            // Legacy cells can overflow a type total while each key still fits 126 bits.
            projectTypeTotal(slotTypes[slot], typeTotalExact(slotTypes[slot])
                    .add(java.math.BigInteger.valueOf(amount)));
        } else {
            long sumLo = slotLo[slot] + amount;
            if (sumLo < 0) {
                sumLo &= Long.MAX_VALUE;
                if (slotHi[slot] == Long.MAX_VALUE) {
                    projectTypeTotal(slotTypes[slot], exactAmount(slotHi[slot], slotLo[slot])
                            .add(java.math.BigInteger.valueOf(amount)));
                } else {
                    if (++slotHi[slot] == Long.MAX_VALUE) exactPathPossible = true;
                    slotLo[slot] = sumLo;
                }
            } else {
                slotLo[slot] = sumLo;
            }
        }

        needsPersist = true;
        modCount++;
        return amount;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  extract
    // ══════════════════════════════════════════════════════════════════════

    public long extract(AEKey key, long amount, Actionable mode) {
        if (amount <= 0) return 0;

        int id = resolveId(key);
        if (id == -1) return 0;
        int slot = slotOfId[id];
        if (arbitraryPrecision && exactPathPossible && slotExact[slot]) {
            return extractExact(key, java.math.BigInteger.valueOf(amount), mode).longValueExact();
        }

        long curLo = lo[id], curHi = hi[id];
        long taken = DualLong126.geq(curHi, curLo, amount) ? amount : curLo;

        if (mode == Actionable.SIMULATE) return taken;

        long newLo = curLo - taken;
        long newHi = curHi;
        if (newLo < 0) { newLo &= Long.MAX_VALUE; newHi--; }

        boolean keyRemoved = (newLo == 0 && newHi == 0);
        if (keyRemoved) {
            recycleId(id, key);
            totalTypes--;
            if (freeCount > Math.max(totalTypes, 1) * COMPACT_THRESHOLD) {
                needsCompact = true;
            }
        } else {
            lo[id] = newLo;
            hi[id] = newHi;
            enqueueDirty(id);
        }

        if (slotExact[slot]) {
            projectTypeTotal(slotTypes[slot], typeTotalExact(slotTypes[slot])
                    .subtract(java.math.BigInteger.valueOf(taken)));
        } else {
            long sumLo = slotLo[slot] - taken;
            long sumHi = slotHi[slot];
            if (sumLo < 0) { sumLo &= Long.MAX_VALUE; sumHi--; }
            slotLo[slot] = sumLo;
            slotHi[slot] = sumHi;
        }
        if (keyRemoved && --slotCount[slot] <= 0) {
            slotCount[slot] = 0;
            slotLo[slot] = 0;
            slotHi[slot] = 0;
            slotExact[slot] = false;
            exactTypeTotals.remove(slotTypes[slot]);
        }

        needsPersist = true;
        modCount++;
        return taken;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Queries
    // ══════════════════════════════════════════════════════════════════════

    @FunctionalInterface
    interface CappedAmountConsumer {
        void accept(AEKey key, long amount);
    }

    void forEachCappedAmount(CappedAmountConsumer consumer) {
        for (int id = 0; id < nextId; id++) {
            var key = idToKey[id];
            if (key != null) consumer.accept(key, DualLong126.cap(hi[id], lo[id]));
        }
    }

    public void getAvailableStacks(KeyCounter out) {
        // A fresh counter is the common network-enumeration case: skip the read-modify-write
        // lookup pair and append directly. Keys are unique, so one decision covers the loop.
        boolean empty = out.isEmpty();
        for (int id = 0; id < nextId; id++) {
            var key = idToKey[id];
            if (key == null) continue;
            long amount = DualLong126.cap(hi[id], lo[id]);
            if (empty) {
                out.add(key, amount);
            } else {
                out.set(key, com.google.common.math.LongMath.saturatedAdd(out.get(key), amount));
            }
        }
    }

    public boolean containsKey(AEKey key) {
        return resolveId(key) >= 0;
    }

    public long getAmount(AEKey key) {
        int id = resolveId(key);
        if (id == -1) return 0;
        return DualLong126.cap(hi[id], lo[id]);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Persist — queue-driven, O(changed). Split layout:
    //  ListTag<CompoundTag> for keys, LongArrayTag for lo/hi.
    // ══════════════════════════════════════════════════════════════════════

    public CompoundTag persist(@Nullable CompoundTag lastRoot, HolderLookup.Provider registries) {
        // AE2 15 encodes keys without a registry context; reuse a stateless serializer.
        return persist(lastRoot, DEFAULT_SERIALIZER, registries);
    }

    public CompoundTag persist(@Nullable CompoundTag lastRoot, KeySerializer keySerializer, HolderLookup.Provider registries) {
        if (needsCompact) {
            compact();
            lastRoot = null;
        }
        if (lastRoot == null) {
            return persistFull(keySerializer, registries);
        }

        ListTag keys = lastRoot.getList("keys", Tag.TAG_COMPOUND);
        CompoundTag wide = arbitraryPrecision ? lastRoot.getCompound("bigAmounts") : null;
        long[] pLo = lastRoot.getLongArray("lo");
        long[] pHi = lastRoot.getLongArray("hi");

        int tagLen = alignPow2(nextId);
        if (pLo.length < nextId || pHi.length < nextId) {
            pLo = Arrays.copyOf(pLo, tagLen);
            pHi = Arrays.copyOf(pHi, tagLen);
            lastRoot.put("lo", new LongArrayTag(pLo));
            lastRoot.put("hi", new LongArrayTag(pHi));
        }
        for (int i = 0; i < dirtyCount; i++) {
            int id = dirtyQueue[i];
            inQueue[id] = false;

            if (isStructDirty[id]) {
                writeSnapshot.keysChanged();
                isStructDirty[id] = false;
                if (idToKey[id] != null) {
                    if (serializedKey[id] == null) {
                        serializedKey[id] = keySerializer.toTag(idToKey[id], registries);
                    }
                    putKeyEntry(keys, id, keyEntry(id));
                } else {
                    putKeyEntry(keys, id, new CompoundTag());
                }
            }

            pLo[id] = lo[id];
            if (pHi[id] != hi[id]) {
                writeSnapshot.highChanged();
            }
            pHi[id] = hi[id];
            if (wide != null && (!wideAmounts.isEmpty() || !wide.isEmpty())) {
                var amount = idToKey[id] == null ? null : wideAmounts.get(idToKey[id]);
                String index = Integer.toString(id);
                if (amount != null) wide.putByteArray(index, amount.toByteArray());
                else wide.remove(index);
            }
        }
        dirtyCount = 0;

        lastRoot.putInt("totalTypes", totalTypes);
        needsPersist = false;
        if (wide != null) {
            lastRoot.putBoolean("arbitraryPrecision", true);
            lastRoot.put("bigAmounts", wide);
        }
        return lastRoot;
    }

    private static void putKeyEntry(ListTag keys, int id, CompoundTag entry) {
        // Newly allocated ids enter the dirty queue in ascending order, so append directly.
        // Fill gaps defensively when the supplied list is shorter than a dirty id.
        if (id == keys.size()) {
            keys.add(entry);
        } else {
            while (keys.size() <= id) keys.add(new CompoundTag());
            keys.set(id, entry);
        }
    }

    /**
     * The {@code {"key": ...}} list entry for one id. Its contents never change while the id
     * holds the key, so older save snapshots can keep sharing it. Persistence replaces the
     * list position and the whole key tag instead of mutating either in place.
     */
    private CompoundTag keyEntry(int id) {
        var entry = keyEntries[id];
        if (entry == null) {
            entry = new CompoundTag();
            entry.put("key", serializedKey[id]);
            keyEntries[id] = entry;
        }
        return entry;
    }

    private static final KeySerializer DEFAULT_SERIALIZER = (key, registries) -> key.toTagGeneric();

    private CompoundTag persistFull(KeySerializer keySerializer, HolderLookup.Provider registries) {
        // Clear dirty state — everything is being written
        for (int i = 0; i < dirtyCount; i++) {
            int id = dirtyQueue[i];
            inQueue[id] = false;
            isStructDirty[id] = false;
        }
        dirtyCount = 0;

        int tagLen = alignPow2(nextId);
        ListTag keys = new ListTag();
        long[] pLo = new long[tagLen];
        long[] pHi = new long[tagLen];

        for (int id = 0; id < nextId; id++) {
            if (idToKey[id] != null) {
                if (serializedKey[id] == null) {
                    serializedKey[id] = keySerializer.toTag(idToKey[id], registries);
                }
                keys.add(keyEntry(id));
                pLo[id] = lo[id];
                pHi[id] = hi[id];
            } else {
                keys.add(new CompoundTag());
            }
        }

        CompoundTag root = new CompoundTag();
        root.put("keys", keys);
        root.put("lo", new LongArrayTag(pLo));
        root.put("hi", new LongArrayTag(pHi));
        root.putInt("totalTypes", totalTypes);
        needsPersist = false;
        persistWide(root);
        return root;
    }

    private void compact() {
        invalidateLookup();
        int newCap = alignPow2(Math.max(totalTypes, 1));
        int newNext = 0;

        AEKey[] nKey = new AEKey[newCap];
        long[] nLo = new long[newCap];
        long[] nHi = new long[newCap];
        CompoundTag[] nSer = new CompoundTag[newCap];
        CompoundTag[] nEntry = new CompoundTag[newCap];
        int[] nSlot = new int[newCap];

        for (int old = 0; old < nextId; old++) {
            if (idToKey[old] == null) continue;
            int nid = newNext++;
            nKey[nid] = idToKey[old];
            nLo[nid] = lo[old];
            nHi[nid] = hi[old];
            nSer[nid] = serializedKey[old];
            nEntry[nid] = keyEntries[old];
            nSlot[nid] = slotOfId[old];
            keyToId.put(idToKey[old], nid);
        }

        idToKey = nKey;
        lo = nLo;
        hi = nHi;
        serializedKey = nSer;
        keyEntries = nEntry;
        slotOfId = nSlot;
        inQueue = new boolean[newCap];
        isStructDirty = new boolean[newCap];
        dirtyQueue = new int[Math.max(64, newNext)];
        dirtyCount = 0;
        nextId = newNext;
        freeIds = new int[64];
        freeCount = 0;
        needsCompact = false;
    }

    private static int alignPow2(int n) {
        if (n <= INITIAL_CAPACITY) return INITIAL_CAPACITY;
        return Integer.highestOneBit(n - 1) << 1;
    }

    @FunctionalInterface
    public interface KeySerializer {
        CompoundTag toTag(AEKey key, HolderLookup.Provider registries);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Load
    // ══════════════════════════════════════════════════════════════════════

    @FunctionalInterface
    public interface KeyDeserializer {
        @Nullable AEKey fromTag(CompoundTag tag, HolderLookup.Provider registries);
    }
    //  Load
    public void load(CompoundTag root, HolderLookup.Provider registries) {
        load(root, (tag, reg) -> AEKey.fromTagGeneric(tag), registries);
    }

    public void load(
            CompoundTag root,
            KeyDeserializer keyDeserializer,
            HolderLookup.Provider registries) {
        java.util.Objects.requireNonNull(root, "root");
        java.util.Objects.requireNonNull(keyDeserializer, "keyDeserializer");
        invalidateLookup();
        writeSnapshot.reset();
        exactPathPossible = false;
        wideAmounts.clear();
        exactTypeTotals.clear();
        arbitraryPrecision = root.getBoolean("arbitraryPrecision");
        keyToId.clear();
        Arrays.fill(idToKey, null);
        Arrays.fill(lo, 0L);
        Arrays.fill(hi, 0L);
        Arrays.fill(serializedKey, null);
        Arrays.fill(keyEntries, null);
        Arrays.fill(slotOfId, -1);
        Arrays.fill(inQueue, false);
        Arrays.fill(isStructDirty, false);
        nextId = 0;
        freeCount = 0;
        totalTypes = 0;
        dirtyCount = 0;
        needsPersist = false;
        needsCompact = false;
        modCount++;
        resetTypeSlots();

        ListTag keys = root.getList("keys", Tag.TAG_COMPOUND);
        long[] pLo = root.getLongArray("lo");
        long[] pHi = root.getLongArray("hi");
        int size = keys.size();
        if (size > 0) ensureCapacity(size - 1);
        nextId = size;
        boolean healed = pLo.length < size || pHi.length < size;
        int[] mergedInto = new int[size];
        Arrays.fill(mergedInto, -1);
        boolean hasDuplicates = false;
        java.math.BigInteger[] loadedAmounts = null;
        var wide = root.getCompound("bigAmounts");
        for (var index : wide.getAllKeys()) {
            try {
                int sourceId = Integer.parseInt(index);
                if (!arbitraryPrecision || sourceId < 0 || sourceId >= size) {
                    healed = true;
                    continue;
                }
                var exactAmount = com.moakiee.thunderbolt.core.storage.big.BigAmounts.nonNegative(
                        new java.math.BigInteger(wide.getByteArray(index)));
                if (exactAmount.compareTo(MAX_126) <= 0) {
                    healed = true;
                    continue;
                }
                if (loadedAmounts == null) loadedAmounts = new java.math.BigInteger[size];
                loadedAmounts[sourceId] = exactAmount;
            } catch (RuntimeException malformed) {
                healed = true;
            }
        }

        for (int id = 0; id < size; id++) {
            CompoundTag entry = keys.getCompound(id);
            long entryLo = id < pLo.length ? pLo[id] : 0L;
            long entryHi = id < pHi.length ? pHi[id] : 0L;
            if (!entry.contains("key")) {
                addFree(id);
                if (entryLo != 0L || entryHi != 0L) healed = true;
                continue;
            }
            if (!entry.contains("key", Tag.TAG_COMPOUND)) {
                addFree(id);
                healed = true;
                continue;
            }

            AEKey key;
            try {
                key = keyDeserializer.fromTag(entry.getCompound("key").copy(), registries);
            } catch (RuntimeException malformed) {
                key = null;
            }
            if (key == null) {
                addFree(id);
                healed = true;
                continue;
            }

            boolean invalidProjection = entryLo < 0L || entryHi < 0L || (entryLo == 0L && entryHi == 0L);
            if (invalidProjection && (loadedAmounts == null || loadedAmounts[id] == null)) {
                addFree(id);
                healed = true;
                continue;
            }
            if (invalidProjection) healed = true;
            int existingId = keyToId.getInt(key);
            if (existingId != -1) {
                mergedInto[id] = existingId;
                addFree(id);
                healed = true;
                hasDuplicates = true;
                continue;
            }

            keyToId.put(key, id);
            idToKey[id] = key;
            lo[id] = entryLo;
            hi[id] = entryHi;
            serializedKey[id] = entry.getCompound("key").copy();
            totalTypes++;
            int slot = typeSlot(key.getType());
            slotCount[slot]++;
            slotOfId[id] = slot;
            mergedInto[id] = id;
        }

        if (loadedAmounts != null) {
            for (int id = 0; id < size; id++) {
                if (loadedAmounts[id] != null && mergedInto[id] < 0) healed = true;
            }
        }
        if (hasDuplicates) {
            if (loadedAmounts == null) loadedAmounts = new java.math.BigInteger[size];
            for (int id = 0; id < size; id++) {
                int target = mergedInto[id];
                if (target < 0 || target == id) continue;
                var total = loadedAmounts[target];
                if (total == null) total = exactAmount(hi[target], lo[target]);
                var duplicate = loadedAmounts[id];
                if (duplicate == null) {
                    long duplicateHi = id < pHi.length ? pHi[id] : 0L;
                    long duplicateLo = id < pLo.length ? pLo[id] : 0L;
                    duplicate = exactAmount(duplicateHi, duplicateLo);
                }
                loadedAmounts[target] = total.add(duplicate);
            }
        }
        if (loadedAmounts != null) {
            for (int id = 0; id < size; id++) {
                if (mergedInto[id] == id && loadedAmounts[id] != null)
                    healed |= setLoadedAmount(idToKey[id], loadedAmounts[id]);
            }
        }
        rebuildExactTotals();
        needsCompact = healed || freeCount > Math.max(totalTypes, 1) * COMPACT_THRESHOLD;
        needsPersist = healed;
    }

    private boolean setLoadedAmount(AEKey key, java.math.BigInteger amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Negative loaded quantity");
        }
        var stored = amount.min(arbitraryPrecision ? MAX_EXACT : MAX_126);
        int id = keyToId.getInt(key);
        projectAmount(id, stored);
        if (arbitraryPrecision && stored.compareTo(MAX_126) > 0) {
            wideAmounts.put(key, stored);
        } else {
            wideAmounts.remove(key);
        }
        return stored.compareTo(amount) != 0;
    }

    private void projectAmount(int id, java.math.BigInteger amount) {
        if (amount.bitLength() <= 63) {
            lo[id] = amount.longValueExact();
            hi[id] = 0L;
            return;
        }
        var projection = amount.min(MAX_126);
        lo[id] = projection.and(MASK_63).longValueExact();
        hi[id] = projection.shiftRight(63).longValueExact();
    }


    // ══════════════════════════════════════════════════════════════════════
    //  ID lifecycle — stable: id = ListTag position
    // ══════════════════════════════════════════════════════════════════════

    private int allocateId(AEKey key) {
        int id;
        if (freeCount > 0) {
            id = freeIds[--freeCount];
        } else {
            id = nextId++;
            ensureCapacity(id);
        }
        keyToId.put(key, id);
        idToKey[id] = key;
        lo[id] = 0;
        hi[id] = 0;
        serializedKey[id] = null;
        keyEntries[id] = null;
        isStructDirty[id] = true;
        enqueueDirty(id);
        lastLookupKey = key;
        lastLookupId = id;
        return id;
    }

    private void recycleId(int id, AEKey key) {
        keyToId.removeInt(key);
        idToKey[id] = null;
        lo[id] = 0;
        hi[id] = 0;
        serializedKey[id] = null;
        keyEntries[id] = null;
        slotOfId[id] = -1;
        isStructDirty[id] = true;
        enqueueDirty(id);
        addFree(id);
        lastLookupKey = key;
        lastLookupId = -1;
    }

    private void addFree(int id) {
        if (freeCount == freeIds.length) {
            freeIds = Arrays.copyOf(freeIds, freeIds.length * 2);
        }
        freeIds[freeCount++] = id;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Capacity
    // ══════════════════════════════════════════════════════════════════════

    private void ensureCapacity(int required) {
        if (required < lo.length) return;
        int newCap = Math.max(INITIAL_CAPACITY, Integer.highestOneBit(required) << 1);
        int oldCapacity = lo.length;
        lo = Arrays.copyOf(lo, newCap);
        hi = Arrays.copyOf(hi, newCap);
        idToKey = Arrays.copyOf(idToKey, newCap);
        serializedKey = Arrays.copyOf(serializedKey, newCap);
        keyEntries = Arrays.copyOf(keyEntries, newCap);
        slotOfId = Arrays.copyOf(slotOfId, newCap);
        Arrays.fill(slotOfId, oldCapacity, newCap, -1);
        inQueue = Arrays.copyOf(inQueue, newCap);
        isStructDirty = Arrays.copyOf(isStructDirty, newCap);
    }

    private void initArrays(int capacity) {
        lo = new long[capacity];
        hi = new long[capacity];
        idToKey = new AEKey[capacity];
        serializedKey = new CompoundTag[capacity];
        keyEntries = new CompoundTag[capacity];
        slotOfId = new int[capacity];
        Arrays.fill(slotOfId, -1);
        inQueue = new boolean[capacity];
        isStructDirty = new boolean[capacity];
        dirtyQueue = new int[64];
        freeIds = new int[64];
    }
}
