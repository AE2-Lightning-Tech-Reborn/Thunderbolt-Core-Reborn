package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlanningSessionDiagnosticsTest {
    @ParameterizedTest
    @ValueSource(longs = {1, 100, 1_000_000_000_000L})
    void cachedContendedGraphsKeepOptimizationAcrossAmountsAndRecipeOrders(long scale) {
        for (boolean reverse : new boolean[] {false, true}) {
            var graph = competingRoutes(scale, reverse);
            var session = new CraftPlannerV2.PlanningSession<String>();
            PlanningDiagnostics first = null;
            long elapsed = 0;
            int probes = 0;
            int remaining = Integer.MAX_VALUE;
            for (long amount : new long[] {scale, scale, Math.max(1, scale / 2), scale}) {
                var result = CraftPlannerV2.planDetailed(graph, "T", amount, session);
                var plan = result.plan();
                assertTrue(plan.feasible());
                assertFalse(plan.budgetExhausted());
                assertTrue(plan.missing().isEmpty());
                assertEquals(BigInteger.ONE, plan.executionCount());
                assertEquals(Map.of("raw", 2 * scale), plan.usedStock());
                assertTrue(plan.usedStock().get("raw") <= graph.stock("raw"));
                var replay = MaterialDagReplay.tryPlan(graph, plan.firings(), "T", amount);
                assertNotNull(replay);
                assertEquals(plan.usedStock(), replay.usedStock());
                assertEquals(CraftPlannerV2.planDetailed(graph, "T", amount).plan().firings(), plan.firings());
                var diagnostics = result.diagnostics();
                assertEquals(1, diagnostics.contendedOutputs());
                if (first == null) {
                    first = diagnostics;
                    assertEquals(1, diagnostics.compiledOrientations());
                    assertEquals(0, diagnostics.reusedCompilations());
                } else {
                    assertStructure(first, diagnostics);
                    assertCacheHit(diagnostics);
                }
                assertTrue(session.searchWorkBudget.remaining > 0);
                assertTrue(session.searchWorkBudget.remaining <= remaining);
                assertTrue(session.consumptionOptimizationProbes >= probes);
                assertTrue(session.consumptionOptimizationProbes <= FeasibleConsumptionOptimizer.MAX_PROBES);
                assertTrue(session.consumptionOptimizationNanos >= elapsed);
                remaining = session.searchWorkBudget.remaining;
                probes = session.consumptionOptimizationProbes;
                elapsed = session.consumptionOptimizationNanos;
            }
        }
    }

    @Test
    void ordinaryChainKeepsMetadataAndStillSkipsOptionalOptimization() {
        var graph = CraftGraph.<String>builder().stock("raw", 8)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1))).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        var first = CraftPlannerV2.planDetailed(graph, "T", 1, session).diagnostics();
        assertEquals(3, first.reachableItems());
        assertEquals(2, first.reachablePatterns());
        assertEquals(2, first.inputEdges());
        assertEquals(0, first.contendedOutputs());
        for (long amount : new long[] {4, 2, 1}) {
            var result = CraftPlannerV2.planDetailed(graph, "T", amount, session);
            assertTrue(result.plan().feasible());
            assertEquals(Map.of("raw", amount), result.plan().usedStock());
            assertStructure(first, result.diagnostics());
            assertCacheHit(result.diagnostics());
            assertEquals(0, result.diagnostics().consumptionOptimizationProbes());
        }
        assertEquals(0, session.consumptionOptimizationNanos);
        assertEquals(0, session.consumptionOptimizationProbes);
    }

    @Test
    void returnedContainerKeepsMetadataOnReuse() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.consumedReturning("full", 1, "empty")),
                        List.of(CraftOutput.of("scrap", 1)))
                .pattern("full", 1, List.of(CraftInput.of("empty", 1), CraftInput.of("powder", 1)))
                .stock("empty", 1).stock("powder", 16).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        session.refineMissing = false;
        var first = CraftPlannerV2.planDetailed(graph, "T", 1, session).diagnostics();
        for (long amount : new long[] {8, 3, 1}) {
            var result = CraftPlannerV2.planDetailed(graph, "T", amount, session);
            assertTrue(result.plan().feasible());
            assertStructure(first, result.diagnostics());
            assertEquals(0, result.diagnostics().compiledOrientations());
            assertTrue(result.diagnostics().reusedCompilations() > 0);
            assertEquals(0, result.diagnostics().graphCompileNanos());
            assertEquals(Map.of("empty", 1L, "powder", amount), result.plan().usedStock());
        }
    }

    @Test
    void seedOrderedGraphKeepsItsStructuralFlagOnReuse() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("X", 100), CraftInput.of("Y", 1)))
                .pattern("X", 1, List.of(CraftInput.returned("X", 1)))
                .pattern("X", 1, List.of(CraftInput.of("seed-raw", 1)))
                .pattern("Y", 1, List.of(CraftInput.of("y-raw", 1)))
                .pattern("Y", 1, List.of(CraftInput.of("y-dead", 1)))
                .stock("seed-raw", 1).stock("y-raw", 1).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        session.refineMissing = false;
        var first = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        assertTrue(first.plan().feasible());
        assertTrue(first.diagnostics().seedOrdered());
        var reused = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        assertEquals(first.plan().firings(), reused.plan().firings());
        assertStructure(first.diagnostics(), reused.diagnostics());
        assertEquals(0, reused.diagnostics().compiledOrientations());
        assertTrue(reused.diagnostics().reusedCompilations() > 0);
    }

    @Test
    void cutGraphKeepsItsStructuralCountOnReuse() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("D", 9)))
                .pattern("D", 9, List.of(CraftInput.of("B", 1)))
                .stock("D", 9).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        session.refineMissing = false;
        var first = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        assertTrue(first.plan().feasible());
        assertTrue(first.diagnostics().cycleCuts() > 0);
        var reused = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        assertEquals(first.plan().firings(), reused.plan().firings());
        assertStructure(first.diagnostics(), reused.diagnostics());
        assertEquals(0, reused.diagnostics().compiledOrientations());
        assertTrue(reused.diagnostics().reusedCompilations() > 0);
    }

    @Test
    void cachedMetadataDoesNotRefillExhaustedWork() {
        var graph = competingRoutes(1, false);
        var session = new CraftPlannerV2.PlanningSession<String>();
        var first = CraftPlannerV2.planDetailed(graph, "T", 1, session).diagnostics();
        int probes = session.consumptionOptimizationProbes;
        long elapsed = session.consumptionOptimizationNanos;
        assertTrue(session.searchWorkBudget.remaining > 0);
        assertTrue(session.searchWorkBudget.tryConsume(session.searchWorkBudget.remaining));
        var result = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        assertStructure(first, result.diagnostics());
        assertCacheHit(result.diagnostics());
        assertEquals(0, session.searchWorkBudget.remaining);
        assertEquals(probes, session.consumptionOptimizationProbes);
        assertEquals(elapsed, session.consumptionOptimizationNanos);
        assertEquals(0, result.diagnostics().consumptionOptimizationProbes());
        assertTrue(result.plan().feasible());
    }

    @Test
    void projectedStockCompilesItsOwnMetadataInsteadOfReusingParentBounds() {
        var graph = competingRoutes(1, false);
        var session = new CraftPlannerV2.PlanningSession<String>();
        var first = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        var limited = graph.withStockLimits(Map.of("raw", 1L));
        var probe = session.newProbe();
        var result = CraftPlannerV2.planDetailed(limited, "T", 1, probe);
        assertFalse(result.plan().feasible());
        assertTrue(result.plan().usedStock().getOrDefault("raw", 0L) <= 1);
        assertTrue(result.diagnostics().compiledOrientations() > 0);
        assertEquals(0, result.diagnostics().reusedCompilations());
        assertStructure(first.diagnostics(), result.diagnostics());
        assertThrows(IllegalArgumentException.class,
                () -> CraftPlannerV2.planDetailed(limited, "T", 1, session));
        assertEquals(Map.of("raw", 2L), CraftPlannerV2.planDetailed(graph, "T", 1, session).plan().usedStock());
    }

    private static CraftGraph<String> competingRoutes(long scale, boolean reverse) {
        var bulky = new CraftPattern<>("T", 10 * scale, List.of(CraftInput.of("raw", 10 * scale)), null);
        var compact = new CraftPattern<>("T", scale, List.of(CraftInput.of("raw", 2 * scale)), null);
        return CraftGraph.<String>builder().stock("raw", 100 * scale)
                .pattern(reverse ? compact : bulky).pattern(reverse ? bulky : compact).build();
    }

    private static void assertCacheHit(PlanningDiagnostics diagnostics) {
        assertEquals(0, diagnostics.compiledOrientations());
        assertEquals(1, diagnostics.reusedCompilations());
        assertEquals(0, diagnostics.graphCompileNanos());
    }

    private static void assertStructure(PlanningDiagnostics expected, PlanningDiagnostics actual) {
        assertEquals(expected.reachableWorkEstimate(), actual.reachableWorkEstimate());
        assertEquals(expected.reachableItems(), actual.reachableItems());
        assertEquals(expected.reachablePatterns(), actual.reachablePatterns());
        assertEquals(expected.inputEdges(), actual.inputEdges());
        assertEquals(expected.contendedOutputs(), actual.contendedOutputs());
        assertEquals(expected.cycleCuts(), actual.cycleCuts());
        assertEquals(expected.seedOrdered(), actual.seedOrdered());
    }
}
