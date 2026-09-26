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

import com.moakiee.thunderbolt.core.storage.cell.DualLong126;

/**
 * Array-indexed storage engine for the infinite cell.
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
 * When the number of free (hole) slots exceeds {@code totalTypes * COMPACT_THRESHOLD},
 * a deferred compaction is scheduled and executed at the next {@link #persist},
 * reassigning contiguous ids and forcing a full rewrite.
 */
public final class IndexedStorage {
    private static final java.math.BigInteger MASK_63 = java.math.BigInteger.valueOf(Long.MAX_VALUE);
    private static final java.math.BigInteger MAX_126 =
            java.math.BigInteger.ONE.shiftLeft(126).subtract(java.math.BigInteger.ONE);
    private static final java.math.BigInteger MAX_EXACT =
            java.math.BigInteger.ONE
                    .shiftLeft(com.moakiee.thunderbolt.core.storage.big.BigAmounts.MAX_BITS)
                    .subtract(java.math.BigInteger.ONE);
    private boolean arbitraryPrecision;
    // Only quantities beyond the legacy 126-bit encoding need an additional serialized value.
    private final java.util.Map<AEKey, java.math.BigInteger> wideAmounts = new java.util.HashMap<>();
    private final java.util.Map<AEKeyType, java.math.BigInteger> exactTypeTotals = new java.util.HashMap<>();
    private final java.util.Map<AEKeyType, java.math.BigInteger> legacyOverflowTotals = new java.util.HashMap<>();

    public void enableArbitraryPrecision() {
        if (arbitraryPrecision) return;
        arbitraryPrecision = true;
        legacyOverflowTotals.clear();
        rebuildExactTotals();
    }

    public java.math.BigInteger getAmountExact(AEKey key) {
        var wide = wideAmounts.get(key);
        if (wide != null) return wide;
        int id = keyToId.getInt(key);
        if (id < 0) return java.math.BigInteger.ZERO;
        return java.math.BigInteger.valueOf(hi[id]).shiftLeft(63)
                .add(java.math.BigInteger.valueOf(lo[id]));
    }

    public java.util.Map<AEKey, java.math.BigInteger> snapshotExact() {
        var result = new java.util.LinkedHashMap<AEKey, java.math.BigInteger>();
        for (int id = 0; id < nextId; id++) {
            var key = idToKey[id];
            if (key == null) continue;
            var wide = wideAmounts.get(key);
            result.put(key, wide != null
                    ? wide
                    : java.math.BigInteger.valueOf(hi[id]).shiftLeft(63)
                            .add(java.math.BigInteger.valueOf(lo[id])));
        }
        return result.isEmpty() ? java.util.Map.of() : java.util.Collections.unmodifiableMap(result);
    }

    public java.math.BigInteger insertExact(AEKey key, java.math.BigInteger amount, Actionable mode) {
        com.moakiee.thunderbolt.core.storage.big.BigAmounts.nonNegative(amount);
        if (!arbitraryPrecision) throw new IllegalStateException("Exact insertion requires an opted-in cell");
        var current = getAmountExact(key);
        var accepted = amount.min(MAX_EXACT.subtract(current).max(java.math.BigInteger.ZERO));
        if (accepted.signum() > 0 && mode == Actionable.MODULATE) setAmountExact(key, current.add(accepted), current);
        return accepted;
    }

    public java.math.BigInteger extractExact(AEKey key, java.math.BigInteger amount, Actionable mode) {
        com.moakiee.thunderbolt.core.storage.big.BigAmounts.nonNegative(amount);
        if (!arbitraryPrecision) throw new IllegalStateException("Exact extraction requires an opted-in cell");
        var current = getAmountExact(key);
        var taken = current.min(amount);
        if (taken.signum() > 0 && mode == Actionable.MODULATE) setAmountExact(key, current.subtract(taken), current);
        return taken;
    }

    private void setAmountExact(AEKey key, java.math.BigInteger amount) {
        setAmountExact(key, amount, getAmountExact(key));
    }

