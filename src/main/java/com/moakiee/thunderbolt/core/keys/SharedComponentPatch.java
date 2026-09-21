package com.moakiee.thunderbolt.core.keys;

/** Opaque identity of a copy-on-write component snapshot; callers must never mutate the token. */
public interface SharedComponentPatch {
    /** Borrowed for synchronous value lookup only; never retain, mutate, or publish as an identity. */
    it.unimi.dsi.fastutil.objects.Reference2ObjectMap<net.minecraft.core.component.DataComponentType<?>, java.util.Optional<?>> thunderbolt$componentPatchView();
    /** Freeze the current patch exactly as native copy/asPatch would, without a wrapper allocation. */
    void thunderbolt$shareComponentPatch();
    Object thunderbolt$sharedPatchIdentity();
    Object thunderbolt$copyOnWritePatchIdentity();
    Object thunderbolt$prototypeIdentity();
    void thunderbolt$markRepeatedCopy(boolean repeated);
}
