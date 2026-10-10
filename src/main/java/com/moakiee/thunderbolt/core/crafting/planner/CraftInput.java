package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

import java.util.Objects;
import java.math.BigInteger;

/**
 * An input slot with an authoritative exact amount and a saturated long view.
 * Ordinary inputs consume {@code amount * times}; unchanged catalysts need one seed;
 * finite-use tools consume {@code amount * ceil(times / uses)}.
 * A non-null remainder is produced per firing of an ordinary container input.
 * Host-private reusable stock is allowed only for unchanged, infinite-use catalysts.
 */
public record CraftInput<K>(K key, long amount, boolean returned, long uses, K remainder,
                            ReusableStockSource reusableStockSource, BigInteger exactAmount) {

    /** A true catalyst survives unlimited firings (one seed serves the whole batch). */
    public static final long INFINITE_USES = Long.MAX_VALUE;

    public CraftInput(K key, long amount, boolean returned, long uses, K remainder,
            ReusableStockSource reusableStockSource) {
        this(key, amount, returned, uses, remainder, reusableStockSource, BigInteger.valueOf(amount));
    }

    public CraftInput<K> scaled(long multiplier) {
        BigInteger exact = exactAmount.multiply(BigInteger.valueOf(multiplier));
        return new CraftInput<>(key, Sat.mul(amount, multiplier), returned, uses, remainder,
                reusableStockSource, exact);
    }

    public BigInteger unitsForExact(BigInteger times) {
        if (times.signum() == 0) return BigInteger.ZERO;
        BigInteger units = !returned ? times : uses == INFINITE_USES ? BigInteger.ONE
                : ExactDiagnosticPlanner.ceilDiv(times, BigInteger.valueOf(uses));
        return ExactDiagnosticPlanner.checked(exactAmount.multiply(units));
    }

    public CraftInput {
        Objects.requireNonNull(key, "key");
        if (exactAmount == null || exactAmount.signum() <= 0) throw new IllegalArgumentException("exactAmount");
        if (amount <= 0) {
            throw new IllegalArgumentException("input amount must be > 0, was " + amount);
        }
        if (returned && uses <= 0) {
            throw new IllegalArgumentException("returned input uses must be > 0, was " + uses);
        }
        if (reusableStockSource != null
                && (!returned || uses != INFINITE_USES || remainder != null)) {
            throw new IllegalArgumentException(
                    "host-owned reusable stock requires an unchanged, infinitely reusable input");
        }
    }

    public static <K> CraftInput<K> of(K key, long amount) {
        return new CraftInput<>(key, amount, false, INFINITE_USES, null, null);
    }

    public static <K> CraftInput<K> returned(K key, long amount) {
        return new CraftInput<>(key, amount, true, INFINITE_USES, null, null);
    }

    /** A catalyst whose initial seed is borrowed from a host-private reusable-stock scope. */
    public static <K> CraftInput<K> returnedFrom(
            K key, long amount, ReusableStockSource source) {
        return new CraftInput<>(key, amount, true, INFINITE_USES, null,
                Objects.requireNonNull(source, "source"));
    }

    /** A degrading tool/finite catalyst: one {@code amount}-sized unit survives {@code uses} firings. */
    public static <K> CraftInput<K> finiteUse(K key, long amount, long uses) {
        return new CraftInput<>(key, amount, true, uses, null, null);
    }

    /**
     * A container input: {@code amount} of {@code key} are consumed per firing and the same count of
     * {@code remainder} (a different item, e.g. the empty bucket) is handed back as a byproduct.
     */
    public static <K> CraftInput<K> consumedReturning(K key, long amount, K remainder) {
        return new CraftInput<>(key, amount, false, INFINITE_USES,
                Objects.requireNonNull(remainder), null);
    }

    /** Units of {@link #key} consumed to fire the pattern {@code times} times (closed form). */
    public long unitsFor(long times) {
        if (!returned) {
            return Sat.mul(amount, times);
        }
        long unit = uses == INFINITE_USES ? 1L : Sat.ceilDiv(times, uses);
        return Sat.mul(amount, unit);
    }

    /** Max firings supportable if {@code available} units of {@link #key} are on hand (capacity bound). */
    public long firingsFrom(long available) {
        long perUnit = available / amount; // whole units usable
        if (!returned) {
            return perUnit;
        }
        return Sat.mul(perUnit, uses); // each unit yields `uses` firings (INFINITE_USES saturates)
    }
}
