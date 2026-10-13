package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UpstreamBatchOptimizerBranchTest {
    private static final List<String> ORDER = List.of("T", "A", "B", "I", "J", "X", "Y", "R", "S");

    @Test
    void coordinatedBranchReplacementEscapesTwoIndividuallyWorseChanges() {
        var fixture = fixture(2, 1, false);
        var incumbent = policy(fixture, false, false);
        var firstOnly = policy(fixture, true, false);
        var secondOnly = policy(fixture, false, true);
        var both = policy(fixture, true, true);
        assertEquals(7, executions(incumbent));
        assertEquals(8, executions(firstOnly));
        assertEquals(8, executions(secondOnly));
        assertEquals(6, executions(both));
        assertEquals(Map.of("R", 2L), incumbent.usedStock());
        assertEquals(Map.of("S", 2L), both.usedStock());

        var result = improve(fixture, incumbent);

        assertJointReplacement(fixture, incumbent, result);
    }

    @Test
    void publicPlannerUsesTheCertifiedJointReplacement() {
        var fixture = fixture(2, 1, false);
        var result = CraftPlannerV2.plan(fixture.graph(), "T", 1);
        assertJointReplacement(fixture, policy(fixture, false, false), result);
    }

    @Test
    void replacementCannotBorrowMissingStockFromTheOtherSupplier() {
        var fixture = fixture(1, 1, false);
        var incumbent = policy(fixture, false, false);
        var inaccessible = jointCounts(fixture);
        assertNull(MaterialDagReplay.tryPlan(fixture.graph(), inaccessible, "T", 1));

        assertNull(improve(fixture, incumbent));
        assertEquals(Map.of("R", 2L), incumbent.usedStock());
        assertEquals(7, executions(incumbent));
    }

    @Test
    void repeatedRegistrationsAndSharedSourcePreserveOriginalPatternIdentities() {
        var fixture = fixture(2, 3, true);
        assertEquals(6, fixture.graph().patternsFor("A").size());
        assertEquals(6, fixture.graph().patternsFor("B").size());
        assertSame(fixture.patterns().get(1).source(), fixture.patterns().get(2).source());
        assertNotSame(fixture.patterns().get(1), fixture.patterns().get(2));
        var incumbent = policy(fixture, false, false);

        assertJointReplacement(fixture, incumbent, improve(fixture, incumbent));
    }

    @Test
    void branchProposalsShareTheProbeAndWorkBudgetsAndPreserveTheirIncumbent() {
        var fixture = fixture(2, 1, false);
        var incumbent = policy(fixture, false, false);
        var original = Map.copyOf(incumbent.firings());
        var totalWork = new AtomicInteger();
        var probes = new AtomicInteger();
        var result = UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", 1, incumbent, ORDER,
                work -> { assertTrue(work > 0); totalWork.addAndGet(work); return true; },
                () -> probes.incrementAndGet() <= 32);
        assertJointReplacement(fixture, incumbent, result);
        assertTrue(probes.get() > 0 && probes.get() <= 32);
        assertTrue(totalWork.get() > 0);

        for (int limit : new int[] {0, 1, totalWork.get() / 4, totalWork.get() - 1}) {
            int[] remaining = {limit};
            var limited = UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", 1, incumbent, ORDER,
                    work -> {
                        assertTrue(work > 0);
                        if (work > remaining[0]) return false;
                        remaining[0] -= work;
                        return true;
                    }, () -> true);
            if (limited != null) assertJointReplacement(fixture, incumbent, limited);
            assertTrue(remaining[0] >= 0);
        }
        assertNull(UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", 1, incumbent, ORDER, work -> false,
                () -> { throw new AssertionError("work exhaustion entered certification"); }));
        var denied = new AtomicInteger();
        assertNull(UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", 1, incumbent, ORDER, work -> true,
                () -> { denied.incrementAndGet(); return false; }));
        assertEquals(1, denied.get(), "a denied shared probe budget must stop further proposals");
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("R", 2L), incumbent.usedStock());
    }

    @Test
    void branchSearchPropagatesExternalCancellationAndOptionalDeadlines() {
        var fixture = fixture(2, 1, false);
        var incumbent = policy(fixture, false, false);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> improve(fixture, incumbent));
        } finally {
            Thread.interrupted();
        }
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> improve(fixture, incumbent));
        }
    }

    @Test
    void moreThanSixteenDistinctBranchPatternsStillDeclineBeforeCertification() {
        var base = fixture(2, 1, false);
        var builder = CraftGraph.<String>builder().stock("R", 2).stock("S", 2);
        base.patterns().forEach(builder::pattern);
        for (int index = 0; index < 8; index++)
            builder.pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)));
        var graph = builder.build();
        var incumbent = policy(base, false, false);
        assertNull(UpstreamBatchOptimizer.tryImprove(graph, "T", 1, incumbent, ORDER, work -> true,
                () -> { throw new AssertionError("oversized graph entered certification"); }));
    }

    private static Fixture fixture(long secondStock, int registrations, boolean sharedSource) {
        Object source = sharedSource ? new Object() : null;
        var patterns = List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), source),
                new CraftPattern<>("A", 1, List.of(CraftInput.of("I", 2)), source),
                new CraftPattern<>("A", 1, List.of(CraftInput.of("J", 2)), source),
                new CraftPattern<>("B", 1, List.of(CraftInput.of("I", 2)), source),
                new CraftPattern<>("B", 1, List.of(CraftInput.of("J", 2)), source),
                new CraftPattern<>("I", 3, List.of(CraftInput.of("X", 1)), source),
                new CraftPattern<>("J", 4, List.of(CraftInput.of("Y", 2)), source),
                new CraftPattern<>("X", 1, List.of(CraftInput.of("R", 1)), source),
                new CraftPattern<>("Y", 1, List.of(CraftInput.of("S", 1)), source));
        var builder = CraftGraph.<String>builder().stock("R", 2).stock("S", secondStock);
        for (var pattern : patterns) for (int copy = 0; copy < registrations; copy++) builder.pattern(pattern);
        return new Fixture(builder.build(), patterns);
    }

    private static CraftPlan<String> policy(Fixture fixture, boolean firstUsesJ, boolean secondUsesJ) {
        var counts = new IdentityHashMap<CraftPattern<String>, Long>();
        var patterns = fixture.patterns();
        counts.put(patterns.get(0), 1L);
        counts.put(patterns.get(firstUsesJ ? 2 : 1), 1L);
        counts.put(patterns.get(secondUsesJ ? 4 : 3), 1L);
        long jDemand = (firstUsesJ ? 2 : 0) + (secondUsesJ ? 2 : 0);
        long iDemand = 4 - jDemand;
        long iBatches = (iDemand + 2) / 3;
        long jBatches = (jDemand + 3) / 4;
        if (iBatches > 0) {
            counts.put(patterns.get(5), iBatches);
            counts.put(patterns.get(7), iBatches);
        }
        if (jBatches > 0) {
            counts.put(patterns.get(6), jBatches);
            counts.put(patterns.get(8), 2 * jBatches);
        }
        var plan = MaterialDagReplay.tryPlan(fixture.graph(), counts, "T", 1);
        assertNotNull(plan);
        return plan;
    }

    private static Map<CraftPattern<String>, Long> jointCounts(Fixture fixture) {
        var patterns = fixture.patterns();
        return Map.of(patterns.get(0), 1L, patterns.get(2), 1L, patterns.get(4), 1L,
                patterns.get(6), 1L, patterns.get(8), 2L);
    }

    private static CraftPlan<String> improve(Fixture fixture, CraftPlan<String> incumbent) {
        return UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", 1, incumbent, ORDER, work -> true, () -> true);
    }

    private static void assertJointReplacement(Fixture fixture, CraftPlan<String> incumbent, CraftPlan<String> result) {
        assertNotNull(result);
        assertTrue(result.feasible());
        assertTrue(result.missing().isEmpty());
        assertEquals(6, executions(result));
        assertEquals(Map.of("S", 2L), result.usedStock());
        assertEquals(jointCounts(fixture), result.firings());
        assertTrue(result.firings().keySet().stream().allMatch(pattern -> fixture.patterns().stream().anyMatch(p -> p == pattern)));
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
        var certified = MaterialDagReplay.tryPlan(fixture.graph(), result.firings(), "T", 1);
        assertNotNull(certified);
        assertEquals(result.usedStock(), certified.usedStock());
        assertEquals(Map.of("R", 2L), incumbent.usedStock());
    }

    private static long executions(CraftPlan<?> plan) { return plan.firings().values().stream().mapToLong(Long::longValue).sum(); }
    private record Fixture(CraftGraph<String> graph, List<CraftPattern<String>> patterns) {}
}
