package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.Objects;
import java.math.BigInteger;

/**
 * Non-primary output of a {@link CraftPattern}, credited to the shared byproduct pool.
 *
 * @param key    the byproduct item
 * @param amount how many are produced per single firing of the pattern
 * @param exactAmount exact per-firing quantity behind the bounded {@code long} view
 * @param <K>    item key type
 */
public record CraftOutput<K>(K key, long amount, BigInteger exactAmount) {
    public CraftOutput(K key, long amount) {
        this(key, amount, BigInteger.valueOf(amount));
    }

    public static <K> CraftOutput<K> exact(K key, BigInteger amount) {
        return new CraftOutput<>(key, amount.min(BigInteger.valueOf(Sat.SAT)).longValueExact(), amount);
    }

    public CraftOutput {
        Objects.requireNonNull(key, "key");
        if (exactAmount == null || exactAmount.signum() <= 0) throw new IllegalArgumentException("exactAmount");
        if (amount <= 0) {
            throw new IllegalArgumentException("output amount must be > 0, was " + amount);
        }
    }

    public static <K> CraftOutput<K> of(K key, long amount) {
        return new CraftOutput<>(key, amount);
    }
}
