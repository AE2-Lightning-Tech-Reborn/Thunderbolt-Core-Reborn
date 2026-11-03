package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import org.junit.jupiter.api.Test;

class SmallRecoveryBudgetStatusTest {
    @Test void rejectedSharedWorkMarksTheExistingMissingWitnessAndDiagnostics() {
        var graph = terminalMixture();
        var original = optionalRecoveryExpires(graph, 48);
        var rejected = CraftPlannerV2.planDetailed(graph, "T", 23, 256, 48);
        assertFalse(rejected.plan().feasible());
        assertTrue(rejected.plan().budgetExhausted());
        assertTrue(rejected.diagnostics().searchCutoff());
        assertEquals(48, rejected.diagnostics().consumedSearchBudget());
        assertEquals(Map.of("B", 1L), rejected.plan().missing());
        assertSameWitness(original.plan(), rejected.plan());
        var copiedDiagnostics = rejected.diagnostics().withAdditionalSearchWork(0, rejected.diagnostics().totalNanos());
        assertTrue(copiedDiagnostics.searchCutoff(), "the original two-argument API retains an existing cutoff");
    }

    @Test void successfulRecoveryDoesNotInventARejectedWorkRequest() {
        var result = CraftPlannerV2.planDetailed(terminalMixture(), "T", 23, 256, 71);
        assertTrue(result.plan().feasible());
        // The core's tied route order can consume 70 or 71 units. The exact last-unit boundary
        // is independently checked below against the real recovery callback and shared counter.
        assertTrue(result.diagnostics().consumedSearchBudget() > 48);
        assertTrue(result.diagnostics().consumedSearchBudget() <= 71);
        assertFalse(result.plan().budgetExhausted());
        assertFalse(result.diagnostics().searchCutoff());
        assertEquals(4, result.plan().firings().values().stream().mapToLong(n -> n).sum());
    }

