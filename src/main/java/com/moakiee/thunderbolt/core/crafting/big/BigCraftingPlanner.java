package com.moakiee.thunderbolt.core.crafting.big;

import com.moakiee.thunderbolt.core.crafting.planner.*;
import com.moakiee.thunderbolt.core.storage.big.BigAmounts;

import java.math.BigInteger;
import java.util.*;

/** Exact candidate generation followed by an independent executable-prefix certificate. */
public final class BigCraftingPlanner {
    private BigCraftingPlanner() {}

    public record Result<K>(
            BigExecutionProgram<K> program, Map<K, BigInteger> missing, boolean verified) {
        public Result {
            missing = Map.copyOf(missing);
        }

        public boolean executable() {
            return verified && missing.isEmpty();
        }
    }

    public static <K> Result<K> plan(
            CraftGraph<K> graph, K target, BigInteger amount, Map<K, BigInteger> stock) {
        if (BigAmounts.nonNegative(amount).signum() == 0)
            throw new IllegalArgumentException("amount");
        var candidate =
                CraftPlannerV2.planExactDiagnostic(
                        graph, target, amount, new CraftPlannerV2.PlanningSession<>(), Set.of());
        var supplied = new LinkedHashMap<K, BigInteger>(stock);
        // A craft request always makes fresh output, matching the native AE2 order semantics.
        supplied.remove(target);
        candidate.missing().forEach((k, n) -> supplied.merge(k, n, BigInteger::add));
        var certified = BigExecutionProgram.certify(candidate.firings(), supplied);
        if (certified.isEmpty()) return new Result<>(null, candidate.missing(), false);
        var program = certified.get();
        var finalStock = new LinkedHashMap<>(program.required());
        BigExecutionProgram.apply(finalStock, program.delta(), BigInteger.ONE);
        if (finalStock.getOrDefault(target, BigInteger.ZERO).compareTo(amount) < 0)
            return new Result<>(null, candidate.missing(), false);
        var missing = new LinkedHashMap<K, BigInteger>();
        program.required()
                .forEach(
                        (k, n) -> {
                            var have =
                                    Objects.equals(k, target)
                                            ? BigInteger.ZERO
                                            : stock.getOrDefault(k, BigInteger.ZERO);
                            var shortage = n.subtract(have);
                            if (shortage.signum() > 0) missing.put(k, shortage);
                        });
        return new Result<>(program, missing, true);
    }
}
