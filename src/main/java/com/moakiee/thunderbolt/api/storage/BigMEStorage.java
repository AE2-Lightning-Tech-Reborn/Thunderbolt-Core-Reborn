package com.moakiee.thunderbolt.api.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;

import java.math.BigInteger;
import java.util.Map;

/**
 * Additive exact-quantity contract. Implementations share their long facade's physical inventory.
 */
public interface BigMEStorage {
    BigInteger insertBig(AEKey key, BigInteger amount, Actionable mode, IActionSource source);

    BigInteger extractBig(AEKey key, BigInteger amount, Actionable mode, IActionSource source);

    /** Exact extractable quantities at this instant; never a second mutable inventory. */
    Map<AEKey, BigInteger> snapshotBig(IActionSource source);
}
