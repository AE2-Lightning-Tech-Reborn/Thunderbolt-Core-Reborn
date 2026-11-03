package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatDirectWitnessTest {
    @BeforeAll static void nativeRuntimeLoads() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
    }

    @Test void exhaustedNativeCallBudgetKeepsTheCheapestCertifiedDirectShortage() {
        var graph = conversionCycle(false);
        var session = new CpSatRankedFlowSolver.PlanningSession(0, 4096, 250_000_000L);

        var result = CpSatRankedFlowSolver.solve(graph, "T", 1, session);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("B", 1L), result.plan().missing());
        assertEquals(BigInteger.ONE, result.plan().executionCount());
        assertEquals(1, result.plan().firings().size());
        assertEquals("target", result.plan().firings().keySet().iterator().next().source());
        assertTrue(result.plan().budgetExhausted());
        assertTrue(session.incomplete());
        assertEquals(0, session.refinementCalls());
        assertEquals(0, session.blockSolves());
        assertTrue(CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "T", 1)
                .plan().feasible());
    }

    @Test void directAlternativesUseTheWholeObjectiveInsteadOfTheFirstRegisteredRecipe() {
        var graph = conversionCycle(true);
        var session = new CpSatRankedFlowSolver.PlanningSession(0, 4096, 250_000_000L);

        var plan = CpSatRankedFlowSolver.solve(graph, "T", 1, session).plan();

        assertNotNull(plan);
        assertEquals(Map.of("B", 1L), plan.missing());
        assertEquals(BigInteger.ONE, plan.executionCount());
        assertEquals("target", plan.firings().keySet().iterator().next().source());
        assertEquals(0, session.refinementCalls());
    }

    @Test void directCertificationDoesNotBypassAnExhaustedSharedTimeBudget() {
        var session = new CpSatRankedFlowSolver.PlanningSession(0, 4096, 0);

        var result = CpSatRankedFlowSolver.solve(conversionCycle(false), "T", 1, session);

        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().budgetExhausted());
        assertEquals(0, session.improvements());
        assertEquals(0, session.refinementCalls());
        assertDoesNotThrow(PlanningCancellation::check);
    }

    private static CraftGraph<String> conversionCycle(boolean worseDirectRouteFirst) {
        var builder = CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("B", 2, List.of(CraftInput.of("A", 2)), "2A-to-2B"))
                .pattern(new CraftPattern<>("A", 3, List.of(CraftInput.of("B", 3)), "3B-to-3A"));
        if (worseDirectRouteFirst) builder.pattern(new CraftPattern<>("T", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("B", 2)), "worse"));
        return builder.pattern(new CraftPattern<>("T", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), "target")).stock("A", 2).build();
    }
}
