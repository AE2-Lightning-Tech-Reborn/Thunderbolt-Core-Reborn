package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MissingRefinementTest {
    @Test
    void partialStockFibReachesTheMinimumSupplyAndReplays() {
        var graph = fib(32, Map.of("X0", 1L, "X1", 1L, "X2", 1L));
        var result = CraftPlannerV2.planDetailed(graph, "X32", 1);
        // Every production tree needs >= 5842 raw units, of which 3 already exist.
        assertEquals(Map.of("X1", 2512L, "X2", 3327L), result.plan().missing());
        assertTrue(result.diagnostics().missingRefinementImprovements() > 0);
        ReplenishmentContractTest.assertExactBalance(graph, "X32", 1, result.plan());
        assertTrue(CraftPlannerV2.plan(graph.withAdditionalStock(result.plan().missing()),
                "X32", 1, 1, 1).feasible());
    }

    @Test
    void removesDominatedMissingAtAnOrdinaryAlternative() {
        var graph = fib(3, Map.of("X0", 1L));
        var result = CraftPlannerV2.planDetailed(graph, "X3", 1);
        assertEquals(Map.of("X1", 1L), result.plan().missing());
        ReplenishmentContractTest.assertExactBalance(graph, "X3", 1, result.plan());
    }

    @Test
    void refinedRandomDagsRemainExecutableWhenReplannedWithTinyBudgets() {
        var random = new Random(730917L);
        for (int sample = 0; sample < 100; sample++) {
            var b = CraftGraph.<String>builder();
            for (int i = 0; i < 8; i++) {
                b.stock("M" + i, random.nextInt(4));
                if (i >= 6) continue;
                for (int choice = 0; choice < 2; choice++) {
                    var inputs = new ArrayList<CraftInput<String>>();
                    inputs.add(CraftInput.of("M" + (i + 1 + random.nextInt(7 - i)), 1 + random.nextInt(3)));
                    inputs.add(CraftInput.of("M" + (i + 1 + random.nextInt(7 - i)), 1 + random.nextInt(3)));
                    b.pattern("M" + i, 1 + random.nextInt(2), inputs);
                }
            }
            var graph = b.build();
            var result = CraftPlannerV2.planDetailed(graph, "M0", 4);
            ReplenishmentContractTest.assertExactBalance(graph, "M0", 4, result.plan());
            var next = CraftPlannerV2.plan(graph.withAdditionalStock(result.plan().missing()), "M0", 4, 1, 1);
            assertTrue(next.feasible(), "sample=" + sample + " first=" + result.plan() + " next=" + next);
            assertTrue(result.diagnostics().missingRefinementProbes() <= CraftPlannerV2.MAX_MISSING_REFINEMENT_PROBES);
        }
    }

    @Test
    void quantityProbesShareTheRefinementLimit() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("A", 2), CraftInput.of("B", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 2)))
                .stock("A", 100).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        int probes = 0;
        for (int amount = 1; amount <= 32; amount++) {
            var result = CraftPlannerV2.planDetailed(graph, "T", amount, session);
            probes += result.diagnostics().missingRefinementProbes();
            assertEquals(Map.of("B", (long) amount), result.plan().missing());
        }
        assertTrue(probes > 0);
        assertTrue(probes <= CraftPlannerV2.MAX_MISSING_REFINEMENT_PROBES);
    }

    @Test
    void largeAmountsDoNotBecomeUnitSizedSearches() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            long amount = 1_000_000_000L;
            var graph = fib(32, Map.of("X0", amount, "X1", amount, "X2", amount));
            var result = CraftPlannerV2.planDetailed(graph, "X32", amount);
            assertTrue(result.diagnostics().missingRefinementProbes() <= CraftPlannerV2.MAX_MISSING_REFINEMENT_PROBES);
            ReplenishmentContractTest.assertExactBalance(graph, "X32", amount, result.plan());
        });
    }

    @Test
    void optionalDeadlineKeepsTheWitnessAndRestoresTheCheckpoint() {
        var graph = fib(3, Map.of());
        var plan = CraftPlannerV2.plan(graph, "X3", 1, 1, 1);
        try (var ignored = PlanningCancellation.limitOptionalWork(0L)) {
            assertSame(plan, MissingRefinement.refine(graph, plan, List.of("X1", "X2"),
                    candidate -> false,
                    supplied -> { fail("expired stage should not call the planner"); return null; }, count -> {}));
        }
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void outerCancellationIsNeverConvertedIntoAMissingResult() {
        var graph = fib(3, Map.of());
        var plan = CraftPlannerV2.plan(graph, "X3", 1, 1, 1);
        assertThrows(PlanningExitException.class, () -> MissingRefinement.refine(
                graph, plan, List.of("X1", "X2"), candidate -> false, supplied -> {
                    throw new PlanningExitException("outer cancellation");
                }, count -> {}));
    }

    private static CraftGraph<String> fib(int depth, Map<String, Long> stock) {
        var b = CraftGraph.<String>builder();
        for (int i = 3; i <= depth; i++) {
            b.pattern("X" + i, 1, List.of(CraftInput.of("X" + (i - 1), 1), CraftInput.of("X" + (i - 2), 1)));
            b.pattern("X" + i, 1, List.of(CraftInput.of("X" + (i - 2), 1), CraftInput.of("X" + (i - 3), 1)));
        }
        stock.forEach(b::stock);
        return b.build();
    }
}