    private void setAmountExact(AEKey key, java.math.BigInteger amount, java.math.BigInteger before) {
        if (amount.equals(before)) return;
        int id = keyToId.getInt(key);
        if (id < 0) { id = allocateId(key); totalTypes++; typeCounts.addTo(key.getType(), 1); }
        if (amount.signum() == 0) {
            wideAmounts.remove(key);
            recycleId(id, key); totalTypes--; typeCounts.addTo(key.getType(), -1);
            if (typeCounts.getInt(key.getType()) == 0) typeCounts.removeInt(key.getType());
            if (freeCount > Math.max(totalTypes, 1) * COMPACT_THRESHOLD) needsCompact = true;
        } else {
            var projection = amount.min(MAX_126);
            lo[id] = projection.and(MASK_63).longValueExact(); hi[id] = projection.shiftRight(63).longValueExact();
            if (amount.compareTo(MAX_126) > 0) wideAmounts.put(key, amount); else wideAmounts.remove(key);
            enqueueDirty(id);
        }
        AEKeyType type = key.getType();
        var total = exactTypeTotals.getOrDefault(type, java.math.BigInteger.ZERO)
                .add(amount)
                .subtract(before);
        if (total.signum() == 0) {
            exactTypeTotals.remove(type);
            typeAmountLo.removeLong(type);
            typeAmountHi.removeLong(type);
        } else {
            exactTypeTotals.put(type, total);
            projectTypeTotal(type, total);
        }
        needsPersist = true;
        modCount++;
    }

    private void projectTypeTotal(AEKeyType type, java.math.BigInteger amount) {
        var n = amount.min(MAX_126);
        typeAmountLo.put(type, n.and(MASK_63).longValueExact());
        typeAmountHi.put(type, n.shiftRight(63).longValueExact());
    }

