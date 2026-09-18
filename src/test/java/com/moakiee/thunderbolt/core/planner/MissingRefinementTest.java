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
            int[] depths = {16, 24, 32, 48, 64};
            long[] minimumRaw = {65, 616, 5_842, 525_456, 47_261_895};
            for (int i = 0; i < depths.length; i++) {
                for (long amount : new long[]{1, 1_000_000L, 1_000_000_000_000L}) {
                    if (minimumRaw[i] > (Sat.SAT - 1) / amount) continue;
                    String target = "X" + depths[i];
                    var graph = fib(depths[i], Map.of("X0", amount, "X1", amount, "X2", amount));
                    var result = CraftPlannerV2.planDetailed(graph, target, amount);
                    // Every raw leaf has positive unit weight. An executable witness at this
                    // total lower bound rules out every componentwise smaller missing vector.
                    assertEquals((minimumRaw[i] - 3) * amount,
                            result.plan().missing().values().stream().mapToLong(Long::longValue).sum());
                    assertTrue(result.plan().missing().keySet().stream().allMatch(
                            key -> List.of("X0", "X1", "X2").contains(key)));
                    assertTrue(result.diagnostics().missingRefinementProbes() <= 2);
                    ReplenishmentContractTest.assertExactBalance(graph, target, amount, result.plan());
                    assertTrue(CraftPlannerV2.plan(graph.withAdditionalStock(result.plan().missing()),
                            target, amount, 1, 1).feasible());
                }
            }
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
    void fractionalRawCostsGuideBatchedReplenishmentToAnExecutableMinimum() {
        for (long amount : new long[]{8, 8_000_000L, 8_000_000_000_000L}) {
            var original = fib(32, Map.of());
            var builder = CraftGraph.<String>builder();
            for (int i = 3; i <= 32; i++) original.patternsFor("X" + i).forEach(builder::pattern);
            for (int i = 0; i < 3; i++) builder.pattern("X" + i, 4, List.of(CraftInput.of("R" + i, 1)))
                    .stock("R" + i, amount / 4);
            var graph = builder.build();
            var result = CraftPlannerV2.planDetailed(graph, "X32", amount);
            assertEquals(5_839 * (amount / 4), result.plan().missing().values().stream().mapToLong(Long::longValue).sum());
            assertTrue(result.plan().missing().keySet().stream().allMatch(List.of("R0", "R1", "R2")::contains));
            assertTrue(result.diagnostics().missingRefinementProbes() <= 2);
            ReplenishmentContractTest.assertExactBalance(graph, "X32", amount, result.plan());
            assertTrue(CraftPlannerV2.plan(graph.withAdditionalStock(result.plan().missing()), "X32", amount, 1, 1).feasible());
        }
    }

    @Test
    void anAlternativeRoutesActualExtractionCanTightenATrillionSizedSupplyInTwoProbes() {
        for (long original : new long[]{1_000L, 1_000_000_000_000L}) {
            var costly = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", original)), "costly");
            var graph = CraftGraph.<String>builder().pattern(costly)
                    .pattern("T", 1, List.of(CraftInput.of("A", 1))).build();
            var initial = new CraftPlan<>(true, false, Map.of(costly, 1L), Map.<String, Long>of(),
                    Map.<ReusableStockUsageKey<String>, Long>of(), Map.of("A", original),
                    Map.of("A", original, "T", 1L), 2, false);
            var probes = new ArrayList<Map<String, Long>>();
            var result = MissingRefinement.refine(graph, initial, List.of("A"),
                    candidate -> candidate.missing().equals(Map.of("A", 1L)), supplied -> {
                        probes.add(supplied);
                        assertTrue(probes.size() <= 2);
                        return CraftPlannerV2.plan(graph.withAdditionalStock(supplied), "T", 1, 1, 1);
                    }, count -> {});
            assertEquals(List.of(Map.of("A", original - 1), Map.of("A", 1L)), probes);
            assertEquals(Map.of("A", 1L), result.missing());
            ReplenishmentContractTest.assertExactBalance(graph, "T", 1, result);
            assertTrue(CraftPlannerV2.plan(graph.withAdditionalStock(result.missing()), "T", 1, 1, 1).feasible());
        }
    }

    @Test
    void tighterExtractionMustBeRecheckedAndKeepsThePreviousWitnessAtACutoff() {
        var graph = CraftGraph.<String>builder().pattern("T", 1, List.of(CraftInput.of("A", 1))).build();
        var template = CraftPlannerV2.plan(graph, "T", 1);
        var initial = new CraftPlan<>(true, false, template.firings(), Map.<String, Long>of(),
                template.usedReusableStock(), Map.of("A", 100L), template.grossDemand(), 2, false);
        for (boolean unavailable : new boolean[]{false, true}) {
            var probes = new ArrayList<Map<String, Long>>();
            var result = MissingRefinement.refine(graph, initial, List.of("A"), candidate -> false,
                    supplied -> {
                        probes.add(supplied);
                        if (probes.size() > 2) return null;
                        if (supplied.equals(Map.of("A", 1L))) return unavailable ? null : template;
                        return CraftPlannerV2.plan(graph.withAdditionalStock(supplied), "T", 1, 1, 1);
                    }, count -> {});
            assertEquals(Map.of("A", 99L), result.missing());
            assertEquals(Map.of("A", 1L), probes.get(1));
            ReplenishmentContractTest.assertExactBalance(graph, "T", 1, result);
        }
    }

    @Test
    void aTighteningDeadlineRetainsTheFirstProbeButOuterCancellationStillEscapes() {
        var graph = CraftGraph.<String>builder().pattern("T", 1, List.of(CraftInput.of("A", 1))).build();
        var template = CraftPlannerV2.plan(graph, "T", 1);
        var initial = new CraftPlan<>(true, false, template.firings(), Map.<String, Long>of(),
                template.usedReusableStock(), Map.of("A", 100L), template.grossDemand(), 2, false);
        for (boolean outer : new boolean[]{false, true}) {
            int[] calls = {0};
            java.util.function.Supplier<CraftPlan<String>> run = () -> MissingRefinement.refine(
                    graph, initial, List.of("A"), candidate -> false, supplied -> {
                        if (++calls[0] > 2) return null;
                        if (calls[0] == 2) {
                            if (outer) throw new PlanningExitException("outer deadline");
                            try (var ignored = PlanningCancellation.limitOptionalWork(0L)) {
                                PlanningCancellation.check();
                            }
                        }
                        return CraftPlannerV2.plan(graph.withAdditionalStock(supplied), "T", 1, 1, 1);
                    }, count -> {});
            if (outer) assertThrows(PlanningExitException.class, run::get);
            else {
                var result = run.get();
                assertEquals(Map.of("A", 99L), result.missing());
                ReplenishmentContractTest.assertExactBalance(graph, "T", 1, result);
            }
            assertDoesNotThrow(PlanningCancellation::check);
        }
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
