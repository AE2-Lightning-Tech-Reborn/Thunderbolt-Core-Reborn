package com.moakiee.thunderbolt.core.storage.cell;

import java.util.function.IntSupplier;

import appeng.api.stacks.AEKeyType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

/**
 * Tracks byte-level capacity consumption for the infinite cell using
 * remainder-delta incremental updates keyed on {@link AEKeyType}.
 * <p>
 * Formula: {@code usedBytes = uniqueKeys × bytesPerType + Σ_per_type ceil(typeTotal / apb)}
 * <p>
 * Per-{@link AEKeyType} aggregation reduces the number of tracked remainders
 * from O(keys) to O(keyTypes) (typically 2–3). All arithmetic uses signed
 * 63-bit longs; zero {@code Long.xxxUnsigned} calls.
 */
public final class ByteTracker {

    /** A channel moves as one unit when the dense channel array grows or removes an entry. */
    private static final class Channel {
        final AEKeyType type;
        final int amountPerByte;
        long remainder;
        int keyCount;

        Channel(AEKeyType type, int amountPerByte) {
            this.type = type;
            this.amountPerByte = amountPerByte;
        }
    }

    private Channel[] channels = new Channel[4];
    private int unitTypeCount;
    private Reference2IntOpenHashMap<AEKeyType> typeSlots;

    private int findType(AEKeyType type) {
        if (unitTypeCount > 2) return typeSlots.getInt(type);
        for (int i = 0; i < unitTypeCount; i++) {
            if (channels[i].type == type) return i;
        }
        return -1;
    }

    // Some custom channels (e.g. FE) expose a reloadable amount-per-byte setting.
    boolean hasCurrentUnits() {
        for (int i = 0; i < unitTypeCount; i++) {
            var channel = channels[i];
            if (channel.type.getAmountPerByte() != channel.amountPerByte) return false;
        }
        return true;
    }

    private Channel trackUnits(AEKeyType type, int amountPerByte) {
        if (unitTypeCount == channels.length) {
            channels = java.util.Arrays.copyOf(channels, channels.length * 2);
        }
        var channel = new Channel(type, amountPerByte);
        int index = unitTypeCount++;
        channels[index] = channel;
        if (typeSlots != null) {
            typeSlots.put(type, index);
        } else if (unitTypeCount > 2) {
            typeSlots = new Reference2IntOpenHashMap<>();
            typeSlots.defaultReturnValue(-1);
            for (int i = 0; i < unitTypeCount; i++) typeSlots.put(channels[i].type, i);
        }
        return channel;
    }

    private void removeType(int index) {
        int last = --unitTypeCount;
        if (typeSlots != null) {
            typeSlots.removeInt(channels[index].type);
            if (index != last) typeSlots.put(channels[last].type, index);
        }
        channels[index] = channels[last];
        channels[last] = null;
    }

    private long usedBytesLo;
    private long usedBytesHi;

    private long capacityLo;
    private long capacityHi;
    private int bytesPerType;
    private int maxTypes;
    private final IntSupplier totalTypesGetter;

