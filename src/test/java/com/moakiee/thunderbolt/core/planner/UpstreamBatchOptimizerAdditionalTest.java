package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UpstreamBatchOptimizerAdditionalTest {
    @Test
    void incumbentPairMovesOneBatchAtATimeThroughTheFeasibleSharedRawRegion() {
        var fixture = sharedRaw(27);
        var first = plan(fixture, 4, 0, 5, 3, 3, 8);
        assertEquals(23, executions(first));
        var second = additional(fixture, first);
        assertEquals(22, executions(second));
        assertEquals(Map.of("T", 1L, "B", 1L, "raw", 26L), second.usedStock());
        var third = additional(fixture, second);
        assertEquals(21, executions(third));
        assertEquals(Map.of("T", 1L, "B", 1L, "raw", 27L), third.usedStock());
        assertEquals(counts(fixture, 4, 0, 3, 5, 3, 6), third.firings());
        assertNull(additional(fixture, third));
        assertNull(MaterialDagReplay.tryPlan(fixture.graph(), counts(fixture, 4, 0, 2, 6, 3, 5), "T", 5));
        assertEquals(counts(fixture, 4, 0, 5, 3, 3, 8), first.firings());
    }

    @Test
    void incumbentPairStopsAtTheActualSharedRawCapacity() {
        var fixture = sharedRaw(26);
        var first = plan(fixture, 4, 0, 5, 3, 3, 8);
        var result = additional(fixture, first);
        assertEquals(22, executions(result));
        assertNull(additional(fixture, result));
        assertEquals(Map.of("T", 1L, "B", 1L, "raw", 26L), result.usedStock());
    }

    @Test
    void protectedNewBatchCoordinatesTwoOutputsWithoutReplacingTheWholeSecondRoute() {
        var fixture = twoOutputs(8, 0);
        var incumbent = plan(fixture, 3, 0, 2, 0, 0, 2);
        assertEquals(7, executions(incumbent));
        var result = additional(fixture, incumbent);
        assertEquals(6, executions(result));
        assertEquals(counts(fixture, 1, 1, 1, 1, 1, 1), result.firings());
        assertEquals(Map.of("A", 2L, "B", 1L, "R", 1L, "S", 6L), result.usedStock());
        assertEquals(1L, result.firings().get(fixture.patterns().get(3)), "protect precisely one new batch");
        assertEquals(counts(fixture, 3, 0, 2, 0, 0, 2), incumbent.firings());
        assertEquals(Map.of("A", 2L, "R", 2L), incumbent.usedStock());
    }

    @Test
    void protectedBatchCannotDoubleSpendTheSharedSupplier() {
        var fixture = twoOutputs(5, 0);
        var incumbent = plan(fixture, 3, 0, 2, 0, 0, 2);
        assertNull(MaterialDagReplay.tryPlan(fixture.graph(), counts(fixture, 1, 1, 1, 1, 1, 1), "T", 5));
        assertNull(additional(fixture, incumbent));
    }

    @Test
    void publicPlannerReusesBasePairSolvesToCompleteBothStepsWithinItsOriginalBudget() {
        var fixture = sharedRaw(27);
        var result = CraftPlannerV2.planDetailed(fixture.graph(), "T", 5);
        // Repeating each base solve across exchanges exhausted the shared 4096-work allowance
        // at 22 executions. Reuse only completed solves; do not raise that allowance.
        assertEquals(21, executions(result.plan()));
        assertTrue(result.diagnostics().consumptionOptimizationProbes() <= 32);
        assertCertified(fixture, result.plan());
    }

    @Test
    void publicPlannerUsesTheProtectedOneBatchWitness() {
        var fixture = twoOutputs(8, 0);
        var result = CraftPlannerV2.planDetailed(fixture.graph(), "T", 5);
        assertEquals(6, executions(result.plan()));
        assertTrue(result.diagnostics().consumptionOptimizationProbes() <= 32);
        assertCertified(fixture, result.plan());
    }

    @Test
    void additionalStageCannotPreemptEstablishedCandidatesOrReenterAfterSuccess() {
        var fixture = sharedRaw(27);
        var incumbent = plan(fixture, 4, 0, 5, 3, 3, 8);
        var workCalls = new AtomicInteger();
        var search = UpstreamBatchOptimizer.startSearch(fixture.graph(), "T", 5, incumbent, fixture.order(),
                work -> { workCalls.incrementAndGet(); return true; }, () -> true);
        assertNotNull(search);
        int before = workCalls.get();
        assertNull(search.tryAdditional());
        assertEquals(before, workCalls.get());
        assertNull(search.tryEstablished());
        assertNotNull(search.tryAdditional());
        int after = workCalls.get();
        assertNull(search.tryAdditional());
        assertEquals(after, workCalls.get());
    }

    @Test
    void deniedProbeIsLatchedAcrossStageReentryAndDoesNotMutateTheIncumbent() {
        var fixture = sharedRaw(27);
        var incumbent = plan(fixture, 4, 0, 5, 3, 3, 8);
        var probes = new AtomicInteger();
        var search = UpstreamBatchOptimizer.startSearch(fixture.graph(), "T", 5, incumbent, fixture.order(),
                work -> true, () -> { probes.incrementAndGet(); return false; });
        assertNotNull(search);
        assertNull(search.tryEstablished());
        assertNull(search.tryAdditional());
        assertEquals(1, probes.get());
        assertNull(search.tryAdditional());
        assertNull(search.tryEstablished());
        assertEquals(1, probes.get());
        assertEquals(counts(fixture, 4, 0, 5, 3, 3, 8), incumbent.firings());
    }

    @Test
    void exhaustedWorkCannotBeResetForTheAdditionalStage() {
        var fixture = sharedRaw(27);
        var incumbent = plan(fixture, 4, 0, 5, 3, 3, 8);
        var enabled = new AtomicBoolean(true);
        var denied = new AtomicInteger();
        var search = UpstreamBatchOptimizer.startSearch(fixture.graph(), "T", 5, incumbent, fixture.order(),
                work -> { if (enabled.get()) return true; denied.incrementAndGet(); return false; },
                () -> { throw new AssertionError("work exhaustion must precede the first new replay"); });
        assertNotNull(search);
        assertNull(search.tryEstablished());
        enabled.set(false);
        assertNull(search.tryAdditional());
        assertEquals(1, denied.get());
        assertNull(search.tryAdditional());
        assertEquals(1, denied.get());
    }

    @Test
    void sixtyFourEstablishedCandidatesLeaveNoFreshAdditionalAllowance() {
        var fixture = twoOutputs(8, 10);
        var incumbent = plan(fixture, 3, 0, 2, 0, 0, 2);
        var workCalls = new AtomicInteger();
        var search = UpstreamBatchOptimizer.startSearch(fixture.graph(), "T", 5, incumbent, fixture.order(),
                work -> { workCalls.incrementAndGet(); return true; }, () -> true);
        assertNotNull(search);
        assertNull(search.tryEstablished());
        assertEquals(64, search.candidates);
        int before = workCalls.get();
        assertNull(search.tryAdditional());
        assertEquals(before, workCalls.get());
        assertEquals(64, search.candidates);
    }

    @Test
    void stageCancellationPropagatesEvenAfterAnEarlierProbeDenial() {
        var fixture = sharedRaw(27);
        var incumbent = plan(fixture, 4, 0, 5, 3, 3, 8);
        var search = UpstreamBatchOptimizer.startSearch(fixture.graph(), "T", 5, incumbent, fixture.order(),
                work -> true, () -> false);
        assertNotNull(search);
        assertNull(search.tryEstablished());
        assertNull(search.tryAdditional());
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, search::tryAdditional);
        } finally {
            Thread.interrupted();
        }
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, search::tryEstablished);
        }
    }

    private static CraftPlan<String> additional(Fixture fixture, CraftPlan<String> incumbent) {
        var original = Map.copyOf(incumbent.firings());
        var originalStock = Map.copyOf(incumbent.usedStock());
        var search = UpstreamBatchOptimizer.startSearch(fixture.graph(), "T", 5, incumbent, fixture.order(),
                work -> true, () -> true);
        assertNotNull(search);
        assertNull(search.tryEstablished(), "these fixtures require the new deferred neighborhood");
        var result = search.tryAdditional();
        assertTrue(search.candidates <= 64);
        assertEquals(original, incumbent.firings());
        assertEquals(originalStock, incumbent.usedStock());
        if (result != null) {
            assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
            assertCertified(fixture, result);
        }
        return result;
    }

    private static void assertCertified(Fixture fixture, CraftPlan<String> result) {
        assertNotNull(result);
        assertTrue(result.feasible());
        assertTrue(result.missing().isEmpty());
        assertTrue(result.firings().keySet().stream().allMatch(p -> fixture.patterns().stream().anyMatch(q -> p == q)));
        var replay = MaterialDagReplay.tryPlan(fixture.graph(), result.firings(), "T", 5);
        assertNotNull(replay);
        assertEquals(result.usedStock(), replay.usedStock());
    }

    private static Fixture sharedRaw(long raw) {
        var patterns = List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 4), CraftInput.of("B", 1)), null),
                new CraftPattern<>("T", 1, List.of(CraftInput.of("B", 4)), null),
                new CraftPattern<>("A", 2, List.of(CraftInput.of("I", 4)), null),
                new CraftPattern<>("A", 2, List.of(CraftInput.of("raw", 3)), null),
                new CraftPattern<>("B", 1, List.of(CraftInput.of("I", 4)), null),
                new CraftPattern<>("I", 4, List.of(CraftInput.of("raw", 2)), null));
        var builder = CraftGraph.<String>builder().stock("T", 1).stock("B", 1).stock("I", 1).stock("raw", raw);
        patterns.forEach(builder::pattern);
        return new Fixture(builder.build(), patterns, List.of("T", "A", "B", "I", "raw"));
    }

    private static Fixture twoOutputs(long secondStock, int duplicateTargets) {
        var patterns = List.of(
                new CraftPattern<>("T", 2, List.of(CraftInput.of("A", 2)), null),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("A", 3), CraftInput.of("B", 4)), null),
                new CraftPattern<>("A", 2, List.of(CraftInput.of("I", 2)), null),
                new CraftPattern<>("A", 1, List.of(CraftInput.of("S", 3)), null),
                new CraftPattern<>("B", 3, List.of(CraftInput.of("S", 3)), null),
                new CraftPattern<>("I", 2, List.of(CraftInput.of("R", 1)), null));
        var builder = CraftGraph.<String>builder().stock("A", 2).stock("B", 2).stock("I", 1)
                .stock("R", 13).stock("S", secondStock);
        patterns.forEach(builder::pattern);
        for (int index = 0; index < duplicateTargets; index++)
            builder.pattern("T", 2, List.of(CraftInput.of("A", 2)));
        return new Fixture(builder.build(), patterns, List.of("T", "A", "B", "I", "R", "S"));
    }

    private static CraftPlan<String> plan(Fixture fixture, long... counts) {
        var plan = MaterialDagReplay.tryPlan(fixture.graph(), counts(fixture, counts), "T", 5);
        assertNotNull(plan);
        return plan;
    }

    private static Map<CraftPattern<String>, Long> counts(Fixture fixture, long... values) {
        var result = new IdentityHashMap<CraftPattern<String>, Long>();
        for (int i = 0; i < values.length; i++) if (values[i] > 0) result.put(fixture.patterns().get(i), values[i]);
        return result;
    }

    private static long executions(CraftPlan<?> plan) {
        assertNotNull(plan);
        return plan.firings().values().stream().mapToLong(Long::longValue).sum();
    }

    private record Fixture(CraftGraph<String> graph, List<CraftPattern<String>> patterns, List<String> order) {}
}
