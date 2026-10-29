package com.moakiee.thunderbolt.core.crafting.big;

import com.moakiee.thunderbolt.core.storage.big.BigAmounts;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded physical transfer from an exact order to a legacy provider. */
public record BigLongSlice<K>(long copies, Map<K, BigInteger> inputs, Map<K, BigInteger> outputs) {
    public BigLongSlice {
        if (copies < 0) throw new IllegalArgumentException("negative copies");
        inputs = Map.copyOf(inputs);
        outputs = Map.copyOf(outputs);
    }

    public static <K> BigLongSlice<K> plan(
            BigInteger remaining, Map<K, BigInteger> stock,
            Map<K, Long> inputs, Map<K, Long> sharedInputs,
            Map<K, Long> outputs, Map<K, Long> sharedOutputs, long capacity) {
        long copies = BigAmounts.nonNegative(remaining)
                .min(BigInteger.valueOf(Math.max(0, capacity))).longValueExact();
        copies = bounded(copies, inputs, sharedInputs, stock);
        copies = bounded(copies, outputs, sharedOutputs, null);
        return of(copies, inputs, sharedInputs, outputs, sharedOutputs);
    }

    public static <K> BigLongSlice<K> of(long copies,
            Map<K, Long> inputs, Map<K, Long> sharedInputs,
            Map<K, Long> outputs, Map<K, Long> sharedOutputs) {
        if (copies < 0) throw new IllegalArgumentException("negative copies");
        return new BigLongSlice<>(copies, scaled(inputs, sharedInputs, copies),
                scaled(outputs, sharedOutputs, copies));
    }

    private static <K> long bounded(long copies, Map<K, Long> quantities,
            Map<K, Long> shared, Map<K, BigInteger> stock) {
        for (var entry : quantities.entrySet()) {
            long fixed = shared.getOrDefault(entry.getKey(), 0L);
            long each = entry.getValue() - fixed;
            if (fixed < 0 || entry.getValue() < 0 || each < 0)
                throw new IllegalArgumentException("invalid shared amount");
            var available = stock == null ? BigAmounts.LONG_MAX
                    : stock.getOrDefault(entry.getKey(), BigInteger.ZERO).min(BigAmounts.LONG_MAX);
            available = available.subtract(BigInteger.valueOf(fixed));
            if (available.signum() < 0) return 0;
            if (each > 0) copies = Math.min(copies,
                    available.divide(BigInteger.valueOf(each)).longValueExact());
        }
        return copies;
    }

    private static <K> Map<K, BigInteger> scaled(
            Map<K, Long> quantities, Map<K, Long> shared, long copies) {
        if (copies == 0) return Map.of();
        var result = new LinkedHashMap<K, BigInteger>();
        quantities.forEach((key, total) -> {
            long fixed = shared.getOrDefault(key, 0L);
            if (total < 0 || fixed < 0 || fixed > total)
                throw new IllegalArgumentException("invalid shared amount");
            // Check the physical long boundary even though the owning job uses exact counters.
            long amount = Math.addExact(fixed, Math.multiplyExact(total - fixed, copies));
            if (amount > 0) result.put(key, BigInteger.valueOf(amount));
        });
        return result;
    }
}