    private void rebuildExactTotals() {
        exactTypeTotals.clear();
        for (int id = 0; id < nextId; id++) if (idToKey[id] != null)
            exactTypeTotals.merge(idToKey[id].getType(), getAmountExact(idToKey[id]), java.math.BigInteger::add);
        exactTypeTotals.forEach(this::projectTypeTotal);
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
    private AEKey[] idToKey;
    private int nextId;
    private int[] freeIds;
    private int freeCount;

    // Amount arrays (63+63 bit)
    private long[] lo;
    private long[] hi;

    // Cached key serialization — set once per key lifetime, avoids repeated toTagGeneric
    private CompoundTag[] serializedKey;

    // Dirty tracking: queue + per-id flags (replaces bitset scanning)
    private int[] dirtyQueue;
    private int dirtyCount;
    private boolean[] inQueue;
    private boolean[] isStructDirty;

    private int totalTypes;
    private boolean needsPersist;
    private boolean needsCompact;
    private long modCount;

    // Per-AEKeyType aggregates — maintained incrementally
    private final Object2IntOpenHashMap<AEKeyType> typeCounts = new Object2IntOpenHashMap<>();
    private final Object2LongOpenHashMap<AEKeyType> typeAmountLo = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<AEKeyType> typeAmountHi = new Object2LongOpenHashMap<>();

    public IndexedStorage() {
        keyToId.defaultReturnValue(-1);
        initArrays(INITIAL_CAPACITY);
    }

    public int getTotalTypes() { return totalTypes; }

    public boolean needsPersist() { return needsPersist; }

    public long getModCount() { return modCount; }

    public Object2LongOpenHashMap<AEKeyType> getTypeAmountLo() { return typeAmountLo; }
    public Object2LongOpenHashMap<AEKeyType> getTypeAmountHi() { return typeAmountHi; }
    public Object2IntOpenHashMap<AEKeyType> getTypeCounts() { return typeCounts; }

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
        if (arbitraryPrecision && amount > 0) return insertExact(key, java.math.BigInteger.valueOf(amount), mode).longValueExact();
        if (amount <= 0) return 0;
        int id = keyToId.getInt(key);
        if (id >= 0 && hi[id] == Long.MAX_VALUE) {
            amount = Math.min(amount, Long.MAX_VALUE - lo[id]);
            if (amount == 0) return 0;
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
        if (newLo < 0) {
            newLo &= Long.MAX_VALUE;
            if (hi[id] == Long.MAX_VALUE) {
                newLo = Long.MAX_VALUE;
            } else {
                hi[id]++;
            }
        }
        lo[id] = newLo;

        AEKeyType kt = key.getType();
        if (isNewKey) typeCounts.addTo(kt, 1);
        updateLegacyTypeTotal(kt, amount);

        needsPersist = true;
        modCount++;
        return amount;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  extract
    // ══════════════════════════════════════════════════════════════════════

    public long extract(AEKey key, long amount, Actionable mode) {
        if (arbitraryPrecision && amount > 0) return extractExact(key, java.math.BigInteger.valueOf(amount), mode).longValueExact();
        if (amount <= 0) return 0;

        int id = keyToId.getInt(key);
        if (id == -1) return 0;

        long curLo = lo[id], curHi = hi[id];
        long taken = DualLong126.geq(curHi, curLo, amount) ? amount : curLo;

        if (mode == Actionable.SIMULATE) return taken;

        long newLo = curLo - taken;
        if (newLo < 0) { newLo &= Long.MAX_VALUE; hi[id]--; }

        boolean keyRemoved = (newLo == 0 && hi[id] == 0);
        if (keyRemoved) {
            recycleId(id, key);
            totalTypes--;
            if (freeCount > Math.max(totalTypes, 1) * COMPACT_THRESHOLD) {
                needsCompact = true;
            }
        } else {
            lo[id] = newLo;
            enqueueDirty(id);
        }

        AEKeyType kt = key.getType();
        updateLegacyTypeTotal(kt, -taken);
        if (keyRemoved) {
            if (typeCounts.addTo(kt, -1) <= 1) {
                typeCounts.removeInt(kt);
                typeAmountLo.removeLong(kt);
                typeAmountHi.removeLong(kt);
                legacyOverflowTotals.remove(kt);
            }
        }

        needsPersist = true;
        modCount++;
        return taken;
    }

    private void updateLegacyTypeTotal(AEKeyType type, long delta) {
        var overflow = legacyOverflowTotals.get(type);
        if (overflow != null) {
            var updated = overflow.add(java.math.BigInteger.valueOf(delta));
            if (updated.compareTo(MAX_126) > 0) legacyOverflowTotals.put(type, updated);
            else legacyOverflowTotals.remove(type);
            projectTypeTotal(type, updated);
            return;
        }

        long low = typeAmountLo.getLong(type);
        long high = typeAmountHi.getLong(type);
        long updatedLow = low + delta;
        if (delta > 0 && updatedLow < 0) {
            if (high == Long.MAX_VALUE) {
                legacyOverflowTotals.put(type, amount(high, low).add(java.math.BigInteger.valueOf(delta)));
                typeAmountLo.put(type, Long.MAX_VALUE);
                return;
            }
            updatedLow &= Long.MAX_VALUE;
            high++;
        } else if (delta < 0 && updatedLow < 0) {
            updatedLow &= Long.MAX_VALUE;
            high--;
        }
        typeAmountLo.put(type, updatedLow);
        typeAmountHi.put(type, high);
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
        for (int id = 0; id < nextId; id++) {
            var key = idToKey[id];
            if (key != null) {
                out.set(key, com.google.common.math.LongMath.saturatedAdd(out.get(key), DualLong126.cap(hi[id], lo[id])));
            }
        }
    }

    public boolean containsKey(AEKey key) {
        return keyToId.containsKey(key);
    }

    public long getAmount(AEKey key) {
        int id = keyToId.getInt(key);
        if (id == -1) return 0;
        return DualLong126.cap(hi[id], lo[id]);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Persist — queue-driven, O(changed). Split layout:
    //  ListTag<CompoundTag> for keys, LongArrayTag for lo/hi.
    // ══════════════════════════════════════════════════════════════════════

    public CompoundTag persist(@Nullable CompoundTag lastRoot, HolderLookup.Provider registries) {
        return persist(lastRoot, (key, reg) -> key.toTagGeneric(), registries);
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
        long[] pLo = lastRoot.getLongArray("lo");
        long[] pHi = lastRoot.getLongArray("hi");

        int tagLen = alignPow2(nextId);
        if (pLo.length < nextId || pHi.length < nextId) {
            pLo = Arrays.copyOf(pLo, tagLen);
            pHi = Arrays.copyOf(pHi, tagLen);
            lastRoot.put("lo", new LongArrayTag(pLo));
            lastRoot.put("hi", new LongArrayTag(pHi));
        }
        while (keys.size() < nextId) {
            keys.add(new CompoundTag());
        }

        for (int i = 0; i < dirtyCount; i++) {
            int id = dirtyQueue[i];
            inQueue[id] = false;

            if (isStructDirty[id]) {
                isStructDirty[id] = false;
                if (idToKey[id] != null) {
                    if (serializedKey[id] == null) {
                        serializedKey[id] = keySerializer.toTag(idToKey[id], registries);
                    }
                    CompoundTag tag = new CompoundTag();
                    tag.put("key", serializedKey[id]);
                    keys.set(id, tag);
                } else {
                    keys.set(id, new CompoundTag());
                }
            }

            pLo[id] = lo[id];
            pHi[id] = hi[id];
        }
        dirtyCount = 0;

        lastRoot.putInt("totalTypes", totalTypes);
        needsPersist = false;
        persistWide(lastRoot);
        return lastRoot;
    }

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
                CompoundTag tag = new CompoundTag();
                tag.put("key", serializedKey[id]);
                keys.add(tag);
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
        int newCap = alignPow2(Math.max(totalTypes, 1));
        int newNext = 0;

        AEKey[] nKey = new AEKey[newCap];
        long[] nLo = new long[newCap];
        long[] nHi = new long[newCap];
        CompoundTag[] nSer = new CompoundTag[newCap];

        for (int old = 0; old < nextId; old++) {
            if (idToKey[old] == null) continue;
            int nid = newNext++;
            nKey[nid] = idToKey[old];
            nLo[nid] = lo[old];
            nHi[nid] = hi[old];
            nSer[nid] = serializedKey[old];
            keyToId.put(idToKey[old], nid);
        }

        idToKey = nKey;
        lo = nLo;
        hi = nHi;
        serializedKey = nSer;
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

    @FunctionalInterface
    public interface KeyDeserializer {
        @Nullable AEKey fromTag(CompoundTag tag, HolderLookup.Provider registries);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Load
    // ══════════════════════════════════════════════════════════════════════

    public void load(CompoundTag root, HolderLookup.Provider registries) {
        load(root, (tag, reg) -> AEKey.fromTagGeneric(tag), registries);
    }

    public void load(
            CompoundTag root,
            KeyDeserializer keyDeserializer,
            HolderLookup.Provider registries) {
        java.util.Objects.requireNonNull(root, "root");
        java.util.Objects.requireNonNull(keyDeserializer, "keyDeserializer");
        wideAmounts.clear();
        exactTypeTotals.clear();
        legacyOverflowTotals.clear();
        arbitraryPrecision = root.getBoolean("arbitraryPrecision");
        keyToId.clear();
        Arrays.fill(idToKey, null);
        Arrays.fill(lo, 0L);
        Arrays.fill(hi, 0L);
        Arrays.fill(serializedKey, null);
        Arrays.fill(inQueue, false);
        Arrays.fill(isStructDirty, false);
        nextId = 0;
        freeCount = 0;
        totalTypes = 0;
        dirtyCount = 0;
        needsPersist = false;
        needsCompact = false;
        modCount = 0L;
        typeCounts.clear();
        typeAmountLo.clear();
        typeAmountHi.clear();

        ListTag keys = root.getList("keys", Tag.TAG_COMPOUND);
        long[] pLo = root.getLongArray("lo");
        long[] pHi = root.getLongArray("hi");
        int size = keys.size();
        if (size > 0) ensureCapacity(size - 1);
        nextId = size;
        boolean healed = pLo.length < size || pHi.length < size;
        int[] mergedInto = new int[size];
        Arrays.fill(mergedInto, -1);
        java.math.BigInteger[] loadedAmounts = new java.math.BigInteger[size];

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

            if (entryLo < 0L || entryHi < 0L || (entryLo == 0L && entryHi == 0L)) {
                addFree(id);
                healed = true;
                continue;
            }
            loadedAmounts[id] = amount(entryHi, entryLo);

            int existingId = keyToId.getInt(key);
            if (existingId != -1) {
                mergedInto[id] = existingId;
                addFree(id);
                healed = true;
                continue;
            }

            keyToId.put(key, id);
            idToKey[id] = key;
            lo[id] = entryLo;
            hi[id] = entryHi;
            serializedKey[id] = entry.getCompound("key").copy();
            totalTypes++;
            typeCounts.addTo(key.getType(), 1);
            mergedInto[id] = id;
        }

        var wide = root.getCompound("bigAmounts");
        for (var index : wide.getAllKeys()) {
            try {
                int sourceId = Integer.parseInt(index);
                if (!arbitraryPrecision
                        || sourceId < 0
                        || sourceId >= nextId
                        || mergedInto[sourceId] < 0
                        || loadedAmounts[sourceId] == null) {
                    healed = true;
                    continue;
                }
                var exactAmount = com.moakiee.thunderbolt.core.storage.big.BigAmounts.nonNegative(
                        new java.math.BigInteger(wide.getByteArray(index)));
                if (exactAmount.compareTo(MAX_126) <= 0) {
                    healed = true;
                    continue;
                }
                loadedAmounts[sourceId] = exactAmount;
            } catch (RuntimeException malformed) {
                healed = true;
            }
        }
        java.math.BigInteger[] mergedAmounts = new java.math.BigInteger[size];
        for (int id = 0; id < size; id++) {
            int target = mergedInto[id];
            if (target < 0) continue;
            mergedAmounts[target] = mergedAmounts[target] == null ? loadedAmounts[id]
                    : mergedAmounts[target].add(loadedAmounts[id]);
        }
        for (int id = 0; id < size; id++) {
            if (mergedAmounts[id] != null) healed |= setLoadedAmount(idToKey[id], mergedAmounts[id]);
        }
        rebuildLoadedTypeTotals();
        needsCompact = healed || freeCount > Math.max(totalTypes, 1) * COMPACT_THRESHOLD;
        needsPersist = healed;
    }

    private static java.math.BigInteger amount(long high, long low) {
        return java.math.BigInteger.valueOf(high).shiftLeft(63)
                .add(java.math.BigInteger.valueOf(low));
    }

    private boolean setLoadedAmount(AEKey key, java.math.BigInteger amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Negative loaded quantity");
        }
        var stored = amount.min(arbitraryPrecision ? MAX_EXACT : MAX_126);
        int id = keyToId.getInt(key);
        var projection = stored.min(MAX_126);
        lo[id] = projection.and(MASK_63).longValueExact();
        hi[id] = projection.shiftRight(63).longValueExact();
        if (arbitraryPrecision && stored.compareTo(MAX_126) > 0) {
            wideAmounts.put(key, stored);
        } else {
            wideAmounts.remove(key);
        }
        return stored.compareTo(amount) != 0;
    }

    private void rebuildLoadedTypeTotals() {
        exactTypeTotals.clear();
        typeAmountLo.clear();
        typeAmountHi.clear();
        for (int id = 0; id < nextId; id++) {
            AEKey key = idToKey[id];
            if (key == null) continue;
            exactTypeTotals.merge(key.getType(), getAmountExact(key), java.math.BigInteger::add);
        }
        exactTypeTotals.forEach((type, total) -> {
            projectTypeTotal(type, total);
            if (!arbitraryPrecision && total.compareTo(MAX_126) > 0) legacyOverflowTotals.put(type, total);
        });
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
        isStructDirty[id] = true;
        enqueueDirty(id);
        return id;
    }

    private void recycleId(int id, AEKey key) {
        keyToId.removeInt(key);
        idToKey[id] = null;
        lo[id] = 0;
        hi[id] = 0;
        serializedKey[id] = null;
        isStructDirty[id] = true;
        enqueueDirty(id);
        addFree(id);
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
        lo = Arrays.copyOf(lo, newCap);
        hi = Arrays.copyOf(hi, newCap);
        idToKey = Arrays.copyOf(idToKey, newCap);
        serializedKey = Arrays.copyOf(serializedKey, newCap);
        inQueue = Arrays.copyOf(inQueue, newCap);
        isStructDirty = Arrays.copyOf(isStructDirty, newCap);
    }

    private void initArrays(int capacity) {
        lo = new long[capacity];
        hi = new long[capacity];
        idToKey = new AEKey[capacity];
        serializedKey = new CompoundTag[capacity];
        inQueue = new boolean[capacity];
        isStructDirty = new boolean[capacity];
        dirtyQueue = new int[64];
        freeIds = new int[64];
    }
}
