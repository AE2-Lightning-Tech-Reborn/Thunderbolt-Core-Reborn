package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class NonGrowingCycleReachabilityTest {
    @Test
    void conservativeBalanceCanBeFeasibleWhileEveryTransitionIsDeadlocked() {
        // Every transition preserves A+B+C. A positive non-growing potential therefore exists.
        var ab = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 2)),
                List.of(new CraftOutput<>("C", 1)), "2A-to-B-C");
        var bc = new CraftPattern<>("C", 1, List.of(CraftInput.of("B", 2)),
                List.of(new CraftOutput<>("A", 1)), "2B-to-C-A");
        var ca = new CraftPattern<>("A", 1, List.of(CraftInput.of("C", 2)),
                List.of(new CraftOutput<>("B", 1)), "2C-to-A-B");
        var graph = CraftGraph.<String>builder().pattern(ab).pattern(bc).pattern(ca)
                .stock("A", 1).stock("B", 1).build();
        var analysis = ConservativeFeedbackAnalysis.analyzeAll(List.of("A", "B", "C"),
                Map.of("A", List.of(ca), "B", List.of(ab), "C", List.of(bc)));
        assertEquals(1, analysis.fallbacks().size());
        assertEquals(Map.of("A", 1L, "B", 1L, "C", 1L), analysis.fallbacks().get(0).placePotential());
        // Algebraically firing ab and bc once gives (A=0,B=0,C=2), enough for C=1.
        var finalBalance = new HashMap<>(Map.of("A", 1L, "B", 1L, "C", 0L));
        for (var pattern : List.of(ab, bc)) {
            for (var input : pattern.inputs()) finalBalance.merge(input.key(), -input.amount(), Long::sum);
            finalBalance.merge(pattern.output(), pattern.outputAmount(), Long::sum);
            for (var output : pattern.byproducts()) finalBalance.merge(output.key(), output.amount(), Long::sum);
        }
        assertEquals(Map.of("A", 0L, "B", 0L, "C", 2L), finalBalance);
        // But no transition can fire first from (A=1,B=1,C=0).
        for (var pattern : List.of(ab, bc, ca)) {
            assertTrue(pattern.inputs().stream().anyMatch(in -> graph.stock(in.key()) < in.amount()));
        }
        var plan = CraftPlannerV2.plan(graph, "C", 1);
        assertFalse(plan.feasible());
        assertFalse(plan.missing().isEmpty());
    }
}
