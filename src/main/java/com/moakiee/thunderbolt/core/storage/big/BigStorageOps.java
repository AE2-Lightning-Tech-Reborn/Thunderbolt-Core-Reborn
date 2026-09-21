package com.moakiee.thunderbolt.core.storage.big;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import com.moakiee.thunderbolt.api.storage.BigMEStorage;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded legacy bridge: at most one long transfer per endpoint, never quantity-sized loops. */
public final class BigStorageOps {
    private BigStorageOps() {}

    public static BigInteger insert(
            MEStorage storage, AEKey key, BigInteger n, Actionable mode, IActionSource source) {
        BigAmounts.nonNegative(n);
        return accepted(
                n,
                storage instanceof BigMEStorage exact
                        ? exact.insertBig(key, n, mode, source)
                        : BigInteger.valueOf(
                                storage.insert(key, BigAmounts.project(n), mode, source)));
    }

    public static BigInteger extract(
            MEStorage storage, AEKey key, BigInteger n, Actionable mode, IActionSource source) {
        BigAmounts.nonNegative(n);
        return accepted(
                n,
                storage instanceof BigMEStorage exact
                        ? exact.extractBig(key, n, mode, source)
                        : BigInteger.valueOf(
                                storage.extract(key, BigAmounts.project(n), mode, source)));
    }

    public static Map<AEKey, BigInteger> snapshot(MEStorage storage, IActionSource source) {
        if (storage instanceof BigMEStorage exact) return exact.snapshotBig(source);
        var counter = new KeyCounter();
        storage.getAvailableStacks(counter);
        var result = new LinkedHashMap<AEKey, BigInteger>();
        for (var entry : counter) {
            long amount =
                    storage.extract(
                            entry.getKey(),
                            Math.max(0, entry.getLongValue()),
                            Actionable.SIMULATE,
                            source);
            if (amount > 0) result.put(entry.getKey(), BigInteger.valueOf(amount));
        }
        return Map.copyOf(result);
    }

    private static BigInteger accepted(BigInteger requested, BigInteger actual) {
        if (actual == null || actual.signum() < 0 || actual.compareTo(requested) > 0)
            throw new IllegalStateException("Storage returned an invalid exact transfer quantity");
        return actual;
    }
}
