package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot the planner runs against: patterns indexed by their output item, plus a
 * read-only inventory snapshot. The AE2 adapter builds this from {@code ICraftingService} +
 * the network storage snapshot; tests build it directly with {@code String} keys.
 *
 * @param <K> item key type
 */
public final class CraftGraph<K> {

    private final Map<K, List<CraftPattern<K>>> patternsByOutput;
    private final Map<K, Long> stock;
    private final Map<K, BigInteger> exactStock;
    private final Map<ReusableStockKey<K>, Long> reusableStock;
    private final Map<ReusableStockRouteKey<K>, List<K>> reusableStockRoutes;

    private CraftGraph(Map<K, List<CraftPattern<K>>> patternsByOutput, Map<K, Long> stock,
                       Map<ReusableStockKey<K>, Long> reusableStock,
                       Map<ReusableStockRouteKey<K>, List<K>> reusableStockRoutes, Map<K, BigInteger> exactStock) {
        this.patternsByOutput = patternsByOutput;
        this.stock = stock;
        this.exactStock = exactStock;
        this.reusableStock = reusableStock;
        this.reusableStockRoutes = reusableStockRoutes;
    }

    /** Patterns whose primary output is {@code key}, in caller-defined preference order. */
    public List<CraftPattern<K>> patternsFor(K key) {
        return patternsByOutput.getOrDefault(key, List.of());
    }

    /** Available amount of {@code key} in the snapshot (0 if none). */
    public BigInteger exactStock(K key) {
        return exactStock.getOrDefault(key, BigInteger.ZERO).max(BigInteger.ZERO);
    }

    public long stock(K key) {
        Long v = stock.get(key);
        return v == null ? 0L : Math.max(0L, v);
    }

    /** Host-private stock is invisible to ordinary demands and is addressed by reusable inputs only. */
    public long reusableStock(Object scope, K key) {
        Long value = reusableStock.get(new ReusableStockKey<>(scope, key));
        return value == null ? 0L : Math.max(0L, value);
    }

    /** Total physical stock accepted by this exact pattern route (capacity estimate only). */
    public long reusableStock(ReusableStockSource source, K plannedKey) {
        long total = 0L;
        for (var actual : reusableStockCandidates(source, plannedKey)) {
            PlanningCancellation.check();
            total = Sat.add(total, reusableStock(source.storageScope(), actual));
        }
        return total;
    }

    public List<K> reusableStockCandidates(ReusableStockSource source, K plannedKey) {
        var route = new ReusableStockRouteKey<>(source, plannedKey);
        var candidates = reusableStockRoutes.get(route);
        return candidates != null ? candidates : List.of(plannedKey);
    }

    /**
     * Returns the same immutable pattern/reusable-stock snapshot with ordinary stock added.
     * Reference adapters with host-private stock should rebuild through their host-aware graph
     * factory instead; this method is the safe default for ordinary inventory scenarios.
     */
    public CraftGraph<K> withAdditionalStock(Map<K, Long> additionalStock) {
        var merged = new HashMap<>(stock);
        var exactMerged = new HashMap<>(exactStock);
        additionalStock.forEach((key, amount) -> {
            if (key != null && amount != null && amount > 0) {
                merged.merge(key, amount, Sat::add);
                exactMerged.merge(key, BigInteger.valueOf(amount), BigInteger::add);
            }
        });
        return new CraftGraph<>(patternsByOutput, Map.copyOf(merged), reusableStock,
                reusableStockRoutes, Map.copyOf(exactMerged));
    }

    /** Read-only recipe projection over exactly the same inventory snapshot. */
    CraftGraph<K> withPatterns(Map<K, List<CraftPattern<K>>> selected) {
        var frozen = new HashMap<K, List<CraftPattern<K>>>();
        selected.forEach((key, patterns) -> frozen.put(key, List.copyOf(patterns)));
        return new CraftGraph<>(Map.copyOf(frozen), stock, reusableStock, reusableStockRoutes, exactStock);
    }

    /** An optimization probe can only withdraw stock already used by its incumbent. */
    CraftGraph<K> withStockLimits(Map<K, Long> limits) {
        var limited = new HashMap<K, Long>();
        var exact = new HashMap<K, BigInteger>();
        limits.forEach((key, amount) -> {
            if (amount < 0 || amount > stock(key)) throw new IllegalArgumentException("invalid stock limit");
            if (amount > 0) {
                limited.put(key, amount);
                exact.put(key, BigInteger.valueOf(amount));
            }
        });
        return new CraftGraph<>(patternsByOutput, Map.copyOf(limited), reusableStock,
                reusableStockRoutes, Map.copyOf(exact));
    }

