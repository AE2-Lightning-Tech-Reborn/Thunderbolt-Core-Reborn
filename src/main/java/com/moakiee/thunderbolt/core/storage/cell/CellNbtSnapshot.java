package com.moakiee.thunderbolt.core.storage.cell;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread cache of detached NBT fields handed to the I/O worker.
 * Key entries are immutable after encoding; mutable containers and quantities must be detached.
 * Invalidate a field where persistence mutates its encoded source, not where live stock changes.
 * A replaced source is detected by identity, including full rewrites and array growth.
 */
final class CellNbtSnapshot {
    private @Nullable ListTag keySource;
    private @Nullable ListTag keys;
    private @Nullable LongArrayTag highSource;
    private @Nullable LongArrayTag high;

    void keysChanged() {
        keySource = null;
        keys = null;
    }

    void highChanged() {
        highSource = null;
        high = null;
    }

    void reset() {
        keysChanged();
        highChanged();
    }

    /** The returned payload and all shared entries are read-only after handoff. */
    CompoundTag copy(CompoundTag encoded) {
        var result = new CompoundTag();
        for (var name : encoded.getAllKeys()) {
            var value = encoded.get(name);
            if (name.equals("keys") && value instanceof ListTag source) {
                if (keys == null || keySource != source) {
                    keys = new ListTag(source.size());
                    for (var entry : source) keys.add(entry);
                    keySource = source;
                }
                result.put(name, keys);
            } else if (name.equals("hi") && value instanceof LongArrayTag source) {
                if (high == null || highSource != source) {
                    high = source.copy();
                    highSource = source;
                }
                result.put(name, high);
            } else {
                result.put(name, value.copy());
            }
        }
        return result;
    }
}
