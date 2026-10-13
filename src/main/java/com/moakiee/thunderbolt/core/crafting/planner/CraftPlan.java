package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.Map;

/**
 * Result of {@link CraftPlannerV2#plan}.
 *
 * @param supported     false declines to the host simulator with empty fields; v2 always reports true
 * @param feasible      whether stock satisfies the full request; otherwise a partial plan and missing
 *                      materials are provided
 * @param firings       compact firing counts keyed by pattern identity
 * @param usedStock     item -> amount drawn directly from the inventory snapshot.
 * @param usedReusableStock host + logical pool + item -> amount borrowed from private storage.
 * @param missing       item -> amount that could not be obtained (raw leaves under DEEP mode).
 * @param grossDemand   pre-extraction demand per visited item, used for AE2 {@code addStackBytes}
 * @param itemsProcessed linear-pass items or fallback node invocations, not individual firings
 * @param budgetExhausted shared work or orientation limit stopped search; a hot-node threshold only
 *                       reorders routes. Missing materials describe the selected route, not a global
 *                       impossibility proof. Ordinary material DAGs additionally check replenishment
 *                       against a stock-independent recipe policy within the executable amount range.
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
