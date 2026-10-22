package com.moakiee.thunderbolt.core.crafting.batch;

import com.moakiee.thunderbolt.core.crafting.batch.SharedBatchInputPattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

/** Resolves inputs that one physical item may safely serve for an entire dispatched batch. */
public final class SharedBatchInputs {
    private SharedBatchInputs() {
    }

    /**
     * Returns whether this pattern has at least one explicitly shared or exactly self-returning
     * input variant.
     */
    public static boolean hasSharedInputs(IPatternDetails details) {
        if (details == null) return false;
        var inputs = details.getInputs();
        for (int slot = 0; slot < inputs.length; slot++) {
            var input = inputs[slot];
            for (var possible : input.getPossibleInputs()) {
                if (possible.what() != null && isSharedInput(details, slot, input, possible.what())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Explicit marker declarations remain authoritative. Ordinary recipe inputs are inferred as
     * shared only when AE2 reports that the exact concrete key comes back unchanged. Equality of
     * {@link AEKey} includes item components, so damaged or otherwise transformed catalysts keep
     * their normal per-copy accounting.
     */
    public static boolean isSharedInput(IPatternDetails details, int slot, AEKey concreteKey) {
        if (details == null || concreteKey == null) return false;
        var inputs = details.getInputs();
        if (slot < 0 || slot >= inputs.length) return false;

        return isSharedInput(details, slot, inputs[slot], concreteKey);
    }

    private static boolean isSharedInput(
            IPatternDetails details, int slot, IPatternDetails.IInput input, AEKey concreteKey) {
        if (details instanceof SharedBatchInputPattern explicit
                && explicit.isSharedBatchInput(slot, concreteKey)) {
            return true;
        }

        AEKey remaining = input.getRemainingKey(concreteKey);
        return concreteKey.equals(remaining);
    }
}