    public ByteTracker(IntSupplier totalTypesGetter) {
        this.totalTypesGetter = totalTypesGetter;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Configuration
    // ══════════════════════════════════════════════════════════════════════

    public void configure(int bytesPerType, int maxTypes, long capacityLo, long capacityHi) {
        this.bytesPerType = bytesPerType;
        this.maxTypes = maxTypes;
        this.capacityLo = capacityLo;
        this.capacityHi = capacityHi;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Hot-path: capacity check
    // ══════════════════════════════════════════════════════════════════════

    public long computeMaxInsertable(AEKeyType type, boolean isNewKey) {
        int apb = type.getAmountPerByte();

        if (capacityHi == usedBytesHi) {
            if (capacityLo <= usedBytesLo) return 0;
            return maxFromFreeBytes(capacityLo - usedBytesLo, apb, type, isNewKey);
        }
        if (capacityHi < usedBytesHi) return 0;
        return Long.MAX_VALUE;
    }

    private long maxFromFreeBytes(long freeBytes, int apb, AEKeyType type, boolean isNewKey) {
        int totalTypes = totalTypesGetter.getAsInt();
        if (isNewKey) {
            if (totalTypes >= maxTypes) return 0;
            if (freeBytes < bytesPerType) return 0;
            freeBytes -= bytesPerType;
        }
        int index = findType(type);
        long r = index < 0 ? 0 : channels[index].remainder;
        long freeInPartial = (r > 0) ? (apb - r) : 0;
        if (freeBytes > Long.MAX_VALUE / apb) return Long.MAX_VALUE;
        long result = freeBytes * apb + freeInPartial;
        return result < 0 ? Long.MAX_VALUE : result;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Hot-path: insert delta
    // ══════════════════════════════════════════════════════════════════════

    public void onInsert(AEKeyType type, long amount, boolean isNewKey) {
        int apb = type.getAmountPerByte();
        int index = findType(type);
        var channel = index < 0 ? trackUnits(type, apb) : channels[index];

        long oldR = channel.remainder;
        long aMod = amount % apb;
        long combined = oldR + aMod;
        long extra = (combined >= apb) ? 1L : 0L;
        long newR = extra > 0 ? combined - apb : combined;
        channel.remainder = newR;

        long byteDelta = amount / apb + extra
                + (newR > 0 ? 1L : 0L) - (oldR > 0 ? 1L : 0L);

        if (isNewKey) {
            byteDelta += bytesPerType;
            // With one unit per byte, the request plus key charge can carry on
            // its own before it is added to the existing low word.
            if (byteDelta < 0) { byteDelta &= Long.MAX_VALUE; usedBytesHi++; }
            channel.keyCount++;
        }

        usedBytesLo += byteDelta;
        if (usedBytesLo < 0) { usedBytesLo &= Long.MAX_VALUE; usedBytesHi++; }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Hot-path: extract delta
    // ══════════════════════════════════════════════════════════════════════

    public void onExtract(AEKeyType type, long amount, boolean keyRemoved) {
        int apb = type.getAmountPerByte();
        int index = findType(type);
        var channel = channels[index];

        long oldR = channel.remainder;
        long aMod = amount % apb;
        long newR, extra;
        if (oldR >= aMod) {
            newR = oldR - aMod;
            extra = 0;
        } else {
            newR = oldR + apb - aMod;
            extra = 1;
        }

        long bytesFreed = amount / apb + extra
                + (oldR > 0 ? 1L : 0L) - (newR > 0 ? 1L : 0L);

        channel.remainder = newR;
        if (keyRemoved) {
            bytesFreed += bytesPerType;
            if (bytesFreed < 0) { bytesFreed &= Long.MAX_VALUE; usedBytesHi--; }
            if (--channel.keyCount <= 0) {
                removeType(index);
            }
        }

        usedBytesLo -= bytesFreed;
        if (usedBytesLo < 0) { usedBytesLo &= Long.MAX_VALUE; usedBytesHi--; }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Queries
    // ══════════════════════════════════════════════════════════════════════

    public long getUsedBytes() {
        return DualLong126.cap(usedBytesHi, usedBytesLo);
    }

    public long getUsedBytesHi() { return usedBytesHi; }

    public long getUsedBytesLo() { return usedBytesLo; }

    public boolean isFull() {
        if (capacityHi == usedBytesHi) return capacityLo <= usedBytesLo;
        return capacityHi < usedBytesHi;
    }

    public boolean isTypeFull() {
        return totalTypesGetter.getAsInt() >= maxTypes;
    }

    /** Test-only visibility: per-type bookkeeping entries must vanish with a type's last key. */
    int trackedTypeEntries() {
        return unitTypeCount * 2;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Cold-path: full rebuild from pre-aggregated per-type data — O(keyTypes)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Rebuild byte tracking state from per-{@link AEKeyType} aggregates
     * already maintained by the storage engine.
     *
     * @param ktLo   per-type total amount (lo half, 63-bit unsigned)
     * @param ktHi   per-type total amount (hi half, 63-bit unsigned)
     * @param ktCounts per-type unique key count
     * @param totalKeys total number of unique keys across all types
     */
    public void rebuild(Object2LongOpenHashMap<AEKeyType> ktLo,
                        Object2LongOpenHashMap<AEKeyType> ktHi,
                        Object2IntOpenHashMap<AEKeyType> ktCounts,
                        int totalKeys) {
        beginRebuild();
        long[] divBuf = new long[2];
        for (var kt : ktLo.keySet()) {
            rebuildChannel(kt, ktLo.getLong(kt), ktHi.getLong(kt), ktCounts.getInt(kt), divBuf);
        }
        finishRebuild(totalKeys);
    }

    /**
     * Rebuild from the engine's identity-indexed type slots. This avoids materializing the
     * three per-type maps that the cold path above needs, and reuses the caller's scratch
     * buffer. Only the small channel records are recreated.
     */
    public void rebuild(IndexedStorage storage, long[] divBuf) {
        beginRebuild();
        int slots = storage.usedTypeSlots();
        for (int slot = 0; slot < slots; slot++) {
            int count = storage.typeCountAt(slot);
            if (count <= 0) continue;
            rebuildChannel(storage.typeAt(slot), storage.typeLoAt(slot), storage.typeHiAt(slot), count, divBuf);
        }
        finishRebuild(storage.getTotalTypes());
    }

    private void beginRebuild() {
        if (typeSlots != null) typeSlots.clear();
        java.util.Arrays.fill(channels, null);
        unitTypeCount = 0;
        usedBytesLo = 0;
        usedBytesHi = 0;
    }

    /** Both public rebuild entry points use exactly the same rounding and carry arithmetic. */
    private void rebuildChannel(AEKeyType type, long lo, long hi, int keyCount, long[] divBuf) {
        int apb = type.getAmountPerByte();
        var channel = trackUnits(type, apb);
        channel.keyCount = keyCount;
        channel.remainder = hi == 0 ? lo % apb : DualLong126.mod126(hi, lo, apb);
        DualLong126.ceilDiv126(hi, lo, apb, divBuf);
        usedBytesLo += divBuf[1];
        if (usedBytesLo < 0) { usedBytesLo &= Long.MAX_VALUE; usedBytesHi++; }
        usedBytesHi += divBuf[0];
    }

    private void finishRebuild(int totalKeys) {
        usedBytesLo += (long) totalKeys * bytesPerType;
        if (usedBytesLo < 0) { usedBytesLo &= Long.MAX_VALUE; usedBytesHi++; }
    }
}
