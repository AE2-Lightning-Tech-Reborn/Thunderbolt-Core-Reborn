package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ByproductReplaySafetyTest {
    @Test
    void singleProducerMayReplaceStockToSupplyTheSiblingByproduct() {
        for (long n : new long[] {1, 2, 1_000_000, 1_000_000_000_000L}) {
            var primary = new CraftPattern<>("A", 1, List.of(CraftInput.of("raw", 1),
                    CraftInput.of("fuel", 1)), List.of(CraftOutput.of("B", 1)), "primary");
            var secondary = new CraftPattern<>("B", 1,
                    List.of(CraftInput.of("A", 1), CraftInput.of("fuel", 1)), "secondary");
            var graph = CraftGraph.<String>builder().stock("A", n).stock("raw", n).stock("fuel", 2*n)
                    .pattern(primary).pattern(secondary).pattern("T", 1, List.of(CraftInput.of("B", 2))).build();
            var plan = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CraftPlannerV2.plan(graph, "T", n));
            assertTrue(plan.feasible(), () -> "n="+n+" "+plan);
            long produced = plan.firings().get(primary);
            assertTrue(produced > 0 && produced <= n);
            assertEquals(2*n, produced + plan.firings().get(secondary));
            assertEquals(2*n-2*produced, plan.usedStock().getOrDefault("A", 0L));
            assertEquals(2*n, plan.usedStock().get("fuel"));
            if (n <= 2) assertEveryOrderFinishes(plan, "T", n);
        }
    }

    @Test
    void deepSiblingByproductIsAvailableRegardlessOfInputOrder() {
        for (boolean reverse : List.of(false, true)) {
            for (long n : new long[] {1, 2, 1_000_000, 1_000_000_000_000L}) {
                var makeA = new CraftPattern<>("A", 1, List.of(CraftInput.of("raw", 2),
                        CraftInput.of("fuel", 1)), List.of(CraftOutput.of("side", 1)), "makeA");
                var inputs = new ArrayList<>(List.of(CraftInput.of("side", 1), CraftInput.of("B", 1)));
                if (reverse) java.util.Collections.reverse(inputs);
                var graph = CraftGraph.<String>builder().stock("raw", 3*n).stock("fuel", n)
                        .stock("A", n).stock("B", n).pattern(makeA)
                        .pattern("B", 1, List.of(CraftInput.of("A", 1))).pattern("T", 1, inputs).build();
                var plan = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CraftPlannerV2.plan(graph, "T", n));
                assertTrue(plan.feasible(), () -> "n="+n+" reverse="+reverse+" "+plan);
                assertEquals(n, plan.firings().get(makeA));
                assertEquals(Map.of("raw", 2*n, "fuel", n), plan.usedStock());
                if (n <= 2) assertEveryOrderFinishes(plan, "T", n);
            }
        }
    }

    @Test
    void aggregateBoundaryMustPreserveThePrimaryDemandFundingASideOutput() {
        for (boolean reverse : List.of(false, true)) {
            for (long n : new long[] {1, 2, 1_000_000, 1_000_000_000_000L}) {
                var sideProducer = new CraftPattern<>("B", 1,
                        List.of(CraftInput.of("raw", 1), CraftInput.of("fuel", 2)),
                        List.of(CraftOutput.of("side", 1)), "sideProducer");
                var inputs = new ArrayList<>(List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)));
                if (reverse) java.util.Collections.reverse(inputs);
                var graph = CraftGraph.<String>builder().stock("B", n)
                        .stock("raw", 3*n).stock("fuel", 2*n)
                        .pattern("A", 1, List.of(CraftInput.of("side", 1)))
                        .pattern("B", 2, List.of(CraftInput.of("raw", 1), CraftInput.of("fuel", 1)))
                        .pattern(sideProducer).pattern("T", 1, inputs).build();
                var plan = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CraftPlannerV2.plan(graph, "T", n));
                assertTrue(plan.feasible(), () -> "n="+n+" reverse="+reverse+" "+plan);
                assertEquals(n, plan.firings().get(sideProducer));
                assertEquals(Map.of("raw", n, "fuel", 2*n), plan.usedStock());
                assertEquals(n, plan.grossDemand().get("A"), "the aggregate boundary owns one demand record");
                if (n <= 2) assertEveryOrderFinishes(plan, "T", n);
            }
        }
    }

    @Test
    void competingRoutesBelowAWrapperStillProduceTheRequiredSiblingSideOutput() {
        for (boolean reverse : List.of(false, true)) {
            for (long n : new long[] {1, 2, 1_000_000, 1_000_000_000_000L}) {
                var sideProducer = new CraftPattern<>("B", 1,
                        List.of(CraftInput.of("raw", 1), CraftInput.of("fuel", 1)),
                        List.of(CraftOutput.of("side", 1)), "sideProducer");
                var inputs = new ArrayList<>(List.of(CraftInput.of("side", 1), CraftInput.of("C", 1)));
                if (reverse) java.util.Collections.reverse(inputs);
                var graph = CraftGraph.<String>builder().stock("B", 3*n)
                        .stock("raw", n).stock("fuel", 2*n)
                        .pattern("B", 1, List.of(CraftInput.of("fuel", 1)))
                        .pattern(sideProducer).pattern("C", 1, List.of(CraftInput.of("B", 1)))
                        .pattern("T", 1, inputs).build();
                var plan = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CraftPlannerV2.plan(graph, "T", n));
                assertTrue(plan.feasible(), () -> "n="+n+" reverse="+reverse+" "+plan);
                assertEquals(n, plan.firings().get(sideProducer));
                assertEquals(Map.of("raw", n, "fuel", n), plan.usedStock());
                if (n <= 2) assertEveryOrderFinishes(plan, "T", n);
            }
        }
    }

    @Test
    void aSiblingCannotStealTheLastSelfReturnSeed() {
        for (long returned : new long[] {1, 2}) for (long stock : new long[] {1, 2}) {
            var graph = CraftGraph.<String>builder().stock("seed", stock)
                    .pattern("P", 1, List.of(CraftInput.of("seed", 1)), List.of(CraftOutput.of("seed", returned)))
                    .pattern("Q", 1, List.of(CraftInput.of("seed", 1)))
                    .pattern("T", 1, List.of(CraftInput.of("P", 1), CraftInput.of("Q", 1))).build();
            var plan = CraftPlannerV2.plan(graph, "T", 1);
            assertEquals(stock == 2, plan.feasible());
            assertEquals(2-stock, plan.missing().getOrDefault("seed", 0L));
            assertEveryOrderFinishes(plan, "T", 1);
            var ready = CraftPlannerV2.plan(graph.withAdditionalStock(plan.missing()), "T", 1);
            assertTrue(ready.feasible(), () -> ready.toString());
            assertEveryOrderFinishes(ready, "T", 1);
        }
    }

    @Test
    void sharedSelfReturnReserveDoesNotGrowWithFiringCount() {
        long n = 1_000_000_000_000L;
        var graph = CraftGraph.<String>builder().stock("seed", n+1)
                .pattern("P", 1, List.of(CraftInput.of("seed", 1)), List.of(CraftOutput.of("seed", 1)))
                .pattern("Q", 1, List.of(CraftInput.of("seed", 1)))
                .pattern("T", 1, List.of(CraftInput.of("P", 1), CraftInput.of("Q", 1))).build();
        var plan = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CraftPlannerV2.plan(graph, "T", n));
        assertTrue(plan.feasible(), () -> plan.toString());
        assertEquals(n+1, plan.usedStock().get("seed"));
    }

    @Test
    void aWeightedLoopReservesItsStartupAfterAnIndependentSinkRunsFirst() {
        var graph = CraftGraph.<String>builder().stock("A", 6)
                .pattern("B", 2, List.of(CraftInput.of("A", 3)))
                .pattern("T", 1, List.of(CraftInput.of("B", 3)), List.of(CraftOutput.of("A", 4)))
                .pattern("side", 1, List.of(CraftInput.of("A", 5)))
                .pattern("root", 1, List.of(CraftInput.of("T", 2), CraftInput.of("side", 1))).build();
        var plan = CraftPlannerV2.plan(graph, "root", 1);
        assertFalse(plan.feasible(), "side may consume 5 A first, leaving only 1 A for a 3-A recipe");
        assertFalse(plan.missing().isEmpty());
        assertTrue(java.util.Set.of("A", "B").containsAll(plan.missing().keySet()));
        assertEveryOrderFinishes(plan, "root", 1);
        var ready = CraftPlannerV2.plan(graph.withAdditionalStock(plan.missing()), "root", 1);
        assertTrue(ready.feasible());
        assertEveryOrderFinishes(ready, "root", 1);
    }

    @Test
    void aSeedCannotBeFundedByItsOwnDownstreamProducer() {
        // One root firing can steal both initial A and seed. The remaining seed maker then
        // needs A from the producer that is itself waiting for that seed.
        for (long n : new long[] {1, 2, 1_000_000, 1_000_000_000_000L}) {
            var producer = new CraftPattern<>("A", 2,
                    List.of(CraftInput.of("seed", 1), CraftInput.of("fuel", 2)),
                    List.of(CraftOutput.of("seed", 1)), "producer");
            var refill = new CraftPattern<>("seed", 3,
                    List.of(CraftInput.of("A", 1), CraftInput.of("raw", 2)), "refill");
            var root = new CraftPattern<>("T", 1,
                    List.of(CraftInput.of("A", 1), CraftInput.of("seed", 1)), "root");
            var stock = Map.of("A", n, "seed", n, "fuel", 2*n, "raw", 2*n);
            var graph = CraftGraph.<String>builder().stock("A", n).stock("seed", n)
                    .stock("fuel", 2*n).stock("raw", 2*n)
                    .pattern(producer).pattern(refill).pattern(root).build();
            var orderedOnly = new CraftPlan<>(true, true,
                    Map.of(producer, n, refill, n, root, 2*n), stock,
                    Map.<ReusableStockUsageKey<String>, Long>of(), Map.<String, Long>of(),
                    Map.<String, Long>of(), 5, false);
            var safe = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> UnorderedByproductSafety.protect(graph, orderedOnly, "T", 2*n));
            assertFalse(safe.feasible(), "the initially enabled root must not steal the live seed");
            assertFalse(safe.missing().isEmpty());
            if (n <= 2) assertEveryOrderFinishes(safe, "T", 2*n);
        }
    }

    /** Independent, deliberately unit-wise exhaustive oracle used only for these tiny fixtures. */
    static void assertEveryOrderFinishes(CraftPlan<String> plan, String target, long amount) {
        var stock = new HashMap<>(plan.usedStock());
        plan.missing().forEach((key, n) -> stock.merge(key, n, Long::sum));
        everyOrder(new HashMap<>(plan.firings()), stock, target, amount, new java.util.HashSet<>());
    }

    private static void everyOrder(Map<CraftPattern<String>, Long> counts, Map<String, Long> stock,
                                    String target, long amount,
                                    java.util.Set<Map<CraftPattern<String>, Long>> checked) {
        // For ordinary fixed recipes, remaining counts uniquely determine the inventory. Avoid
        // rechecking all permutations of independent firings while still visiting every state.
        if (!checked.add(Map.copyOf(counts))) return;
        if (counts.values().stream().allMatch(n -> n == 0)) {
            assertTrue(stock.getOrDefault(target, 0L) >= amount);
            return;
        }
        boolean enabled = false;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() == 0) continue;
            var pattern = entry.getKey();
            var needed = new HashMap<String, Long>();
            pattern.inputs().forEach(i -> needed.merge(i.key(), i.amount(), Long::sum));
            if (needed.entrySet().stream().anyMatch(i -> stock.getOrDefault(i.getKey(), 0L) < i.getValue())) continue;
            enabled = true;
            var next = new HashMap<>(stock);
            needed.forEach((key, n) -> next.merge(key, -n, Long::sum));
            next.merge(pattern.output(), pattern.outputAmount(), Long::sum);
            pattern.byproducts().forEach(o -> next.merge(o.key(), o.amount(), Long::sum));
            var remaining = new HashMap<>(counts);
            remaining.put(pattern, entry.getValue()-1);
            everyOrder(remaining, next, target, amount, checked);
        }
        assertTrue(enabled, () -> "deadlock: stock="+stock+" remaining="+counts);
    }
}
