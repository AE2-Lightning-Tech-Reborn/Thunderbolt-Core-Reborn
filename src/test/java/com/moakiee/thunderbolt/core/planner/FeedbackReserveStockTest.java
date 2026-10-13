package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeedbackReserveStockTest {
    @Test
    void actualRecipePreferenceOrdersPreserveTheCompleteStockAwareCertificate() {
        for (long n : new long[] {2, 100, 1_000_000_000_000L}) {
            var original = graph(n, 2 * n);
            var patterns = new ArrayList<>(List.of(original.patternsFor("M3").get(0),
                    original.patternsFor("M4").get(0), original.patternsFor("M3").get(1),
                    original.patternsFor("M5").get(0)));
            for (boolean reverse : new boolean[] {false, true}) {
                var b = CraftGraph.<String>builder().stock("M0", 2*n).stock("M1", 2*n)
                        .stock("M2", n).stock("M4", n);
                if (reverse) java.util.Collections.reverse(patterns);
                patterns.forEach(b::pattern);
                var g = b.build();
                var firings = new LinkedHashMap<CraftPattern<String>, Long>();
                patterns.forEach(p -> firings.put(p, n));
                var initial = new CraftPlan<>(true, true, firings,
                        Map.of("M0", n, "M1", n, "M2", n, "M4", n), Map.of(), Map.of(), Map.of(), 6, false);
                var p = UnorderedByproductSafety.protect(g, initial, "M5", n);
                assertTrue(p.feasible());
                assertEquals(initial.firings(), p.firings());
                assertBalance(p, n);
                var replanned = CraftPlannerV2.plan(g, "M5", n);
                assertTrue(replanned.feasible());
                assertBalance(replanned, n);
                if (n == 2) ByproductReplaySafetyTest.assertEveryOrderFinishes(p, "M5", n);
            }
        }
    }

    @Test
    void weightedThreePlaceFeedbackUsesAvailableSeedAndKeepsTheOriginalFirings() {
        for (long n : new long[] {2, 100, 1_000_000_000_000L, Sat.SAT / 16}) {
            for (boolean reverse : new boolean[] {false, true}) {
                var f = weightedLoops(n, 1, reverse, false);
                var p = UnorderedByproductSafety.protect(f.graph(), f.plan(), "T", n);
                assertTrue(p.feasible(), () -> p.toString());
                assertEquals(n + 5, p.usedStock().get("L0A"));
                assertEquals(f.plan().firings(), p.firings());
                assertReady(f.graph(), p, n);
                assertEquals(p, UnorderedByproductSafety.protect(f.graph(), p, "T", n));
            }
        }
    }

    @Test
    void equalExternalShortagePrefersTheSmallerCompleteWithdrawal() {
        var f = weightedLoops(2, 1, false, true);
        var p = UnorderedByproductSafety.protect(f.graph(), f.plan(), "T", 2);
        assertTrue(p.feasible());
        assertEquals(Map.of("L0A", 2L, "L0C", 2L), p.usedStock());
        assertEquals(f.plan().firings(), p.firings());
        assertReady(f.graph(), p, 2);
    }

    @Test
    void projectedStockCannotBorrowSeedsFromTheOriginalInventoryAndSupplementReplans() {
        var f = weightedLoops(2, 1, false, false);
        var projected = f.graph().withStockLimits(Map.of("L0A", 2L));
        var p = UnorderedByproductSafety.protect(projected, f.plan(), "T", 2);
        assertFalse(p.feasible());
        assertEquals(2L, p.missing().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(f.plan().firings(), p.firings());
        ByproductReplaySafetyTest.assertEveryOrderFinishes(p, "T", 2);
        var replenished = projected.withAdditionalStock(p.missing());
        var ready = CraftPlannerV2.plan(replenished, "T", 2, 1, 1);
        assertReady(replenished, ready, 2);
        assertEquals(2L, projected.stock("L0A"));
        assertEquals(7L, f.graph().stock("L0A"));
    }

    @Test
    void independentFeedbackComponentsReserveTheirOwnSeedsAcrossRecipeOrders() {
        for (boolean reverse : new boolean[] {false, true}) {
            var f = weightedLoops(2, 2, reverse, false);
            var p = UnorderedByproductSafety.protect(f.graph(), f.plan(), "T", 2);
            assertTrue(p.feasible());
            assertEquals(Map.of("L0A", 7L, "L1A", 7L), p.usedStock());
            assertEquals(f.plan().firings(), p.firings());
            assertReady(f.graph(), p, 2);
            assertEquals(p, UnorderedByproductSafety.protect(f.graph(), p, "T", 2));
        }
    }

    @Test
    void aCompleteStartupCertificateUsesUnassignedStockBeforeRequestingSupply() {
        for (long n : new long[] {2, 100, 1_000_000_000_000L}) {
            for (boolean reverse : new boolean[] {false, true}) {
                var graph = graph(n, 2 * n);
                var initial = witness(graph, n, reverse);
                var protectedPlan = UnorderedByproductSafety.protect(graph, initial, "M5", n);

                assertTrue(protectedPlan.feasible(), () -> protectedPlan.toString());
                assertTrue(protectedPlan.missing().isEmpty());
                assertEquals(initial.firings(), protectedPlan.firings());
                assertEquals(2 * n, protectedPlan.grossDemand().get("M4"));
                assertTrue(protectedPlan.usedStock().get("M0") > n);
                protectedPlan.usedStock().forEach((key, count) -> assertTrue(count <= graph.stock(key)));
                assertBalance(protectedPlan, n);
                if (n == 2) {
                    assertFalse(UnorderedByproductSafety.allOrdersFinishSmall(initial, "M5", n));
                    ByproductReplaySafetyTest.assertEveryOrderFinishes(protectedPlan, "M5", n);
                }
                assertEquals(protectedPlan,
                        UnorderedByproductSafety.protect(graph, protectedPlan, "M5", n));
            }
        }
    }

    @Test
    void exhaustedInventoryStillRequiresAnExecutableStartupReserve() {
        long n = 100;
        var graph = graph(n, n);
        var protectedPlan = UnorderedByproductSafety.protect(graph, witness(graph, n, false), "M5", n);
        assertFalse(protectedPlan.feasible());
        assertFalse(protectedPlan.missing().isEmpty());
        var supplied = graph.withAdditionalStock(protectedPlan.missing());
        var used = new HashMap<>(protectedPlan.usedStock());
        protectedPlan.missing().forEach((key, count) -> used.merge(key, count, Math::addExact));
        var ready = new CraftPlan<>(true, true, protectedPlan.firings(), Map.copyOf(used), Map.of(),
                Map.of(), protectedPlan.grossDemand(), 6, false);
        assertTrue(UnorderedByproductSafety.protect(supplied, ready, "M5", n).feasible());
        assertBalance(ready, n);
    }

    private static CraftGraph<String> graph(long n, long m0) {
        return CraftGraph.<String>builder().stock("M0", m0).stock("M1", 2 * n)
                .stock("M2", n).stock("M4", n)
                .pattern("M3", 1, List.of(CraftInput.of("M0", 1), CraftInput.of("M2", 1)))
                .pattern("M4", 1, List.of(CraftInput.of("M0", 1), CraftInput.of("M1", 1)))
                .pattern("M3", 1, List.of(CraftInput.of("M4", 2)), List.of(CraftOutput.of("M0", 1)))
                .pattern("M5", 1, List.of(CraftInput.of("M3", 2))).build();
    }

    private static CraftPlan<String> witness(CraftGraph<String> graph, long n, boolean reverse) {
        var patterns = new ArrayList<>(List.of(graph.patternsFor("M3").get(0),
                graph.patternsFor("M4").get(0), graph.patternsFor("M3").get(1),
                graph.patternsFor("M5").get(0)));
        if (reverse) java.util.Collections.reverse(patterns);
        var counts = new LinkedHashMap<CraftPattern<String>, Long>();
        patterns.forEach(pattern -> counts.put(pattern, n));
        return new CraftPlan<>(true, true, counts, Map.of("M0", n, "M1", n, "M2", n, "M4", n),
                Map.of(), Map.of(), Map.of("M0", 2 * n, "M1", n, "M2", n,
                        "M3", 2 * n, "M4", 2 * n, "M5", n), 6, false);
    }

    private static void assertBalance(CraftPlan<String> plan, long n) {
        assertBalance(plan, "M5", n);
    }

    private static void assertBalance(CraftPlan<String> plan, String target, long n) {
        var balance = new HashMap<String, BigInteger>();
        plan.usedStock().forEach((key, count) -> balance.put(key, BigInteger.valueOf(count)));
        plan.firings().forEach((pattern, count) -> {
            var times = BigInteger.valueOf(count);
            pattern.inputs().forEach(input -> balance.merge(input.key(),
                    input.exactAmount().multiply(times).negate(), BigInteger::add));
            balance.merge(pattern.output(), pattern.exactOutputAmount().multiply(times), BigInteger::add);
            pattern.byproducts().forEach(output -> balance.merge(output.key(),
                    output.exactAmount().multiply(times), BigInteger::add));
        });
        assertTrue(balance.values().stream().allMatch(amount -> amount.signum() >= 0));
        assertTrue(balance.get(target).compareTo(BigInteger.valueOf(n)) >= 0);
    }

    private record LoopFixture(CraftGraph<String> graph, CraftPlan<String> plan) {}

    /** Three cyclic places, different arc coefficients and an independent sink stealing A. */
    private static LoopFixture weightedLoops(long n, int loops, boolean reverse, boolean allSeeds) {
        var b = CraftGraph.<String>builder();
        var patterns = new ArrayList<CraftPattern<String>>();
        var targetInputs = new ArrayList<CraftInput<String>>();
        var used = new HashMap<String, Long>();
        for (int loop = 0; loop < loops; loop++) {
            String prefix = "L" + loop;
            b.stock(prefix + "A", n + 5);
            if (allSeeds) b.stock(prefix + "B", 3).stock(prefix + "C", 2);
            patterns.add(new CraftPattern<>(prefix + "B", 3, List.of(CraftInput.of(prefix + "A", 5)), null));
            patterns.add(new CraftPattern<>(prefix + "C", 2, List.of(CraftInput.of(prefix + "B", 3)), null));
            patterns.add(new CraftPattern<>(prefix + "P", 1, List.of(CraftInput.of(prefix + "C", 2)),
                    List.of(CraftOutput.of(prefix + "A", 5)), null));
            patterns.add(new CraftPattern<>(prefix + "S", 1, List.of(CraftInput.of(prefix + "A", 1)), null));
            targetInputs.add(CraftInput.of(prefix + "P", 1));
            targetInputs.add(CraftInput.of(prefix + "S", 1));
            used.put(prefix + "A", n);
        }
        patterns.add(new CraftPattern<>("T", 1, targetInputs, null));
        if (reverse) java.util.Collections.reverse(patterns);
        var firings = new LinkedHashMap<CraftPattern<String>, Long>();
        patterns.forEach(p -> { b.pattern(p); firings.put(p, n); });
        return new LoopFixture(b.build(), new CraftPlan<>(true, true, firings, Map.copyOf(used),
                Map.of(), Map.of(), Map.of(), 5 * loops + 1, false));
    }

    private static void assertReady(CraftGraph<String> graph, CraftPlan<String> plan, long n) {
        assertTrue(plan.feasible(), () -> plan.toString());
        assertTrue(plan.missing().isEmpty());
        plan.usedStock().forEach((key, count) -> assertTrue(count >= 0 && count <= graph.stock(key)));
        plan.firings().forEach((pattern, count) -> {
            assertTrue(count > 0);
            assertTrue(graph.patternsFor(pattern.output()).stream().anyMatch(p -> p == pattern));
        });
        assertBalance(plan, "T", n);
        if (n == 2) ByproductReplaySafetyTest.assertEveryOrderFinishes(plan, "T", n);
    }
}
