package com.moakiee.thunderbolt.core.crafting.pattern;

import appeng.api.stacks.AEItemKey;

/** Only keys equal to the fork's stripped identity may use item-only recipe caches. */
public final class PatternInputIdentity {
    private PatternInputIdentity() {}

    public static boolean hasSecondaryData(AEItemKey key) {
        // Compare the identity actually represented by AE2-GTO. Unlike ordinary Forge AE2,
        // this fork does not keep a separate serialized caps field. Do not reconstruct a
        // stack or invent metadata that the network's key contract cannot represent.
        return !key.equals(key.dropSecondary());
    }
}
