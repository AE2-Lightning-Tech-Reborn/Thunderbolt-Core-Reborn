package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.Map;

/**
 * Compact result of {@link CraftPlannerV2#plan}.
 *
 * @param supported whether the solver accepted the request; the v2 planner always reports true
 * @param feasible whether stock and validated recipe execution cover the requested amount
 * @param firings pattern identity to firing count
 * @param usedStock item to amount drawn from the inventory snapshot
 * @param usedReusableStock host, logical pool and item to amount borrowed from private storage
 * @param missing shortages for the selected route, not a global infeasibility proof
 * @param grossDemand demand before stock withdrawal, used for AE2 byte accounting
 * @param itemsProcessed visited demand nodes; firing counts are handled in closed form
 * @param budgetExhausted whether a work or cycle-orientation limit prevented further search;
 *                        the hot-node re-ranking threshold does not set this flag
 * @param <K> item key type
 */
public record CraftPlan<K>(
        boolean supported,
        boolean feasible,
        Map<CraftPattern<K>, Long> firings,
        Map<K, Long> usedStock,
        Map<ReusableStockUsageKey<K>, Long> usedReusableStock,
        Map<K, Long> missing,
        Map<K, Long> grossDemand,
        int itemsProcessed,
        boolean budgetExhausted) {

    /** Exact weighted recipe execution cost; virtual tag transfers remain in {@link #firings}. */
    public BigInteger executionCount() {
        long total = 0;
        for (var entry : firings.entrySet()) {
            int cost = entry.getKey().executionCost();
            if (cost == 0) continue;
            long count = entry.getValue();
            if (count < 0 || cost != 1 && count > Long.MAX_VALUE / cost) return bigExecutionCount();
            long weighted = count * cost;
            if (total > Long.MAX_VALUE - weighted) return bigExecutionCount();
            total += weighted;
        }
        return BigInteger.valueOf(total);
    }

    private BigInteger bigExecutionCount() {
        BigInteger total = BigInteger.ZERO;
        for (var entry : firings.entrySet()) {
            if (entry.getKey().executionCost() != 0)
                total = total.add(BigInteger.valueOf(entry.getValue())
                        .multiply(BigInteger.valueOf(entry.getKey().executionCost())));
        }
        return total;
    }
}
