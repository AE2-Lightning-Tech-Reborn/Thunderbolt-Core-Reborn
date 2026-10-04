package com.moakiee.thunderbolt.compat.appliede;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.crafting.IPatternDetails;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;

/** Aggregates into AppliedE's existing output queue; EMC is paid by the CPU's batch extraction. */
public final class AppliedEModuleBatchSupport {
    static final String TRANSMUTATION_PATTERN = "gripe._90.appliede.me.misc.TransmutationPattern";
    private AppliedEModuleBatchSupport() {
    }

    /** Forge 0.14.x uses me.misc, not the newer platform's me.service package. */
    public static @Nullable GenericStack primaryOutput(@Nullable IPatternDetails details) {
        return details != null && details.getClass().getName().equals(TRANSMUTATION_PATTERN)
                ? details.getPrimaryOutput() : null;
    }

    public static long capacity(@Nullable GenericStack output, Object2LongMap<AEKey> pending) {
        if (output == null || output.amount() <= 0L) return 0L;
        long held = pending.getLong(output.what());
        if (held < 0L) return 0L;
        return (Long.MAX_VALUE - held) / output.amount();
    }

    /** Returns unaccepted copies, with one queue update regardless of the batch size. */
    public static long enqueue(
            @Nullable GenericStack output, Object2LongMap<AEKey> pending, long copies) {
        if (copies <= 0L) return 0L;
        long accepted = Math.min(copies, capacity(output, pending));
        if (accepted > 0L) {
            pending.put(output.what(), pending.getLong(output.what()) + output.amount() * accepted);
        }
        return copies - accepted;
    }
}
