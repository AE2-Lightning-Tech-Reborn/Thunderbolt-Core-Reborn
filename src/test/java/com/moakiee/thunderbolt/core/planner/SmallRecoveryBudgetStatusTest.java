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

    @Test void successfulRecoveryMaySpendTheLastWorkUnitWithoutARejection() {
        var result = CraftPlannerV2.planDetailed(terminalMixture(), "T", 23, 256, 71);
        assertTrue(result.plan().feasible());
        assertEquals(71, result.diagnostics().consumedSearchBudget());
        assertFalse(result.plan().budgetExhausted());
        assertFalse(result.diagnostics().searchCutoff());
        assertEquals(4, result.plan().firings().values().stream().mapToLong(n -> n).sum());
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
        assertNull(SmallConservativeSearch.tryPlan(graph, "T", 1, 1, () -> {
            assertEquals(1, ++calls[0], "the local state cutoff must precede another shared work request");
            return true;
        }));
        assertEquals(1, calls[0]);
    }

    @Test void optionalRecoveryDeadlineKeepsTheExistingMissingStatus() {
        var result = optionalRecoveryExpires(terminalMixture(), 48);
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("B", 1L), result.plan().missing());
        assertTrue(result.diagnostics().consumedSearchBudget() < result.diagnostics().configuredSearchBudget());
        assertFalse(result.plan().budgetExhausted());
        assertFalse(result.diagnostics().searchCutoff());
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
