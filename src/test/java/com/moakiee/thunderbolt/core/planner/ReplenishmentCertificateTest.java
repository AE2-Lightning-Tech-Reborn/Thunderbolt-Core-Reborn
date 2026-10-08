package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import org.junit.jupiter.api.Test;

class ReplenishmentCertificateTest {
    @Test
    void smallFeedbackKeepsItsMinimumSeedWithoutOptionalReplanning() {
        for (long stock : new long[] {1, 5}) {
            var fixture = weightedFeedback(stock, 2);
            var session = new CraftPlannerV2.PlanningSession<String>();
            session.refineMissing = false;
            var result = CraftPlannerV2.planDetailed(fixture.graph(), "T", 2, session);

            assertEquals(Map.of("A", 6 - stock), result.plan().missing());
            assertEquals(Map.of("A", stock, "raw", 2L), result.plan().usedStock());
            assertEquals(3L, result.plan().firings().get(fixture.makeB()));
            assertEquals(2L, result.plan().firings().get(fixture.makeTarget()));
            assertFalse(result.plan().feasible());
            assertEquals(0, result.diagnostics().missingRefinementProbes());
            assertEquals(0, result.diagnostics().consumedSearchBudget());
            assertEquals(1, result.diagnostics().planRuns());
            assertTrue(CraftPlannerV2.plan(
                    fixture.graph().withAdditionalStock(result.plan().missing()), "T", 2, 1, 1).feasible());
        }
    }

    @Test
    void smallFeedbackRetainsExternalConsumableShortfallsWithoutOptionalReplanning() {
        var fixture = weightedFeedback(1, 0);
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.refineMissing = false;
        var result = CraftPlannerV2.planDetailed(fixture.graph(), "T", 2, session);

        assertEquals(Map.of("A", 5L, "raw", 2L), result.plan().missing());
        assertEquals(Map.of("A", 1L), result.plan().usedStock());
        assertEquals(0, result.diagnostics().missingRefinementProbes());
        assertTrue(CraftPlannerV2.plan(
                fixture.graph().withAdditionalStock(result.plan().missing()), "T", 2, 1, 1).feasible());
    }

    @Test
    void cancellationDuringTheSmallReplenishmentCertificateStillPropagates() {
        var fixture = weightedFeedback(1, 2);
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.refineMissing = false;
        var exit = new PlanningExitException("cancel small replenishment certificate");
        boolean[] observed = {false};
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) { }
            @Override public void checkpoint() {
                boolean certificate = false, replenishment = false;
                for (var frame : Thread.currentThread().getStackTrace()) {
                    certificate |= frame.getClassName().equals(MaterialDagReplay.class.getName())
                            && frame.getMethodName().equals("trySmallPlan");
                    replenishment |= frame.getClassName().equals(CraftPlannerV2.class.getName())
                            && frame.getMethodName().equals("rechecksReplenishment");
                }
                if (certificate && replenishment) {
                    observed[0] = true;
                    throw exit;
                }
            }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> CraftPlannerV2.planDetailed(fixture.graph(), "T", 2, session)));
        }
        assertTrue(observed[0], "must cancel the new replenishment certificate path");
        assertDoesNotThrow(PlanningCancellation::check);
    }

    private static Fixture weightedFeedback(long seed, long raw) {
        var makeB = new CraftPattern<>("B", 2, List.of(CraftInput.of("A", 3)), "3A_to_2B");
        var makeTarget = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("B", 3), CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("A", 4)), "3B_raw_to_T_4A");
        var graph = CraftGraph.<String>builder().pattern(makeB).pattern(makeTarget)
                .stock("A", seed).stock("raw", raw).build();
        return new Fixture(graph, makeB, makeTarget);
    }

    private record Fixture(CraftGraph<String> graph, CraftPattern<String> makeB,
                           CraftPattern<String> makeTarget) { }
}
