package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerminalBatchOptimizerTest {
    @Test
    void completeSingleRouteUsesExistingTargetStockAndReducesExecutions() {
        var slow = route(1, 1);
        var fast = route(10, 6);
        var fixture = fixture(Map.of("R", 100L, "T", 11L), routes(slow, fast),
                Map.of(slow, 9L), 20);
        var result = improve(fixture);
        assertCertified(fixture, result, 1);
        assertEquals(Map.of(fast, 1L), result.firings());
        assertEquals(Map.of("R", 6L, "T", 10L), result.usedStock());
        assertEquals(Map.of(slow, 9L), fixture.incumbent().firings());
    }

    @Test
    void boundedPairPortfolioClosesARealSeventeenRouteExecutionGap() {
        // Random terminal audit seed 51032026, sample 15. Every single route needs
        // at least seven firings or exceeds inventory; four is the output lower bound.
        var fixture = sample15();
        var result = improve(fixture);
        assertCertified(fixture, result, 4);
        assertEquals(2, result.firings().size());
        assertTrue(result.usedStock().getOrDefault("R", 0L) <= 28);
        assertTrue(result.usedStock().getOrDefault("S", 0L) <= 19);
        var publicPlan = CraftPlannerV2.plan(fixture.graph(), "T", fixture.amount());
        assertCertified(fixture, publicPlan, 4);
    }

    @Test
    void repeatedRawSlotsAreAggregatedBeforeCapacityAndReplay() {
        var slow = route(2, 1);
        var split = new CraftPattern<>("T", 10,
                List.of(CraftInput.of("R", 2), CraftInput.of("R", 3)), null);
        var fixture = fixture(Map.of("R", 10L), routes(slow, split), Map.of(slow, 10L), 20);
        var result = improve(fixture);
        assertCertified(fixture, result, 2);
        assertEquals(Map.of("R", 10L), result.usedStock());

        var tooExpensive = new CraftPattern<>("T", 10,
                List.of(CraftInput.of("R", 4), CraftInput.of("R", 4)), null);
        var tight = fixture(Map.of("R", 10L), routes(slow, tooExpensive), Map.of(slow, 10L), 20);
        assertNull(improve(tight), "no mixture can pay eight raw units per large batch");
    }

    @Test
    void canonicalExactInputAmountsControlConsumptionEvenWhenLongViewsDiffer() {
        var slow = route(1, 1);
        var exactCheap = new CraftPattern<>("T", 10,
                List.of(new CraftInput<>("R", 100, false, CraftInput.INFINITE_USES,
                        null, null, BigInteger.valueOf(2))), null);
        var fixture = fixture(Map.of("R", 20L), routes(slow, exactCheap), Map.of(slow, 20L), 20);
        var result = improve(fixture);
        assertCertified(fixture, result, 2);
        assertEquals(Map.of("R", 4L), result.usedStock());

        var exactExpensive = new CraftPattern<>("T", 10,
                List.of(new CraftInput<>("R", 1, false, CraftInput.INFINITE_USES,
                        null, null, BigInteger.valueOf(11))), null);
        var tight = fixture(Map.of("R", 20L), routes(slow, exactExpensive), Map.of(slow, 20L), 20);
        assertNull(improve(tight), "the narrowed input view must not invent a feasible batch");
    }

    @Test
    void saturatedExactAmountsAndAggregatedSlotsDeclineBeforeCertification() {
        var slow = route(1, 1);
        var saturated = BigInteger.valueOf(Sat.SAT);
        var alternatives = List.of(
                new CraftPattern<>("T", saturated, List.of(CraftInput.of("R", 1)), List.of(), null),
                new CraftPattern<>("T", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                        List.of(CraftInput.of("R", 1)), List.of(), null),
                new CraftPattern<>("T", 10, List.of(new CraftInput<>("R", 1, false,
                        CraftInput.INFINITE_USES, null, null, saturated)), null),
                new CraftPattern<>("T", 10, List.of(CraftInput.of("R", Sat.SAT / 2),
                        CraftInput.of("R", Sat.SAT / 2), CraftInput.of("R", 1)), null));
        for (var alternative : alternatives) {
            var fixture = fixture(Map.of("R", 20L), routes(slow, alternative), Map.of(slow, 20L), 20);
            assertDeclinesBeforeProbe(fixture);
        }
    }

    @Test
    void executionSumOverflowDoesNotBecomeASmallerIncumbentOrAValidCandidate() {
        var fixture = singleRouteFixture();
        var corrupt = new CraftPlan<>(true, true,
                Map.of(fixture.routes().get(0), Long.MAX_VALUE, fixture.routes().get(1), 1L),
                fixture.incumbent().usedStock(), Map.<ReusableStockUsageKey<String>, Long>of(),
                Map.<String, Long>of(), fixture.incumbent().grossDemand(), 0, false);
        assertNull(TerminalBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), corrupt,
                fixture.routes(), work -> true,
                () -> { throw new AssertionError("overflowed execution total entered replay"); }));
        assertEquals(Long.MAX_VALUE, corrupt.firings().get(fixture.routes().getFirst()));
    }

    @Test
    void workRefusalAtSuccessivePairSearchBoundariesKeepsOnlyCertifiedResults() {
        var fixture = sample15();
        var original = Map.copyOf(fixture.incumbent().firings());
        var calls = new AtomicInteger();
        var full = TerminalBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(),
                fixture.incumbent(), fixture.routes(), work -> {
                    assertTrue(work > 0);
                    calls.incrementAndGet();
                    return true;
                }, () -> true);
        assertCertified(fixture, full, 4);
        assertTrue(calls.get() > 1);
        // Each charged boundary is denied in turn, including boundaries inside the
        // pair calculation and final replay. This does not depend on sort internals.
        for (int deniedAt = 1; deniedAt <= calls.get(); deniedAt++) {
            int boundary = deniedAt;
            var reached = new AtomicInteger();
            var result = TerminalBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(),
                    fixture.incumbent(), fixture.routes(), work -> {
                        assertTrue(work > 0);
                        return reached.incrementAndGet() < boundary;
                    }, () -> true);
            assertTrue(reached.get() >= boundary);
            if (result != null) assertCertifiedImprovement(fixture, result);
            assertEquals(original, fixture.incumbent().firings());
        }
    }

    @Test
    void deniedWorkAndDeniedProbesCannotExportAnUncertifiedImprovement() {
        var fixture = sample15();
        assertNull(TerminalBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(),
                fixture.incumbent(), fixture.routes(), work -> false,
                () -> { throw new AssertionError("denied work reached a certification probe"); }));
        var probes = new AtomicInteger();
        assertNull(TerminalBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(),
                fixture.incumbent(), fixture.routes(), work -> true,
                () -> { probes.incrementAndGet(); return false; }));
        assertTrue(probes.get() > 0);
        assertEquals(Map.of(fixture.routes().get(12), 7L), fixture.incumbent().firings());
    }

    @Test
    void callerCancellationAndExpiredOptionalWorkRemainVisible() {
        var fixture = sample15();
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> improve(fixture));
        } finally {
            Thread.interrupted();
        }
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> improve(fixture));
        }
        try {
            assertThrows(CancellationException.class, () -> TerminalBatchOptimizer.tryImprove(
                    fixture.graph(), "T", fixture.amount(), fixture.incumbent(), fixture.routes(),
                    work -> true, () -> { Thread.currentThread().interrupt(); return true; }));
        } finally {
            Thread.interrupted();
        }
        assertEquals(Map.of(fixture.routes().get(12), 7L), fixture.incumbent().firings());
    }

    @Test
    void repeatedRegistrationsDoNotConsumeDistinctRouteSlots() {
        var slow = route(1, 1);
        var fast = route(10, 6);
        var registered = routes(slow, fast);
        for (int i = 0; i < 80; i++) registered.add(slow);
        var fixture = fixture(Map.of("R", 100L), registered, Map.of(slow, 20L), 20);
        var result = improve(fixture);
        assertCertified(fixture, result, 2);
        assertEquals(Map.of(fast, 2L), result.firings());
    }

    @Test
    void distinctObjectsSharingOneSourceRemainDistinctRouteChoices() {
        Object source = new Object();
        var slow = new CraftPattern<>("T", 1, List.of(CraftInput.of("R", 1)), source);
        var fast = new CraftPattern<>("T", 10, List.of(CraftInput.of("R", 6)), source);
        var fixture = fixture(Map.of("R", 100L), routes(slow, fast), Map.of(slow, 20L), 20);
        var result = improve(fixture);
        assertCertified(fixture, result, 2);
        assertEquals(Map.of(fast, 2L), result.firings());
    }

    @Test
    void distinctRouteCountOutsideTheSeventeenToSixtyFourPortfolioDeclines() {
        var fixture = singleRouteFixture();
        var small = new ArrayList<>(fixture.routes().subList(0, 16));
        assertDeclinesBeforeProbe(fixture(Map.of("R", 100L), small,
                fixture.incumbent().firings(), fixture.amount()));
        var large = new ArrayList<>(fixture.routes());
        while (large.size() < 64) large.add(route(1, 1000 + large.size()));
        var atLimit = fixture(Map.of("R", 100L), large, fixture.incumbent().firings(), fixture.amount());
        assertCertified(atLimit, improve(atLimit), 2);
        while (large.size() < 65) large.add(route(1, 1000 + large.size()));
        assertDeclinesBeforeProbe(fixture(Map.of("R", 100L), large,
                fixture.incumbent().firings(), fixture.amount()));
    }

    @Test
    void stockedIntermediateWithAProducerIsStillOutsideTerminalScope() {
        var fixture = singleRouteFixture();
        var graph = CraftGraph.<String>builder().stock("R", 100).stock("ore", 100);
        fixture.routes().forEach(graph::pattern);
        graph.pattern("R", 1, List.of(CraftInput.of("ore", 1)));
        var withUpstream = graph.build();
        assertDeclinesBeforeProbe(new Fixture(withUpstream, fixture.routes(), fixture.incumbent(), fixture.amount()));
    }

    @Test
    void statefulSideOutputSelfFeedingAndInputlessAlternativesDecline() {
        var slow = route(1, 1);
        var alternatives = List.of(
                new CraftPattern<>("T", 10, List.of(CraftInput.returned("R", 1)), null),
                new CraftPattern<>("T", 10, List.of(CraftInput.finiteUse("R", 1, 5)), null),
                new CraftPattern<>("T", 10, List.of(CraftInput.consumedReturning("R", 1, "empty")), null),
                new CraftPattern<>("T", 10, List.of(CraftInput.returnedFrom("R", 1,
                        new ReusableStockSource("host", "pool"))), null),
                new CraftPattern<>("T", 10, List.of(CraftInput.of("R", 1)),
                        List.of(CraftOutput.of("side", 1)), null),
                new CraftPattern<>("T", BigInteger.TEN, List.of(CraftInput.of("R", 1)),
                        List.of(), null, List.of(List.of(CraftInput.of("R", 1)))),
                new CraftPattern<>("T", 10, List.of(CraftInput.of("T", 1)), null),
                new CraftPattern<String>("T", 10, List.of(), null));
        for (var alternative : alternatives) {
            var fixture = fixture(Map.of("R", 100L), routes(slow, alternative), Map.of(slow, 20L), 20);
            assertDeclinesBeforeProbe(fixture);
        }
    }

    @Test
    void moreThanFourRawResourcesDeclineBeforeCertification() {
        var slow = route(1, 1);
        var wide = new CraftPattern<>("T", 10, List.of(CraftInput.of("R", 1),
                CraftInput.of("S", 1), CraftInput.of("U", 1), CraftInput.of("V", 1),
                CraftInput.of("W", 1)), null);
        var fixture = fixture(Map.of("R", 100L, "S", 100L, "U", 100L, "V", 100L, "W", 100L),
                routes(slow, wide), Map.of(slow, 20L), 20);
        assertDeclinesBeforeProbe(fixture);
    }

    @Test
    void fourTerminalResourcesShareOneExactInventoryCertificate() {
        var raw = List.of("R", "S", "U", "V");
        var slow = new CraftPattern<>("T", 1, raw.stream().map(key -> CraftInput.of(key, 1)).toList(), null);
        var fast = new CraftPattern<>("T", 10, raw.stream().map(key -> CraftInput.of(key, 6)).toList(), null);
        var fixture = fixture(Map.of("R", 100L, "S", 100L, "U", 100L, "V", 100L),
                routes(slow, fast), Map.of(slow, 20L), 20);
        var result = improve(fixture);
        assertCertified(fixture, result, 2);
        assertEquals(Map.of("R", 12L, "S", 12L, "U", 12L, "V", 12L), result.usedStock());
    }

    @Test
    void optimalPolicyResultDoesNotSpendAnotherTerminalSearchAllowance() {
        var slow = route(1, 1);
        var fast = route(10, 1);
        var fixture = fixture(Map.of("R", 100L), routes(slow, fast), Map.of(slow, 20L), 20);
        var oracleCalls = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(fixture.graph(), "T", 20, fixture.incumbent(),
                32, candidate -> {
                    oracleCalls.incrementAndGet();
                    return MaterialDagReplay.tryPlan(candidate, Map.of(fast, 2L), "T", 20);
                }, work -> {
                    assertEquals(0, oracleCalls.get(), "the accepted policy already reaches the exact lower bound");
                    return true;
                });
        assertEquals(1, oracleCalls.get());
        assertCertified(fixture, result.plan(), 2);
    }

    @Test
    void equalExecutionStockSavingIsLeftToTheExistingPortfolio() {
        var costly = route(10, 6);
        var cheap = route(10, 1);
        var fixture = fixture(Map.of("R", 100L), routes(costly, cheap), Map.of(costly, 2L), 20);
        assertNull(improve(fixture));
        assertEquals(Map.of("R", 12L), fixture.incumbent().usedStock());
    }

    private static Fixture singleRouteFixture() {
        var slow = route(1, 1);
        return fixture(Map.of("R", 100L), routes(slow, route(10, 6)), Map.of(slow, 20L), 20);
    }

    private static CraftPattern<String> route(long output, long raw) {
        return new CraftPattern<>("T", output, List.of(CraftInput.of("R", raw)), null);
    }

    @SafeVarargs
    private static ArrayList<CraftPattern<String>> routes(CraftPattern<String>... selected) {
        var result = new ArrayList<>(List.of(selected));
        while (result.size() < 17) result.add(route(1, 1000 + result.size()));
        return result;
    }

    private static Fixture fixture(Map<String, Long> stock, List<CraftPattern<String>> routes,
            Map<CraftPattern<String>, Long> counts, long amount) {
        var builder = CraftGraph.<String>builder();
        stock.forEach(builder::stock);
        routes.forEach(builder::pattern);
        var graph = builder.build();
        var incumbent = MaterialDagReplay.tryPlan(graph, counts, "T", amount);
        assertNotNull(incumbent, "test incumbent must have an independently replayable certificate");
        return new Fixture(graph, routes, incumbent, amount);
    }

    private static Fixture sample15() {
        int[][] source = {{23,12,12},{12,12,6},{25,8,12},{20,14,10},{3,7,5},{11,1,5},
                {6,6,7},{25,12,9},{22,8,9},{7,5,5},{2,15,10},{23,8,5},{13,1,1},
                {20,7,1},{21,6,7},{11,6,14},{25,2,5}};
        var routes = new ArrayList<CraftPattern<String>>();
        for (var row : source) routes.add(new CraftPattern<>("T", row[0],
                List.of(CraftInput.of("R", row[1]), CraftInput.of("S", row[2])), null));
        return fixture(Map.of("R", 28L, "S", 19L), routes, Map.of(routes.get(12), 7L), 84);
    }

    private static CraftPlan<String> improve(Fixture fixture) {
        return TerminalBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), fixture.incumbent(),
                fixture.routes(), work -> true, () -> true);
    }

    private static void assertDeclinesBeforeProbe(Fixture fixture) {
        assertNull(TerminalBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), fixture.incumbent(),
                fixture.routes(), work -> true,
                () -> { throw new AssertionError("out-of-scope graph entered certification"); }));
    }

    private static void assertCertified(Fixture fixture, CraftPlan<String> result, long executions) {
        assertCertifiedImprovement(fixture, result);
        assertEquals(executions, executions(result));
    }

    private static void assertCertifiedImprovement(Fixture fixture, CraftPlan<String> result) {
        assertNotNull(result);
        assertTrue(result.feasible());
        assertTrue(result.supported());
        assertTrue(result.missing().isEmpty());
        assertTrue(result.usedReusableStock().isEmpty());
        assertTrue(executions(result) < executions(fixture.incumbent()));
        var replay = MaterialDagReplay.tryPlan(fixture.graph(), result.firings(), "T", fixture.amount());
        assertNotNull(replay);
        assertEquals(replay.usedStock(), result.usedStock());
    }

    private static long executions(CraftPlan<String> plan) {
        long total = 0;
        for (long count : plan.firings().values()) total = Math.addExact(total, count);
        return total;
    }

    private record Fixture(CraftGraph<String> graph, List<CraftPattern<String>> routes,
            CraftPlan<String> incumbent, long amount) {}
}
