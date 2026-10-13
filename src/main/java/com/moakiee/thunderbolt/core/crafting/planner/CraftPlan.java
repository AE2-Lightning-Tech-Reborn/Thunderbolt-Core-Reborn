package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.Map;

/**
 * Result of {@link CraftPlannerV2#plan}.
 *
 * @param supported     {@code false} means the fast path declined (e.g. recursion/cycle detected);
 *                      caller must fall back to AE2's simulator. When {@code false} all other fields
 *                      are empty/zero. The v2 planner always plans, so it always reports {@code true}.
 * @param feasible      {@code true} if the requested amount can be fully crafted from current stock.
 *                      When {@code false}, {@link #missing} lists what is short (a partial plan is
 *                      still provided for the craftable part).
 * @param firings       pattern -> number of times to fire it (the compact plan). Keyed by pattern
 *                      object identity.
 * @param usedStock     item -> amount drawn directly from the inventory snapshot.
 * @param usedReusableStock host + logical pool + item -> amount borrowed from private storage.
 * @param missing       item -> amount that could not be obtained (raw leaves under DEEP mode).
 * @param grossDemand   item -> total amount requested before drawing from stock (one entry per
 *                      visited item). Exposed so the AE2 adapter can reproduce AE2's byte accounting
 *                      ({@code addStackBytes} is charged on the pre-extraction request amount).
 * @param itemsProcessed number of items visited by the linear demand pass, or recursive node
 *                       invocations performed by the bounded fallback. Request magnitude does not
 *                       affect this value because every firing count is handled in closed form.
 * @param budgetExhausted {@code true} when a shared work budget or cycle-orientation limit prevented
 *                       further search. This is an internal search diagnostic, not a separate user
 *                       result. Missing items describe the selected route; even a completed search
 *                       does not prove that every possible route needs those items. Ordinary material
 *                       DAGs within the executable amount range additionally validate replenishment
 *                       against a stock-independent recipe policy. A hot-node visit threshold merely
 *                       changes route ordering and does not set this flag.
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
