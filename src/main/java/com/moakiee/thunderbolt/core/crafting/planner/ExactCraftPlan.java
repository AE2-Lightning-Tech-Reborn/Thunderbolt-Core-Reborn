package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.Map;

/** Exact quantities for a diagnostic plan. This type deliberately has no execution adapter. */
public record ExactCraftPlan<K>(
        Map<CraftPattern<K>, BigInteger> firings,
        Map<K, BigInteger> usedStock,
        Map<K, BigInteger> missing,
        Map<K, BigInteger> grossDemand,
        boolean incomplete) {
    public ExactCraftPlan {
        firings = Map.copyOf(firings);
        usedStock = Map.copyOf(usedStock);
        missing = Map.copyOf(missing);
        grossDemand = Map.copyOf(grossDemand);
    }
}
