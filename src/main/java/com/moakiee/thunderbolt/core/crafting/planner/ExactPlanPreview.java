package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReport;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports;

/** Sole boundary from arbitrary precision planning to AE2's display-only long projection. */
final class ExactPlanPreview {
    private ExactPlanPreview() {}

    static boolean needsExact(CraftPlan<AEKey> plan) {
        if (wide(plan.grossDemand()) || wide(plan.firings()) || wide(plan.usedStock()) || wide(plan.missing())) {
            return true;
        }
        Map<AEKey, BigInteger> produced = new HashMap<>();
        for (var entry : plan.firings().entrySet()) {
            BigInteger times = BigInteger.valueOf(entry.getValue());
            CraftPattern<AEKey> pattern = entry.getKey();
            for (var input : pattern.inputs()) {
                if (input.exactAmount().compareTo(BigInteger.valueOf(Sat.SAT)) >= 0) return true;
            }
            produced.merge(pattern.output(), times.multiply(pattern.exactOutputAmount()), BigInteger::add);
            for (var output : pattern.byproducts()) {
                produced.merge(output.key(), times.multiply(output.exactAmount()), BigInteger::add);
            }
        }
        return produced.values().stream().anyMatch(value -> value.compareTo(BigInteger.valueOf(Sat.SAT)) >= 0);
    }

    private static boolean wide(Map<?, Long> amounts) {
        return amounts.values().stream().anyMatch(Sat::isSaturated);
    }

    static CraftingPlan create(AEKey output, long amount, boolean multiplePaths,
            ExactCraftPlan<AEKey> plan, Map<AEKey, DurabilityChain<AEKey>> durability, Set<AEKey> emittable) {
        Map<AEKey, BigInteger> used = new HashMap<>();
        Map<AEKey, BigInteger> missing = new HashMap<>();
        Map<AEKey, BigInteger> produced = new HashMap<>();
        Map<AEKey, BigInteger> emitted = new HashMap<>();
        plan.usedStock().forEach((key, value) -> {
            if (emittable.contains(key)) {
                emitted.merge(key, value, BigInteger::add);
            } else if (durability.containsKey(key)) {
                durability.get(key).chargeFromStockExact(value,
                        (actual, count) -> used.merge(actual, count, BigInteger::add));
            } else used.merge(key, value, BigInteger::add);
        });
        plan.missing().forEach((key, value) -> {
            if (emittable.contains(key)) emitted.merge(key, value, BigInteger::add);
            else {
                var chain = durability.get(key);
                missing.merge(key, chain == null ? value
                        : ExactDiagnosticPlanner.ceilDiv(value, BigInteger.valueOf(chain.n())), BigInteger::add);
            }
        });
        // A real pattern may have multiple concrete graph routes. Sum their exact firing counts
        // once per real pattern before enumerating its physical outputs (not durability tokens).
        Map<IPatternDetails, BigInteger> firings = new HashMap<>();
        plan.firings().forEach((pattern, count) -> firings.merge(
                (IPatternDetails) pattern.source(), count, BigInteger::add));
        firings.forEach((pattern, count) -> {
            for (var stack : pattern.getOutputs()) {
                produced.merge(stack.what(), BigInteger.valueOf(stack.amount()).multiply(count), BigInteger::add);
            }
        });
        emitted.forEach((key, value) -> {
            used.merge(key, value, BigInteger::add);
            produced.merge(key, value, BigInteger::add);
        });
        Set<AEKey> keys = new java.util.HashSet<>(used.keySet());
        keys.addAll(missing.keySet());
        keys.addAll(produced.keySet());
        Map<AEKey, ExactPlanReport.Amounts> entries = new HashMap<>();
        for (var key : keys) {
            entries.put(key, new ExactPlanReport.Amounts(used.getOrDefault(key, BigInteger.ZERO),
                    missing.getOrDefault(key, BigInteger.ZERO), produced.getOrDefault(key, BigInteger.ZERO)));
        }
        BigInteger bytes = bytes(plan, durability.keySet());
        // No executable pattern counts cross this boundary. simulation=true also blocks native
        // auto-start, and the exact attachment is checked at the server submission boundary.
        var projection = new CraftingPlan(new GenericStack(output, amount), ExactPlanReport.project(bytes),
                true, multiplePaths, project(used), new KeyCounter(), project(missing), Map.of());
        ExactPlanReports.attach(projection, new ExactPlanReport(bytes, entries, plan.incomplete()));
        return projection;
    }

    static BigInteger bytes(ExactCraftPlan<AEKey> plan, Set<AEKey> durability) {
        BigInteger numerator = BigInteger.ZERO;
        BigInteger denominator = BigInteger.ONE;
        for (var entry : plan.grossDemand().entrySet()) {
            PlanningCancellation.check();
            BigInteger divisor = BigInteger.valueOf(Math.max(1, entry.getKey().getType().getAmountPerByte()));
            BigInteger gcd = denominator.gcd(divisor);
            BigInteger factor = divisor.divide(gcd);
            numerator = numerator.multiply(factor).add(entry.getValue().multiply(BigInteger.valueOf(8))
                    .multiply(denominator.divide(gcd)));
            denominator = denominator.multiply(factor);
        }
        BigInteger total = ExactDiagnosticPlanner.ceilDiv(numerator, denominator)
                .add(BigInteger.valueOf(plan.grossDemand().size()).multiply(BigInteger.valueOf(8)));
        for (var entry : plan.firings().entrySet()) {
            total = total.add(entry.getValue());
            for (var input : entry.getKey().inputs()) {
                if (input.returned() || input.remainder() != null || durability.contains(input.key())) {
                    total = total.add(input.exactAmount().multiply(entry.getValue()));
                }
            }
        }
        return ExactDiagnosticPlanner.checked(total);
    }

    private static KeyCounter project(Map<AEKey, BigInteger> exact) {
        var counter = new KeyCounter();
        exact.forEach((key, value) -> counter.set(key, ExactPlanReport.project(value)));
        return counter;
    }
}