    @Test void smallWitnessAcceptsTheLastWorkUnitButRejectsTheNextRequest() {
        var graph = terminalMixture();
        int[] consumed = {0};
        var measured = SmallConservativeSearch.tryPlan(graph, "T", 23, 4096, () -> { consumed[0]++; return true; });
        assertNotNull(measured);
        int needed = consumed[0];
        var exact = new CraftPlannerV2.SharedCounterBudget(needed);
        boolean[] cutoff = {false};
        var recovered = SmallConservativeSearch.tryPlan(graph, "T", 23, 4096,
                () -> exact.tryConsume(1), () -> cutoff[0] = true);
        assertNotNull(recovered);
        assertTrue(recovered.feasible());
        assertEquals(0, exact.remaining);
        assertFalse(cutoff[0]);
        var insufficient = new CraftPlannerV2.SharedCounterBudget(needed - 1);
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 23, 4096,
                () -> insufficient.tryConsume(1), () -> cutoff[0] = true));
        assertEquals(0, insufficient.remaining);
        assertTrue(cutoff[0]);
        assertTrue(UnorderedByproductSafety.allOrdersFinishSmall(recovered, "T", 23));
    }

    @Test void naturallyExhaustedQueueDoesNotTurnExactBudgetUseIntoARejection() {
        var graph = CraftGraph.<String>builder().pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
        var result = CraftPlannerV2.planDetailed(graph, "T", 1, 256, 1);
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("raw", 1L), result.plan().missing());
        assertEquals(1, result.diagnostics().consumedSearchBudget());
        assertFalse(result.plan().budgetExhausted());
        assertFalse(result.diagnostics().searchCutoff());
    }

    @Test void localStateLimitDoesNotAskTheSharedBudgetForAnotherState() {
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
        int[] calls = {0};
        boolean[] cutoff = {false};
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 1, 1, () -> {
            assertEquals(1, ++calls[0], "the local state cutoff must precede another shared work request");
            return true;
        }, () -> cutoff[0] = true));
        assertEquals(1, calls[0]);
        assertTrue(cutoff[0]);
    }

    @Test void naturallyExhaustedRecoveryQueueDoesNotReportACutoffAtAnExactWorkBoundary() {
        var graph = CraftGraph.<String>builder().pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
        var work = new CraftPlannerV2.SharedCounterBudget(1);
        boolean[] cutoff = {false};
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 1, 4096,
                () -> work.tryConsume(1), () -> cutoff[0] = true));
        assertEquals(0, work.remaining);
        assertFalse(cutoff[0]);
    }

    @Test void depthLimitReportsOnlyAnUnexploredEnabledSuccessor() {
        int limit = SmallConservativeSearch.MAX_FIRINGS;
        for (int stock : new int[] {limit, limit + 1}) {
            var graph = CraftGraph.<String>builder().stock("raw", stock)
                    .pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
            boolean[] cutoff = {false};
            assertNull(SmallConservativeSearch.tryPlan(graph, "T", 2L * limit + 1L, 4096,
                    () -> true, () -> cutoff[0] = true));
            assertEquals(stock > limit, cutoff[0], "a dead end is complete; another enabled firing is truncated");
            cutoff[0] = false;
            var exact = SmallConservativeSearch.tryPlan(graph, "T", 2L * limit, 4096,
                    () -> true, () -> cutoff[0] = true);
            assertNotNull(exact);
            assertTrue(exact.feasible());
            assertFalse(cutoff[0]);
        }
    }

    @Test void optionalRecoveryDeadlineMarksMissingAsAnIncompleteSearch() {
        var result = optionalRecoveryExpires(terminalMixture(), 48);
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("B", 1L), result.plan().missing());
        assertTrue(result.diagnostics().consumedSearchBudget() < result.diagnostics().configuredSearchBudget());
        assertTrue(result.plan().budgetExhausted(), "expiry is UNKNOWN, not a completed missing proof");
        assertTrue(result.diagnostics().searchCutoff());
    }

    @Test void externalCancellationDuringRecoveryStillPropagates() {
        var cancellation = new CancellationException("cancel inside recovery");
        var context = new RecoveryCheckpoint(cancellation);
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(cancellation, assertThrows(CancellationException.class,
                    () -> CraftPlannerV2.planDetailed(terminalMixture(), "T", 23, 256, 48)));
        }
        assertTrue(context.enteredRecovery);
    }

    @Test void exhaustedRecoveryAllowanceKeepsTheNextQuantityProbeUnknown() {
        var graph = terminalMixture();
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 1, session).plan().feasible());
        // This calculation has already spent the optional allowance on earlier probes.
        session.conservativeSearchNanos = SmallConservativeSearch.MAX_NANOS;
        var result = CraftPlannerV2.planDetailed(graph, "T", 23, session);
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("B", 1L), result.plan().missing());
        // Independent sequential witness: two 9T batches and two 4T batches use A4/B14.
        long a = 5, b = 14, produced = 0;
        for (long[] firing : new long[][] {{9, 1, 5}, {9, 1, 5}, {4, 1, 2}, {4, 1, 2}}) {
            assertTrue(a >= firing[1] && b >= firing[2]);
            a -= firing[1]; b -= firing[2]; produced += firing[0];
        }
        assertTrue(produced >= 23);
        assertTrue(result.plan().budgetExhausted(), "skipped recovery is not an infeasibility proof");
        assertTrue(result.diagnostics().searchCutoff());
    }

    @Test void exhaustedOptionalAllowanceStillKeepsACertifiedFeasibleProbe() {
        var graph = terminalMixture();
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.conservativeSearchNanos = SmallConservativeSearch.MAX_NANOS;
        var result = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        assertTrue(result.plan().feasible());
        assertFalse(result.plan().budgetExhausted());
    }

    @Test void exhaustedSharedWorkDoesNotCertifyAnAmbiguousMissingRoute() {
        var result = CraftPlannerV2.planDetailed(terminalMixture(), "T", 23, 256, 4);
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("B", 1L), result.plan().missing());
        assertEquals(4, result.diagnostics().consumedSearchBudget());
        assertTrue(result.plan().budgetExhausted(), "the feasible mixture still needs recovery");
        assertTrue(result.diagnostics().searchCutoff());
    }

    @Test void exhaustedOptionalAllowanceDoesNotInvalidateAnUnambiguousRawShortage() {
        var graph = CraftGraph.<String>builder().pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.conservativeSearchNanos = SmallConservativeSearch.MAX_NANOS;
        var result = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("raw", 1L), result.plan().missing());
        assertFalse(result.plan().budgetExhausted());
        assertFalse(result.diagnostics().searchCutoff());
    }

    private static CraftGraph<String> terminalMixture() {
        long[] routes = {1,3,3, 2,3,2, 9,1,5, 12,5,4, 4,1,2, 3,4,4, 10,6,6, 8,2,5};
        var builder = CraftGraph.<String>builder().stock("A", 5).stock("B", 14);
        for (int i = 0; i < routes.length; i += 3)
            builder.pattern("T", routes[i], List.of(CraftInput.of("A", routes[i + 1]), CraftInput.of("B", routes[i + 2])));
        return builder.build();
    }

    private static PlanningResult<String> optionalRecoveryExpires(CraftGraph<String> graph, int budget) {
        var context = new RecoveryCheckpoint(null);
        PlanningResult<String> result;
        try (var ignored = PlanningCancellation.bind(context)) {
            result = CraftPlannerV2.planDetailed(graph, "T", 23, 256, budget);
        }
        assertTrue(context.enteredRecovery);
        return result;
    }

    private static void assertSameWitness(CraftPlan<String> expected, CraftPlan<String> actual) {
        assertEquals(expected.supported(), actual.supported());
        assertEquals(expected.feasible(), actual.feasible());
        assertEquals(expected.firings(), actual.firings());
        assertEquals(expected.usedStock(), actual.usedStock());
        assertEquals(expected.usedReusableStock(), actual.usedReusableStock());
        assertEquals(expected.missing(), actual.missing());
        assertEquals(expected.grossDemand(), actual.grossDemand());
        assertEquals(expected.itemsProcessed(), actual.itemsProcessed());
    }

    private static final class RecoveryCheckpoint implements PlanningAttemptContext {
        private final CancellationException cancellation;
        private boolean enteredRecovery;

        RecoveryCheckpoint(CancellationException cancellation) { this.cancellation = cancellation; }

        @Override public long deadlineNanos() { return Long.MAX_VALUE; }
        @Override public void report(PlanningDiagnosticSnapshot snapshot) {}

        @Override public void checkpoint() {
            if (enteredRecovery || !StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals(SmallConservativeSearch.class.getName())))) return;
            enteredRecovery = true;
            if (cancellation != null) throw cancellation;
            // Expire only the helper's optional 20 ms window, after the core plan is complete.
            // This deterministic delay avoids changing the enclosing deadline or the work budget.
            try { Thread.sleep(30); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }
}
