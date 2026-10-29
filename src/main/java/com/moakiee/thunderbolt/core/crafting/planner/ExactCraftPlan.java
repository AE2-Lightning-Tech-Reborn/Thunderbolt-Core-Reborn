package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

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

    /** AE2-compatible byte cost, retaining fractions until the final upward rounding. */
    public BigInteger byteCost(ToIntFunction<K> amountPerByte, Set<K> durability) {
        BigInteger numerator = BigInteger.ZERO;
        BigInteger denominator = BigInteger.ONE;
        for (var entry : grossDemand.entrySet()) {
            PlanningCancellation.check();
            BigInteger divisor = BigInteger.valueOf(Math.max(1, amountPerByte.applyAsInt(entry.getKey())));
            BigInteger gcd = denominator.gcd(divisor);
            BigInteger factor = divisor.divide(gcd);
            numerator = numerator.multiply(factor).add(entry.getValue().multiply(BigInteger.valueOf(8))
                    .multiply(denominator.divide(gcd)));
            denominator = denominator.multiply(factor);
        }
        BigInteger total = ExactDiagnosticPlanner.ceilDiv(numerator, denominator)
                .add(BigInteger.valueOf(grossDemand.size()).multiply(BigInteger.valueOf(8)));
        for (var entry : firings.entrySet()) {
            total = total.add(entry.getValue());
            for (var input : entry.getKey().inputs()) {
                if (input.returned() || input.remainder() != null || durability.contains(input.key())) {
                    total = total.add(input.exactAmount().multiply(entry.getValue()));
                }
            }
        }
        return ExactDiagnosticPlanner.checked(total);
    }
}
