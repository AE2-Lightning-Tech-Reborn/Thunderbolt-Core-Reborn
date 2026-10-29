package com.moakiee.thunderbolt.core.crafting.pattern;

import appeng.api.stacks.AEItemKey;

/** Forge 1.20.1 counterpart of the component-patch check used by AE2's item-only recipe caches. */
public final class PatternInputIdentity {
    private PatternInputIdentity() {}

    public static boolean hasSecondaryData(AEItemKey key) {
        // hasTag() covers ordinary NBT only. AE2 also retains serialized Forge capabilities in
        // key identity, so compare against its public, fully stripped identity instead of
        // reconstructing an ItemStack (which could lose unavailable capability data).
        return !key.equals(key.dropSecondary());
    }
}
