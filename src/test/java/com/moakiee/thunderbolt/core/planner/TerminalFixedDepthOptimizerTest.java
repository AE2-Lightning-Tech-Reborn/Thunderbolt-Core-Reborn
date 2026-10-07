package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class TerminalFixedDepthOptimizerTest {
    @Test
    void threeOriginalRoutesReplaceSixExecutionsWithAnExactStockCertificate() {
        var first = route(6, 3, 1);
        var second = route(6, 1, 3);
        var third = route(5, 2, 2);
        var slow = route(3, 1, 1);
        var fixture = fixture(6, 6, wide(17, List.of(first, second, third, slow)), 17, Map.of(slow, 6L));
        var candidate = improve(fixture);
        assertCertified(fixture, candidate, 3);
        assertEquals(Map.of(first, 1L, second, 1L, third, 1L), candidate.firings());
        assertEquals(Map.of("R", 6L, "S", 6L), candidate.usedStock());
        // With x fixed, the two raw slopes have opposite signs. Both output and
        // one raw lower bound have a negative right-hand side; rounding must be exact.
        assertEquals(6, executions(fixture.incumbent()));
    }

    @Test
    void equalRawCoefficientsAcceptZeroSlackAndRejectNegativeSlack() {
        var high = route(6, 3, 3);
        var low = route(5, 3, 1);
        var slow = route(4, 2, 1);
        var routes = wide(17, List.of(high, low, slow));
        var exact = fixture(9, 5, routes, 16, Map.of(slow, 4L));
        var candidate = improve(exact);
        assertCertified(exact, candidate, 3);
        assertEquals(Map.of(high, 1L, low, 2L), candidate.firings());
        assertEquals(Map.of("R", 9L, "S", 5L), candidate.usedStock());

        // R's coefficient difference is zero, while its right-hand side becomes -1.
        // Every three-firing solution is invalid; the four-firing incumbent remains feasible.
        var oneShort = fixture(8, 5, routes, 16, Map.of(slow, 4L));
        assertNull(improve(oneShort));
        assertTrue(oneShort.incumbent().feasible());
    }

    @Test
    void replacingAnEnvelopeMarkerDoesNotDeleteTheRetainedHighOutputRoute() {
        var first = route(10, 3, 3);
        var second = route(9, 2, 2);
        var dominated = route(8, 3, 3);
        var slow = route(6, 1, 1);
        var fixture = fixture(3, 3, wide(17, List.of(first, second, dominated, slow)), 10, Map.of(slow, 2L));
        var candidate = improve(fixture);
        assertCertified(fixture, candidate, 1);
        assertEquals(Map.of(first, 1L), candidate.firings(),
                "B removes A's query marker, but A must remain a candidate");
    }

    @Test
    void reusedMergeBuffersPreserveOriginalIdentitiesAcrossEverySupportedWidth() {
        for (int count = 17; count <= 64; count++) {
            for (int order = 0; order < 3; order++) {
                var first = route(10, 3, 3);
                var tied = route(10, 3, 3);
                var second = route(9, 2, 2);
                var dominated = route(8, 3, 3);
                var slow = route(6, 1, 1);
                var registrations = wide(count, List.of(first, second, dominated, slow, tied));
                if (order == 1) Collections.reverse(registrations);
                if (order == 2) Collections.rotate(registrations, count / 2);
                var expected = registrations.indexOf(first) < registrations.indexOf(tied) ? first : tied;
                var fixture = fixture(3, 3, registrations, 10, Map.of(slow, 2L));
                var candidate = improve(fixture);
                assertCertified(fixture, candidate, 1);
                assertSame(expected, candidate.firings().keySet().iterator().next(),
                        "stable ties and retained prefixes must survive both merge-buffer parities");
                assertEquals(registrations, fixture.graph().patternsFor("T"));
            }
        }
    }

    @Test
    void zeroFiringsDoNotContributePrimaryDemandSlack() {
        var fast = route(4, 1, 1);
        var unavailable = route(256, 127, 127);
        var slow = route(1, 1, 1);
        var fixture = fixture(3, 3, wide(17, List.of(fast, unavailable, slow)), 3, Map.of(slow, 3L));
        assertNull(MaterialDagReplay.tryPlan(fixture.graph(), Map.of(fast, 2L, unavailable, 0L), "T", 3),
                "the zero-count large output cannot justify a second fast batch");
        var candidate = improve(fixture);
        assertCertified(fixture, candidate, 1);
        assertEquals(Map.of(fast, 1L), candidate.firings());
        assertTrue(candidate.firings().values().stream().allMatch(count -> count > 0));
    }

    @Test
    void aLocalWorkLimitDoesNotCallTheSharedBudgetWithAnInventedRejection() {
        // Every fast route consumes R+S=64, so none can fire from R20/S20. The
        // nondominated frontier reaches the search loop before its local ceiling.
        var registrations = new ArrayList<CraftPattern<String>>();
        for (int r = 1; r <= 63; r++) registrations.add(route(8, r, 64 - r));
        var slow = route(4, 1, 1);
        registrations.add(slow);
        var fixture = fixture(20, 20, registrations, 32, Map.of(slow, 8L));
        var used = new AtomicInteger();
        var probes = new AtomicInteger();
        assertNull(TerminalFixedDepthOptimizer.tryImprove(fixture.graph(), "T", 32, fixture.incumbent(), work -> {
            assertTrue(work > 0);
            assertTrue(used.addAndGet(work) <= 4096, "local admission must precede the shared callback");
            return true;
        }, () -> { probes.incrementAndGet(); return true; }));
        assertEquals(4096, used.get());
        assertEquals(0, probes.get());
        assertEquals(Map.of(slow, 8L), fixture.incumbent().firings());
    }

    @Test
    void sharedWorkRefusalStopsBeforeAnyFurtherCallbackOrReplay() {
        var fixture = simpleFixture();
        var charges = new ArrayList<Integer>();
        assertCertified(fixture, TerminalFixedDepthOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(),
                fixture.incumbent(), work -> { charges.add(work); return true; }, () -> true), 1);
        for (int refuseAt : new int[] {1, charges.size() / 2, charges.size()}) {
            var calls = new AtomicInteger();
            var rejected = new AtomicInteger();
            var probes = new AtomicInteger();
            assertNull(TerminalFixedDepthOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(),
                    fixture.incumbent(), work -> {
                        assertTrue(work > 0);
                        assertEquals(0, rejected.get());
                        if (calls.incrementAndGet() == refuseAt) { rejected.incrementAndGet(); return false; }
                        return true;
                    }, () -> { probes.incrementAndGet(); return true; }));
            assertEquals(refuseAt, calls.get());
            assertEquals(1, rejected.get());
            assertEquals(0, probes.get());
        }
    }

    @Test
    void aDeniedSingleProbeLeavesTheOriginalGraphAndIncumbentUntouched() {
        var fixture = simpleFixture();
        var originalRoutes = List.copyOf(fixture.graph().patternsFor("T"));
        var originalFirings = Map.copyOf(fixture.incumbent().firings());
        var originalUsed = Map.copyOf(fixture.incumbent().usedStock());
        var probes = new AtomicInteger();
        assertNull(TerminalFixedDepthOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(),
                fixture.incumbent(), new WorkBudget(4096), () -> { probes.incrementAndGet(); return false; }));
        assertEquals(1, probes.get());
        assertEquals(originalFirings, fixture.incumbent().firings());
        assertEquals(originalUsed, fixture.incumbent().usedStock());
        assertEquals(originalRoutes, fixture.graph().patternsFor("T"));
        for (int i = 0; i < originalRoutes.size(); i++)
            assertSame(originalRoutes.get(i), fixture.graph().patternsFor("T").get(i));
        assertEquals(3, fixture.graph().stock("R"));
        assertEquals(3, fixture.graph().stock("S"));
    }

    @Test
    void optionalDeadlineAndExternalCancellationPropagate() {
        var fixture = simpleFixture();
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> improve(fixture));
        }
        var cancellation = new CancellationException("cancel fixed-depth search");
        PlanningAttemptContext context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() { throw cancellation; }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(cancellation, assertThrows(CancellationException.class, () -> improve(fixture)));
        }
        var calls = new AtomicInteger();
        try {
            assertThrows(CancellationException.class, () -> TerminalFixedDepthOptimizer.tryImprove(
                    fixture.graph(), "T", fixture.amount(), fixture.incumbent(), work -> {
                        calls.incrementAndGet();
                        Thread.currentThread().interrupt();
                        return true;
                    }, () -> true));
            assertEquals(1, calls.get());
        } finally {
            Thread.interrupted();
        }
        assertCertified(fixture, improve(fixture), 1);
    }

    @Test
    void identityBoundsIgnoreRepeatedRegistrationsButRejectOutsideTheDistinctDomain() {
        var fast = route(10, 3, 3);
        var slow = route(6, 1, 1);
        var sixteen = wide(16, List.of(fast, slow));
        for (int i = 0; i < 80; i++) sixteen.add(fast);
        assertNull(improve(fixture(3, 3, sixteen, 10, Map.of(slow, 2L))));
        var sixtyFour = wide(64, List.of(fast, slow));
        for (int i = 0; i < 80; i++) sixtyFour.add(fast);
        var valid = fixture(3, 3, sixtyFour, 10, Map.of(slow, 2L));
        assertCertified(valid, improve(valid), 1);
        assertNull(improve(fixture(3, 3, wide(65, List.of(fast, slow)), 10, Map.of(slow, 2L))));

        var foreign = route(6, 1, 1);
        assertNull(improve(fixture(3, 3, wide(17, List.of(fast, slow)), 10, Map.of(foreign, 2L))),
                "a matching signature is not a registered pattern identity");
    }

    @Test
    void duplicateInputSlotsAggregateBeforeTheStockConstraint() {
        var split = new CraftPattern<>("T", 10, List.of(CraftInput.of("R", 1),
                CraftInput.of("R", 2), CraftInput.of("S", 3)), null);
        var slow = route(6, 1, 1);
        var valid = fixture(3, 3, wide(17, List.of(split, slow)), 10, Map.of(slow, 2L));
        assertCertified(valid, improve(valid), 1);
        assertNull(improve(fixture(2, 3, valid.routes(), 10, Map.of(slow, 2L))),
                "the split route consumes R3, not either individual input slot");
    }

    @Test
    void specialModesAndNonTerminalInputProducersDeclineBeforeCertification() {
        var fast = route(10, 3, 3);
        var slow = route(6, 1, 1);
        var alternatives = List.of(
                new CraftPattern<>("T", 2, List.of(CraftInput.returned("R", 1), CraftInput.of("S", 1)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.finiteUse("R", 1, 2), CraftInput.of("S", 1)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.consumedReturning("R", 1, "empty"),
                        CraftInput.of("S", 1)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.returnedFrom("R", 1,
                        new ReusableStockSource("host", "pool")), CraftInput.of("S", 1)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.of("R", 1), CraftInput.of("S", 1)),
                        List.of(CraftOutput.of("side", 1)), null),
                new CraftPattern<>("T", BigInteger.TWO, List.of(CraftInput.of("R", 1), CraftInput.of("S", 1)),
                        List.of(), null, List.of(List.of(CraftInput.of("R", 1)))),
                new CraftPattern<>("T", 2, List.of(CraftInput.of("T", 1), CraftInput.of("S", 1)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.of("R", 1)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.of("R", 1), CraftInput.of("S", 1),
                        CraftInput.of("U", 1)), null),
                new CraftPattern<String>("T", 2, List.of(), null));
        for (var alternative : alternatives)
            assertNull(improve(fixture(3, 3, wide(17, List.of(fast, slow, alternative)), 10, Map.of(slow, 2L))));

        var ordinary = fixture(3, 3, wide(17, List.of(fast, slow)), 10, Map.of(slow, 2L));
        var builder = CraftGraph.<String>builder().stock("R", 3).stock("S", 3).stock("ore", 3);
        ordinary.routes().forEach(builder::pattern);
        builder.pattern("R", 1, List.of(CraftInput.of("ore", 1)));
        assertNull(TerminalFixedDepthOptimizer.tryImprove(builder.build(), "T", 10, ordinary.incumbent(),
                new WorkBudget(4096), () -> true));
    }

    @Test
    void exactInputDisagreementAndOutOfRangeOriginalRoutesDecline() {
        var fast = route(10, 3, 3);
        var slow = route(6, 1, 1);
        for (long[] amounts : new long[][] {{1, 2}, {2, 1}}) {
            var input = new CraftInput<>("R", amounts[0], false, CraftInput.INFINITE_USES,
                    null, null, BigInteger.valueOf(amounts[1]));
            var mismatch = new CraftPattern<>("T", 2, List.of(input, CraftInput.of("S", 1)), null);
            assertNull(improve(fixture(3, 3, wide(17, List.of(fast, slow, mismatch)), 10, Map.of(slow, 2L))));
        }
        for (var bad : List.of(route(257, 1, 1), route(2, 128, 129),
                new CraftPattern<>("T", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                        List.of(CraftInput.of("R", 1), CraftInput.of("S", 1)), List.of(), null)))
            assertNull(improve(fixture(3, 3, wide(17, List.of(fast, slow, bad)), 10, Map.of(slow, 2L))));
    }

    @Test
    void targetStockAndOriginalQuantityBoundsRemainPartOfTheScope() {
        var fast = route(10, 3, 3);
        var slow = route(6, 1, 1);
        var routes = wide(17, List.of(fast, slow));
        var atStockLimit = fixture(3, 253, routes, 10, Map.of(slow, 2L));
        assertCertified(atStockLimit, improve(atStockLimit), 1);
        assertNull(improve(fixture(3, 254, routes, 10, Map.of(slow, 2L))));
        var stocked = atStockLimit.graph().withAdditionalStock(Map.of("T", 1L));
        assertNull(TerminalFixedDepthOptimizer.tryImprove(stocked, "T", 10, atStockLimit.incumbent(),
                new WorkBudget(4096), () -> true));

        var boundary = route(256, 128, 128);
        var boundarySlow = route(128, 1, 1);
        var atOutputLimit = fixture(128, 128, wide(17, List.of(boundary, boundarySlow)), 256,
                Map.of(boundarySlow, 2L));
        assertCertified(atOutputLimit, improve(atOutputLimit), 1);
        for (long amount : new long[] {-1, 0, 257, Long.MAX_VALUE})
            assertNull(TerminalFixedDepthOptimizer.tryImprove(atOutputLimit.graph(), "T", amount,
                    atOutputLimit.incumbent(), new WorkBudget(4096), () -> true));

        var manySlow = route(1, 1, 1);
        assertNull(improve(fixture(33, 33, wide(17, List.of(route(2, 1, 1), manySlow)), 33,
                Map.of(manySlow, 33L))));
    }

    @Test
    void anExecutionLowerBoundCannotTurnIntoASameExecutionScalarStockTradeoff() {
        var first = route(10, 3, 1);
        var second = route(10, 1, 3);
        var fixture = fixture(6, 6, wide(17, List.of(first, second)), 20, Map.of(first, 2L));
        assertNull(improve(fixture));
        assertEquals(Map.of("R", 6L, "S", 2L), fixture.incumbent().usedStock());
    }

    @Test
    void reducedPreparationWorkFitsTheExistingPublicBudget() {
        var fixture = seed2Sample233();
        var budget = new WorkBudget(2878);
        var probes = new AtomicInteger();
        assertCertified(fixture, TerminalFixedDepthOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(),
                fixture.incumbent(), budget, () -> { probes.incrementAndGet(); return true; }), 5);
        assertEquals(0, budget.rejected);
        assertEquals(2738, budget.used);
        assertEquals(1, probes.get());

        var result = CraftPlannerV2.planDetailed(fixture.graph(), "T", fixture.amount());
        assertCertified(fixture, result.plan(), 5);
        assertEquals(Map.of(fixture.routes().get(3), 2L, fixture.routes().get(27), 1L,
                fixture.routes().get(46), 2L), result.plan().firings());
        // The established objective prioritizes executions: raw draw may rise within stock.
        assertEquals(Map.of("R", 21L, "S", 15L), result.plan().usedStock());
        assertEquals(4096, result.diagnostics().configuredSearchBudget());
        assertFalse(result.plan().budgetExhausted());
        assertFalse(result.diagnostics().searchCutoff());
        assertEquals(6, result.diagnostics().consumptionOptimizationProbes());
        assertEquals(2, result.diagnostics().consumptionOptimizationImprovements());
    }

    @Test
    void publicPortfolioKeepsItsEstablishedImprovementWhenTheSupplementRunsOutOfWork() {
        var fixture = seed2Sample233();
        var budget = new WorkBudget(2737);
        var probes = new AtomicInteger();
        assertNull(TerminalFixedDepthOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(),
                fixture.incumbent(), budget, () -> { probes.incrementAndGet(); return true; }));
        assertEquals(1, budget.rejected);
        assertEquals(0, probes.get());
        assertEquals(6, executions(fixture.incumbent()));

        // The established portfolio already reduced core 9 to 6 before this optional failure.
        var result = CraftPlannerV2.planDetailed(fixture.graph(), "T", fixture.amount(),
                CraftPlannerV2.DEFAULT_VISIT_CAP, 3955);
        assertCertified(fixture, result.plan(), 6);
        assertEquals(Map.of(fixture.routes().get(27), 4L, fixture.routes().get(46), 2L), result.plan().firings());
        assertEquals(Map.of("R", 20L, "S", 10L), result.plan().usedStock());
        assertFalse(result.plan().budgetExhausted());
        assertFalse(result.diagnostics().searchCutoff());
        assertEquals(5, result.diagnostics().consumptionOptimizationProbes());
        assertEquals(1, result.diagnostics().consumptionOptimizationImprovements());
    }

    private static Fixture simpleFixture() {
        var fast = route(10, 3, 3);
        var slow = route(6, 1, 1);
        return fixture(3, 3, wide(17, List.of(fast, slow)), 10, Map.of(slow, 2L));
    }

    private static CraftPattern<String> route(long output, long first, long second) {
        return new CraftPattern<>("T", output, List.of(CraftInput.of("R", first), CraftInput.of("S", second)), null);
    }

    private static ArrayList<CraftPattern<String>> wide(int identities, List<CraftPattern<String>> necessary) {
        var routes = new ArrayList<>(necessary);
        while (routes.size() < identities) routes.add(route(1, 127, 127));
        return routes;
    }

    private static Fixture fixture(long firstStock, long secondStock, List<CraftPattern<String>> routes,
            long amount, Map<CraftPattern<String>, Long> counts) {
        var builder = CraftGraph.<String>builder().stock("R", firstStock).stock("S", secondStock);
        routes.forEach(builder::pattern);
        var graph = builder.build();
        var incumbent = MaterialDagReplay.tryPlan(graph, counts, "T", amount);
        assertNotNull(incumbent, "the test incumbent needs its own original-graph replay certificate");
        assertTrue(incumbent.feasible());
        return new Fixture(graph, List.copyOf(routes), amount, incumbent);
    }

    private static CraftPlan<String> improve(Fixture fixture) {
        var budget = new WorkBudget(4096);
        var probes = new AtomicInteger();
        var result = TerminalFixedDepthOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), fixture.incumbent(),
                budget, () -> { assertEquals(1, probes.incrementAndGet()); return true; });
        assertTrue(budget.used <= 4096);
        if (result != null) assertEquals(1, probes.get());
        return result;
    }

    private static void assertCertified(Fixture fixture, CraftPlan<String> plan, long expectedExecutions) {
        assertNotNull(plan);
        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertTrue(plan.missing().isEmpty());
        assertTrue(plan.usedReusableStock().isEmpty());
        assertEquals(expectedExecutions, executions(plan));
        var identities = new IdentityHashMap<CraftPattern<String>, Boolean>();
        fixture.routes().forEach(route -> identities.put(route, Boolean.TRUE));
        plan.firings().forEach((route, count) -> { assertTrue(identities.containsKey(route)); assertTrue(count > 0); });
        var replay = MaterialDagReplay.tryPlan(fixture.graph(), plan.firings(), "T", fixture.amount());
        assertNotNull(replay);
        assertEquals(replay.usedStock(), plan.usedStock());
    }

    private static long executions(CraftPlan<String> plan) {
        return plan.firings().values().stream().mapToLong(Long::longValue).sum();
    }

    private static Fixture seed2Sample233() {
        // Generated directly from the frozen Java public audit; row order is pattern identity order.
        int[][] rows = {
                {25,2,11}, {24,11,10}, {2,9,8}, {17,2,4}, {3,1,4}, {23,8,10},
                {7,13,2}, {8,1,7}, {15,7,11}, {20,6,8}, {23,6,14}, {19,1,14},
                {25,14,12}, {12,14,8}, {16,8,10}, {22,1,9}, {14,4,5}, {18,12,2},
                {13,8,1}, {6,9,9}, {4,7,5}, {17,8,8}, {7,11,5}, {9,2,15},
                {15,10,5}, {10,15,13}, {5,4,10}, {11,1,1}, {24,12,10}, {12,12,15},
                {9,11,7}, {18,10,1}, {11,7,5}, {16,10,11}, {25,4,13}, {9,10,9},
                {12,14,9}, {3,6,13}, {6,2,6}, {2,15,15}, {7,3,12}, {23,11,11},
                {13,8,2}, {21,1,11}, {2,7,12}, {2,9,12}, {23,8,3}, {14,6,10}};
        var routes = new ArrayList<CraftPattern<String>>();
        for (int[] row : rows) routes.add(route(row[0], row[1], row[2]));
        return fixture(23, 15, routes, 89, Map.of(routes.get(27), 4L, routes.get(46), 2L));
    }

    private static final class WorkBudget implements IntPredicate {
        final int limit;
        int used;
        int rejected;

        WorkBudget(int limit) { this.limit = limit; }

        @Override public boolean test(int work) {
            assertTrue(work > 0);
            assertEquals(0, rejected, "a refused shared callback must be the last callback");
            if (work > limit - used) { rejected++; return false; }
            used += work;
            return true;
        }
    }

    private record Fixture(CraftGraph<String> graph, List<CraftPattern<String>> routes,
            long amount, CraftPlan<String> incumbent) {}
}
