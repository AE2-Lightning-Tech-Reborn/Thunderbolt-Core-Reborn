package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Independent exact arithmetic; never uses Sat or CraftInput.unitsFor for the oracle. */
class ReplenishmentContractTest {
    @Test
    void rawShortfallRetainsTheCycleCutThatCausedIt() {
        for (boolean reverse : List.of(false, true)) {
            var graph = CraftGraph.<String>builder().stock("A", 1).stock("B", 2)
                    .pattern("B", 2, List.of(CraftInput.of("A", 1), CraftInput.of("E", 1)))
                    .pattern("E", 2, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)))
                    .pattern("E", 1, List.of(CraftInput.of("D", 1)))
                    .pattern("T", 1, reverse
                            ? List.of(CraftInput.of("E", 1), CraftInput.of("B", 1))
                            : List.of(CraftInput.of("B", 1), CraftInput.of("E", 1))).build();
            var plan = CraftPlannerV2.plan(graph, "T", 1);
            assertTrue(plan.feasible(), () -> plan.toString());
            assertExactBalance(graph, "T", 1, plan);
        }
    }

    @Test
    void boundedMissingIsSufficientForTheReportedRecipeCountsAndAReplan() {
        var random = new Random(20260917L);
        for (int sample = 0; sample < 500; sample++) {
            var graph = ordinaryDag(random);
            var plan = CraftPlannerV2.plan(graph, "M0", 4, 1, 1);
            assertExactBalance(graph, "M0", 4, plan);
            var replenished = graph.withAdditionalStock(plan.missing());
            var next = CraftPlannerV2.plan(replenished, "M0", 4, 1, 1);
            assertTrue(next.feasible(), "sample=" + sample + " first=" + plan + " next=" + next);
        }
    }

    @Test
    void aSessionRecomputesReplenishmentForEachAmountAfterAnOversizedProbe() {
        var random = new Random(20260917L);
        CraftGraph<String> graph = null;
        // This graph previously changed routes after replenishment and reported new missing items.
        for (int sample = 0; sample <= 68; sample++) graph = ordinaryDag(random);
        var session = new CraftPlannerV2.PlanningSession<String>();
        CraftPlannerV2.planDetailed(graph, "M0", Sat.SAT, session);
        for (long amount : new long[] {4, 1, 6, 2}) {
            var plan = CraftPlannerV2.planDetailed(graph, "M0", amount, session).plan();
            assertExactBalance(graph, "M0", amount, plan);
            var next = CraftPlannerV2.plan(graph.withAdditionalStock(plan.missing()), "M0", amount, 1, 1);
            assertTrue(next.feasible(), "amount=" + amount + " first=" + plan + " next=" + next);
        }
    }

    private static CraftGraph<String> ordinaryDag(Random random) {
        var b = CraftGraph.<String>builder();
        for (int i = 0; i < 8; i++) {
            b.stock("M" + i, random.nextInt(4));
            if (i >= 6) continue;
            for (int choice = 0; choice < 2; choice++) {
                var in = new ArrayList<CraftInput<String>>();
                in.add(CraftInput.of("M" + (i + 1 + random.nextInt(7 - i)), 1 + random.nextInt(3)));
                in.add(CraftInput.of("M" + (i + 1 + random.nextInt(7 - i)), 1 + random.nextInt(3)));
                b.pattern("M" + i, 1 + random.nextInt(2), in);
            }
        }
        return b.build();
    }

    @Test
    void cyclicOrdinaryGraphsCanExecuteTheirSupplementedPlanAndReplan() {
        var random = new Random(1230917L);
        for (int sample = 0; sample < 500; sample++) {
            var b = CraftGraph.<String>builder();
            for (int i = 0; i < 5; i++) b.stock("M" + i, random.nextInt(3));
            for (int r = 0; r < 8; r++) {
                int output = random.nextInt(5);
                int total = 1 + random.nextInt(3);
                var consumed = new LinkedHashMap<String, Long>();
                for (int j = 0; j < total; j++) {
                    int input;
                    do { input = random.nextInt(5); } while (input == output);
                    consumed.merge("M" + input, 1L, Long::sum);
                }
                b.pattern("M" + output, 1 + random.nextInt(total), consumed.entrySet().stream()
                        .map(e -> CraftInput.of(e.getKey(), e.getValue())).toList());
            }
            b.pattern("T", 1, List.of(CraftInput.of("M" + random.nextInt(5), 1),
                    CraftInput.of("M" + random.nextInt(5), 1)));
            var graph = b.build();
            var plan = CraftPlannerV2.plan(graph, "T", 1);
            assertExactBalance(graph, "T", 1, plan);
            var next = CraftPlannerV2.plan(graph.withAdditionalStock(plan.missing()), "T", 1);
            assertTrue(next.feasible(), "sample=" + sample + " first=" + plan + " next=" + next);
        }
    }

    static void assertExactBalance(CraftGraph<String> graph, String target, long amount, CraftPlan<String> plan) {
        Map<String, BigInteger> balance = new HashMap<>();
        plan.usedStock().forEach((key, used) -> {
            assertTrue(used >= 0 && used <= graph.stock(key), () -> key + " overdrawn");
            balance.merge(key, BigInteger.valueOf(used), BigInteger::add);
        });
        plan.missing().forEach((key, missing) -> balance.merge(key, BigInteger.valueOf(missing), BigInteger::add));
        plan.firings().forEach((pattern, count) -> {
            var times = BigInteger.valueOf(count);
            balance.merge(pattern.output(), times.multiply(BigInteger.valueOf(pattern.outputAmount())), BigInteger::add);
            for (var in : pattern.inputs()) {
                assertFalse(in.returned(), "ordinary-material oracle only");
                balance.merge(in.key(), times.multiply(BigInteger.valueOf(in.amount())).negate(), BigInteger::add);
            }
            for (var out : pattern.byproducts()) {
                balance.merge(out.key(), times.multiply(BigInteger.valueOf(out.amount())), BigInteger::add);
            }
        });
        balance.merge(target, BigInteger.valueOf(amount).negate(), BigInteger::add);
        balance.forEach((key, value) -> assertTrue(value.signum() >= 0,
                () -> "unsupplied " + key + ": " + value + " in " + plan));
        assertExecutable(target, amount, plan);
    }

    /** Execute only the reported counts with the exact extracted stock plus the advertised supply. */
    private static void assertExecutable(String target, long amount, CraftPlan<String> plan) {
        var inventory = new HashMap<String, BigInteger>();
        plan.usedStock().forEach((key, used) -> inventory.put(key, BigInteger.valueOf(used)));
        plan.missing().forEach((key, missing) -> inventory.merge(key, BigInteger.valueOf(missing), BigInteger::add));
        var remaining = new LinkedHashMap<>(plan.firings());
        while (!remaining.isEmpty()) {
            boolean progressed = false;
            var iterator = remaining.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                var times = BigInteger.valueOf(entry.getValue());
                var required = new HashMap<String, BigInteger>();
                for (var input : entry.getKey().inputs()) {
                    required.merge(input.key(), times.multiply(BigInteger.valueOf(input.amount())), BigInteger::add);
                }
                if (required.entrySet().stream().anyMatch(e ->
                        inventory.getOrDefault(e.getKey(), BigInteger.ZERO).compareTo(e.getValue()) < 0)) continue;
                required.forEach((key, count) -> inventory.merge(key, count.negate(), BigInteger::add));
                inventory.merge(entry.getKey().output(), times.multiply(BigInteger.valueOf(entry.getKey().outputAmount())), BigInteger::add);
                for (var out : entry.getKey().byproducts()) {
                    inventory.merge(out.key(), times.multiply(BigInteger.valueOf(out.amount())), BigInteger::add);
                }
                iterator.remove();
                progressed = true;
            }
            assertTrue(progressed, () -> "reported replenishment cannot execute " + plan);
        }
        assertTrue(inventory.getOrDefault(target, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
