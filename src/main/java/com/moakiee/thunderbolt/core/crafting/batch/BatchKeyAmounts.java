package com.moakiee.thunderbolt.core.crafting.batch;

import appeng.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;

/**
 * Writes through the primitive parent API shared by both GTO AE2 map implementations.
 * The Object-erased calls still dispatch to the native map's identity and saturation rules;
 * AEKey-typed put/addTo descriptors disappeared when AE2 introduced IdentityKeyLongMap.
 */
final class BatchKeyAmounts {
    private BatchKeyAmounts() {}

    static long put(Reference2LongOpenHashMap<AEKey> amounts, AEKey key, long amount) {
        return amounts.put(key, amount);
    }

    static long addTo(Reference2LongOpenHashMap<AEKey> amounts, AEKey key, long amount) {
        return amounts.addTo(key, amount);
    }
}
