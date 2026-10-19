package com.moakiee.thunderbolt.core.crafting.batch;
import appeng.api.stacks.AEKeyType;
/** Additional container outputs discovered at dispatch time. */
public interface ContainerOutputTracker {
    void thunderbolt$addMaxItems(long amount, AEKeyType type);
}
