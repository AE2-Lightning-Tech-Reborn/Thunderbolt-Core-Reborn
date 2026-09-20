package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmallDagPoliciesTest {
    @Test void tooManyStructuralChoicesAreDeclinedBeforeEnumeration() {
        var b = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)));
        for (int i = 1; i <= SmallDagPolicies.MAX_CHOICES + 1; i++)
            b.pattern("T", 1, List.of(CraftInput.of("A", i)));
        assertTrue(SmallDagPolicies.compile(b.build(), "T").isEmpty());
    }

    @Test void independentUpstreamCyclesKeepDistinctRootTraversalOrders() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("C", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .pattern("C", 1, List.of(CraftInput.of("D", 1)))
                .pattern("D", 1, List.of(CraftInput.of("C", 1))).build();
        var cycles = CycleAnalysis.analyze(graph, "T");
        assertEquals(List.of("A", "C"), cycles.canonicalCuts(List.of("A", "C")));
        assertEquals(List.of("C", "A"), cycles.canonicalCuts(List.of("C", "A")));
    }

    @Test void largeCyclesRetainTheBudgetedPlannerPath() {
        var b = CraftGraph.<String>builder();
        for (int i = 0; i < 6; i++)
            b.pattern("M" + i, 1, List.of(CraftInput.of("M" + ((i + 1) % 6), 1)));
        assertTrue(SmallDagPolicies.compile(b.build(), "M0").isEmpty());
    }

    @Test void excludingAByproductRecipeCannotTurnAnAcyclicIntermediateIntoALeaf() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("X", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .pattern("X", 1, List.of(CraftInput.of("R", 1)), List.of(CraftOutput.of("Y", 1)))
                .build();
        assertTrue(SmallDagPolicies.compile(graph, "T").isEmpty());
    }

    @Test void everyRetainedPolicySurvivesItsOwnSupplyAtTrillionScale() {
        var graph = CraftGraph.<String>builder().stock("M0", 1)
                .pattern("M5", 1, List.of(CraftInput.of("M0", 1), CraftInput.of("M1", 1)))
                .pattern("M0", 1, List.of(CraftInput.of("M1", 2)))
                .pattern("M1", 1, List.of(CraftInput.of("M0", 1))).build();
        var policies = SmallDagPolicies.compile(graph, "M5");
        assertFalse(policies.isEmpty());
        assertTrue(policies.size() <= SmallDagPolicies.MAX_CHOICES);
        for (var policy : policies) for (long amount : new long[] {1, 1_000_000_000_000L}) {
            var plan = policy.plan("M5", amount, Map.of());
            assertNotNull(plan);
            CutPolicyRegressionTest.assertBatchExecutable(graph, plan, amount);
            var supplied = policy.plan("M5", amount, plan.missing());
            assertTrue(supplied.feasible());
            CutPolicyRegressionTest.assertBatchExecutable(graph.withAdditionalStock(plan.missing()), supplied, amount);
        }
    }
}
