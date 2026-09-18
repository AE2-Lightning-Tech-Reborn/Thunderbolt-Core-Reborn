package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class CombinedCycleOrientationTest {

    @Test
    void independentLossyRingsCanBothChangeDirection() {
        for (int rings : new int[] {2, 3, 8, 16}) {
            CraftGraph<String> graph = rings(rings, defaultInputs(rings), false, 4, -1);
            PlanningResult<String> result = CraftPlannerV2.planDetailed(graph, "T", 1);

            assertTrue(result.plan().feasible(), () -> "rings=" + rings + ": " + result);
            assertFalse(result.plan().budgetExhausted());
            assertTrue(result.diagnostics().planRuns() <= 1 + rings);
            assertWitness(result.plan(), rings, 1);
        }
    }

    @Test
    void rootInputAndPatternRegistrationOrderDoNotChangeFeasibility() {
        for (List<String> order : permutations(List.of("A0", "B0", "A1", "B1"))) {
            for (boolean reversePatterns : List.of(false, true)) {
                CraftPlan<String> plan = CraftPlannerV2.plan(
                        rings(2, order, reversePatterns, 4, -1), "T", 1);

                assertTrue(plan.feasible(), () -> order + ": " + plan.missing());
                assertWitness(plan, 2, 1);
            }
        }
    }

    @Test
    void stockBackedComplexComponentsCanCombineTheirCuts() {
        var builder = CraftGraph.<String>builder();
        var demand = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < 2; i++) {
            String x = "X" + i, y = "Y" + i, z = "Z" + i;
            demand.add(CraftInput.of(y, 1));
            demand.add(CraftInput.of(x, 1));
            demand.add(CraftInput.of(z, 1));
            builder.pattern(x, 1, List.of(CraftInput.of(y, 1), CraftInput.of(z, 1)));
            builder.pattern(y, 1, List.of(CraftInput.of(x, 1)));
            builder.pattern(z, 1, List.of(CraftInput.of(x, 1)));
            builder.stock(y, 2).stock(z, 2);
        }
        CraftPlan<String> plan = CraftPlannerV2.plan(builder.pattern("T", 1, demand).build(), "T", 1);

        assertTrue(plan.feasible(), () -> plan.missing().toString());
        assertEquals(2L, plan.usedStock().get("Y0"));
        assertEquals(2L, plan.usedStock().get("Z0"));
        assertEquals(2L, plan.usedStock().get("Y1"));
        assertEquals(2L, plan.usedStock().get("Z1"));
    }

    @Test
    void compressionChainsRepairMissingOnOtherMembersInBothComponents() {
        var builder = CraftGraph.<String>builder();
        var demand = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < 2; i++) {
            String a = "A" + i, b = "B" + i, c = "C" + i;
            demand.add(CraftInput.of(a, 1));
            demand.add(CraftInput.of(c, 1));
            builder.pattern(a, 1, List.of(CraftInput.of(b, 9)));
            builder.pattern(b, 1, List.of(CraftInput.of(c, 9)));
            builder.pattern(c, 9, List.of(CraftInput.of(b, 1)));
            builder.pattern(b, 9, List.of(CraftInput.of(a, 1)));
            builder.stock(a, 2);
        }
        CraftPlan<String> plan = CraftPlannerV2.plan(builder.pattern("T", 1, demand).build(), "T", 1);

        assertTrue(plan.feasible(), () -> plan.missing().toString());
        assertEquals(2L, plan.usedStock().get("A0"));
        assertEquals(2L, plan.usedStock().get("A1"));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void combinedDirectionsRecheckSharedRawCapacity() {
        for (long raw : new long[] {7, 8}) {
            CraftPlan<String> plan = CraftPlannerV2.plan(
                    rings(2, defaultInputs(2), false, 0, raw), "T", 1);

            assertEquals(raw == 8, plan.feasible(), () -> "raw=" + raw + ": " + plan);
            assertTrue(plan.usedStock().getOrDefault("raw", 0L) <= raw);
            if (raw == 8) assertWitness(plan, 2, 1);
            else assertFalse(plan.missing().isEmpty());
        }
    }

    @Test
    void cachedDirectionsRevalidateQuantitiesInTheSameSession() {
        for (List<Long> amounts : List.of(List.of(1L, 3L, 2L), List.of(3L, 2L, 1L))) {
            var graph = rings(2, defaultInputs(2), false, 8, -1);
            var session = new CraftPlannerV2.PlanningSession<String>();
            for (long amount : amounts) {
                var result = CraftPlannerV2.planDetailed(graph, "T", amount, session);

                assertEquals(amount <= 2, result.plan().feasible(),
                        () -> "amount=" + amount + ": " + result.plan());
                assertTrue(result.plan().usedStock().getOrDefault("B0", 0L) <= 8);
                assertTrue(result.plan().usedStock().getOrDefault("B1", 0L) <= 8);
                if (amount <= 2) assertWitness(result.plan(), 2, amount);
            }
        }
    }

    @Test
    void zeroSeedAndInsufficientStockCannotBecomeFeasible() {
        for (long stock : new long[] {0, 1, 3}) {
            CraftPlan<String> plan = CraftPlannerV2.plan(
                    rings(2, defaultInputs(2), false, stock, -1), "T", 1);

            assertFalse(plan.feasible());
            assertFalse(plan.missing().isEmpty());
            assertTrue(plan.usedStock().getOrDefault("B0", 0L) <= stock);
            assertTrue(plan.usedStock().getOrDefault("B1", 0L) <= stock);
        }
    }

    @Test
    void manyIndependentDirectionsShareOneReplayedCandidate() {
        for (int count : new int[] {17, 64, 1024}) {
            var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> CraftPlannerV2.planDetailed(
                    rings(count, defaultInputs(count), false, 4, -1), "T", 1));
            assertTrue(result.plan().feasible(), () -> "rings="+count+" "+result.plan().missing());
            assertFalse(result.plan().budgetExhausted());
            assertEquals(2, result.diagnostics().planRuns());
            assertWitness(result.plan(), count, 1);
        }
    }

    @Test
    void bulkDirectionsStillAccountForOneSharedRawInventory() {
        for (long raw : new long[] {67, 68}) {
            var result = CraftPlannerV2.planDetailed(rings(17, defaultInputs(17), false, 0, raw), "T", 1);
            assertEquals(raw == 68, result.plan().feasible(), () -> result.toString());
            assertTrue(result.plan().usedStock().getOrDefault("raw", 0L) <= raw);
            if (raw == 68) assertWitness(result.plan(), 17, 1);
        }
    }

    @Test
    void bulkHintsLeaveAlreadyFundedComponentsInTheirWorkingDirection() {
        var builder = CraftGraph.<String>builder();
        for (int i = 0; i < 32; i++) {
            String a = "A"+i, b = "B"+i;
            builder.pattern(a, 1, List.of(CraftInput.of(b, 3)));
            builder.pattern(b, 1, List.of(CraftInput.of(a, 3)));
            builder.stock(i % 2 == 0 ? a : b, 4);
        }
        var graph = builder.pattern("T", 1, defaultInputs(32).stream()
                .map(key -> CraftInput.of(key, 1)).toList()).build();
        var result = CraftPlannerV2.planDetailed(graph, "T", 1);
        assertTrue(result.plan().feasible(), () -> result.toString());
        assertEquals(2, result.diagnostics().planRuns());
        for (int i = 0; i < 32; i++)
            assertEquals(4L, result.plan().usedStock().get((i % 2 == 0 ? "A" : "B")+i));
    }

    @Test
    void combinedRetriesStillRespectTheSharedWorkBudget() {
        // Eight cyclic keys exceed the bounded material-DAG portfolio. Optional orientation
        // search must still stop when its own shared allowance is exhausted.
        var result = CraftPlannerV2.planDetailed(
                rings(4, defaultInputs(4), false, 4, -1), "T", 1,
                CraftPlannerV2.DEFAULT_VISIT_CAP, 1);

        assertFalse(result.plan().feasible());
        assertTrue(result.plan().budgetExhausted());
        assertTrue(result.diagnostics().searchCutoff());
        assertEquals(1, result.diagnostics().planRuns());
    }

    @Test
    void smallDeterministicPoliciesRemainAvailableAfterSearchBudgetIsSpent() {
        var result = CraftPlannerV2.planDetailed(
                rings(2, defaultInputs(2), false, 4, -1), "T", 1, 1, 1);
        assertTrue(result.plan().feasible());
        assertEquals(1, result.diagnostics().planRuns());
        assertTrue(result.diagnostics().consumedSearchBudget() <= 1);
        assertWitness(result.plan(), 2, 1);
    }

    private static CraftGraph<String> rings(
            int count, List<String> inputs, boolean reversePatterns, long stockPerRing, long raw) {
        var builder = CraftGraph.<String>builder();
        var patterns = new ArrayList<CraftPattern<String>>();
        for (int i = 0; i < count; i++) {
            String a = "A" + i, b = "B" + i;
            patterns.add(new CraftPattern<>(a, 1, List.of(CraftInput.of(b, 3)), a));
            patterns.add(new CraftPattern<>(b, 1, List.of(CraftInput.of(a, 3)), b));
            if (raw >= 0) {
                patterns.add(new CraftPattern<>(b, 1, List.of(CraftInput.of("raw", 1)), "raw-" + b));
            }
            builder.stock(b, stockPerRing);
        }
        if (reversePatterns) Collections.reverse(patterns);
        patterns.forEach(builder::pattern);
        if (raw >= 0) builder.stock("raw", raw);
        return builder.pattern("T", 1, inputs.stream().map(key -> CraftInput.of(key, 1)).toList()).build();
    }

    private static List<String> defaultInputs(int rings) {
        var inputs = new ArrayList<String>();
        for (int i = 0; i < rings; i++) {
            inputs.add("B" + i);
            inputs.add("A" + i);
        }
        return inputs;
    }

    private static void assertWitness(CraftPlan<String> plan, int rings, long amount) {
        assertTrue(plan.missing().isEmpty());
        for (int i = 0; i < rings; i++) {
            String a = "A" + i, b = "B" + i;
            long madeA = plan.firings().entrySet().stream()
                    .filter(entry -> a.equals(entry.getKey().source()))
                    .mapToLong(java.util.Map.Entry::getValue).sum();
            long reverse = plan.firings().entrySet().stream()
                    .filter(entry -> b.equals(entry.getKey().source()))
                    .mapToLong(java.util.Map.Entry::getValue).sum();
            assertEquals(amount, madeA);
            assertEquals(0L, reverse);
        }
        long targetFirings = plan.firings().entrySet().stream()
                .filter(entry -> entry.getKey().output().equals("T"))
                .mapToLong(java.util.Map.Entry::getValue).sum();
        assertEquals(amount, targetFirings);
    }

    private static List<List<String>> permutations(List<String> values) {
        if (values.isEmpty()) return List.of(List.of());
        var result = new ArrayList<List<String>>();
        for (int i = 0; i < values.size(); i++) {
            var remaining = new ArrayList<>(values);
            String first = remaining.remove(i);
            for (var suffix : permutations(remaining)) {
                var order = new ArrayList<String>();
                order.add(first);
                order.addAll(suffix);
                result.add(List.copyOf(order));
            }
        }
        return result;
    }
}
