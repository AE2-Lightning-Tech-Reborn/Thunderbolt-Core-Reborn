package com.moakiee.thunderbolt.core.keys;

/** Opaque identity of a copy-on-write component snapshot; callers must never mutate the token. */
public interface SharedComponentPatch {
    Object thunderbolt$sharedPatchIdentity();
    void thunderbolt$markRepeatedCopy(boolean repeated);
}
