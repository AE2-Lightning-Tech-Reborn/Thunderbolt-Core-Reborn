package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WideOrdinaryAllocationTest {
    @Test
    void allEightyKnownFeasibleWideAssignmentsStayWithinThreeSeconds() {
        for (int outputs : new int[] {8, 16, 32, 64}) for (int seed = 0; seed < 10; seed++)
            for (long n : new long[] {1, 1_000_000_000_000L}) {
                var graph = assignment(outputs, n, seed);
                var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                        () -> CraftPlannerV2.planDetailed(graph, "T", n));
                assertTrue(result.plan().feasible(), outputs + ":" + seed + ":" + n);
                check(graph, result.plan(), n);
            }
    }

    @Test
    void actualMatrixSizeAllowsWidthSixteenTwoInputAssignments() {
        for (int outputs : new int[] {16, 32}) for (int seed : new int[] {6, 7, 8})
            for (long n : new long[] {1, 1_000_000_000_000L}) {
                var graph = assignment(outputs, n, seed);
                var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                        () -> CraftPlannerV2.planDetailed(graph, "T", n));
                assertTrue(result.plan().feasible(), () -> result.diagnostics().toString());
                assertTrue(result.diagnostics().separatorWidthPeak() > 12);
                assertTrue(result.diagnostics().lowWidthSolved() > 0);
                check(graph, result.plan(), n);
            }
    }

    @Test
    void oneMissingPhysicalUnitCannotBeHiddenByTheCompactMatrix() {
        for (int outputs : new int[] {16, 32}) for (long n : new long[] {1, 1_000_000_000_000L}) {
            var original = assignment(outputs, n, 6);
            int raw = 0;
            while (original.stock("R"+raw) == 0) raw++;
            var graph = original.withoutStock(Map.of("R"+raw, 1L));
            var plan = CraftPlannerV2.plan(graph, "T", n);
            // All alternatives consume exactly two raw units per primary output. The known
            // inventory had exactly that total, so removing one unit is an independent proof.
            assertFalse(plan.feasible());
            assertFalse(plan.missing().isEmpty());
            plan.missing().forEach((key, amount) -> assertTrue(graph.patternsFor(key).isEmpty()));
            check(graph, plan, n);
            var supplied = graph.withAdditionalStock(plan.missing());
            var ready = CraftPlannerV2.plan(supplied, "T", n, 1, 1);
            assertTrue(ready.feasible());
            check(supplied, ready, n);
        }
    }

    private static CraftGraph<String> assignment(int outputs, long n, int seed) {
        var random = new Random(2026092300L+seed);
        var builder = CraftGraph.<String>builder();
        var root = new ArrayList<CraftInput<String>>();
        int[] stock = new int[16];
        for (int i = 0; i < outputs; i++) {
            int witness = random.nextInt(3);
            for (int route = 0; route < 3; route++) {
                int a = random.nextInt(16), b;
                do { b = random.nextInt(16); } while (a == b);
                builder.pattern("P"+i, 1, List.of(CraftInput.of("R"+a, 1), CraftInput.of("R"+b, 1)));
                if (route == witness) { stock[a]++; stock[b]++; }
            }
            root.add(CraftInput.of("P"+i, 1));
        }
        for (int r = 0; r < 16; r++) builder.stock("R"+r, stock[r]*n);
        return builder.pattern("T", 1, root).build();
    }

    private static void check(CraftGraph<String> graph, CraftPlan<String> plan, long amount) {
        var balance = new HashMap<String, BigInteger>();
        plan.usedStock().forEach((key, n) -> {
            assertTrue(n <= graph.stock(key));
            balance.put(key, BigInteger.valueOf(n));
        });
        plan.missing().forEach((key, n) -> balance.merge(key, BigInteger.valueOf(n), BigInteger::add));
        plan.firings().forEach((p, count) -> {
            var n = BigInteger.valueOf(count);
            p.inputs().forEach(i -> balance.merge(i.key(), i.exactAmount().multiply(n).negate(), BigInteger::add));
            balance.merge(p.output(), p.exactOutputAmount().multiply(n), BigInteger::add);
        });
        assertTrue(balance.values().stream().allMatch(v -> v.signum() >= 0));
        assertTrue(balance.getOrDefault("T", BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