    /** Residual ordinary stock for a prefix plan; committed draws cannot be spent a second time. */
    CraftGraph<K> withoutStock(Map<K, Long> withdrawn) {
        var remaining = new HashMap<>(stock);
        var exactRemaining = new HashMap<>(exactStock);
        withdrawn.forEach((key, amount) -> {
            if (amount < 0 || amount > stock(key)) throw new IllegalArgumentException("invalid stock draw");
            remaining.put(key, stock(key) - amount);
            exactRemaining.put(key, exactStock(key).subtract(BigInteger.valueOf(amount)));
        });
        return new CraftGraph<>(patternsByOutput, Map.copyOf(remaining), reusableStock,
                reusableStockRoutes, Map.copyOf(exactRemaining));
    }

    Map<ReusableStockKey<K>, Long> reusableStock() {
        return reusableStock;
    }

    public static <K> Builder<K> builder() {
        return new Builder<>();
    }

    public static final class Builder<K> {
        private final Map<K, List<CraftPattern<K>>> patterns = new HashMap<>();
        private final Map<K, Long> stock = new HashMap<>();
        private final Map<K, BigInteger> exactStock = new HashMap<>();
        private final Map<ReusableStockKey<K>, Long> reusableStock = new HashMap<>();
        private final Map<ReusableStockRouteKey<K>, LinkedHashSet<K>> reusableStockRoutes =
                new HashMap<>();

        /** Adds a pattern; patterns for the same output keep insertion order (= preference order). */
        public Builder<K> pattern(CraftPattern<K> pattern) {
            patterns.computeIfAbsent(pattern.output(), k -> new ArrayList<>()).add(pattern);
            return this;
        }

        public Builder<K> pattern(K output, long outAmount, List<CraftInput<K>> inputs) {
            return pattern(new CraftPattern<>(output, outAmount, inputs, null));
        }

        public Builder<K> pattern(K output, long outAmount, List<CraftInput<K>> inputs,
                                  List<CraftOutput<K>> byproducts) {
            return pattern(new CraftPattern<>(output, outAmount, inputs, byproducts, null));
        }

        public Builder<K> stock(K key, long amount) {
            // Saturating: a durability carrier's aggregate uses can already sit at the saturation
            // cap; a plain Long::sum could overflow negative and stock() would clamp it to zero,
            // turning a huge supply into a false shortfall.
            exactStock.merge(key, BigInteger.valueOf(amount), BigInteger::add);
            stock.merge(key, amount, Sat::add);
            return this;
        }

        public Builder<K> stockExact(K key, BigInteger amount) {
            if (amount.signum() < 0) throw new IllegalArgumentException("negative stock");
            ExactDiagnosticPlanner.checked(amount);
            exactStock.merge(key, amount, BigInteger::add);
            stock.merge(key, amount.min(BigInteger.valueOf(Sat.SAT)).longValueExact(), Sat::add);
            return this;
        }

        /**
         * Publishes one snapshot of a physical host inventory. Repeated patterns from the same host
         * report the same inventory, so snapshots merge by maximum rather than being double-counted.
         */
        public Builder<K> reusableStock(Object scope, K key, long amount) {
            if (amount > 0) {
                reusableStock.merge(new ReusableStockKey<>(scope, key), amount, Math::max);
            }
            return this;
        }

        /** Registers the concrete physical variants accepted by one pattern-specific seed route. */
        public Builder<K> reusableStockRoute(
                ReusableStockSource source, K plannedKey, Iterable<? extends K> actualVariants) {
            var route = new ReusableStockRouteKey<>(source, plannedKey);
            var accepted = reusableStockRoutes.computeIfAbsent(route, ignored -> new LinkedHashSet<>());
            for (var actual : actualVariants) {
                PlanningCancellation.check();
                if (actual != null) accepted.add(actual);
            }
            return this;
        }

        public CraftGraph<K> build() {
            Map<K, List<CraftPattern<K>>> frozen = new HashMap<>();
            for (var entry : patterns.entrySet()) {
                PlanningCancellation.check();
                frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            var frozenRoutes = new HashMap<ReusableStockRouteKey<K>, List<K>>();
            for (var entry : reusableStockRoutes.entrySet()) {
                PlanningCancellation.check();
                frozenRoutes.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new CraftGraph<>(frozen, Map.copyOf(stock), Map.copyOf(reusableStock),
                    Map.copyOf(frozenRoutes), Map.copyOf(exactStock));
        }
    }
}
