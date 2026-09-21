package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatExecutionBlocksTest {
    @BeforeAll static void loadNative() { assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath()); }

    static CraftGraph<String> seedFromStock(long q) {
        return CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("A", 1, List.of(CraftInput.of("S", 1)), "seed"))
                .pattern(new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1), CraftInput.of("R", 1)), "ab"))
                .pattern(new CraftPattern<>("T", 1, List.of(CraftInput.of("B", 1)), List.of(CraftOutput.of("A", 1)), "bt"))
                .stock("S", 1).stock("R", q).build();
    }

    @Test void jointlyChoosesSeedProductionAndTrillionRepetitions() {
        long q = 1_000_000_000_000L;
        var graph = seedFromStock(q);
        var session = new CpSatRankedFlowSolver.PlanningSession(16, 4096, 1_000_000_000L);
        var result = assertTimeoutPreemptively(Duration.ofSeconds(4),
                () -> CpSatRankedFlowSolver.solve(graph, "T", q, session));
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing()
                + " runtime=" + CpSatRuntime.loadFailure());
        assertEquals(1, session.blockSolves());
        assertEquals(Map.of("S", 1L, "R", q), result.plan().usedStock());
        assertEquals(1L, result.plan().firings().get(graph.patternsFor("A").get(0)));
        assertEquals(q, result.plan().firings().get(graph.patternsFor("B").get(0)));
        assertEquals(q, result.plan().firings().get(graph.patternsFor("T").get(0)));
    }

    @Test void closedCycleCanReplenishAnInternalSeedAndRefillReallyExecutes() {
        var graph = CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1), CraftInput.of("R", 1)), "ab"))
                .pattern(new CraftPattern<>("T", 1, List.of(CraftInput.of("B", 1)), List.of(CraftOutput.of("A", 1)), "bt"))
                .stock("R", 10).build();
        var result = CpSatRankedFlowSolver.solve(graph, "T", 10);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertEquals(1L, result.plan().missing().getOrDefault("A", 0L)
                + result.plan().missing().getOrDefault("B", 0L));
        assertFalse(result.plan().missing().containsKey("T"));
        assertTrue(CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "T", 10).plan().feasible());
    }

    @Test void anInsufficientBlockDomainRetainsTheCertifiedReplenishmentWithoutLearningACut() {
        // The unrestricted master admits this quantity, but the optional block variables reserve
        // extra int64 row space and cannot cover it in four stages. That is no unreachability proof.
        long q = 100_000_000_000_000_000L;
        var graph = seedFromStock(q);
        var session = new CpSatRankedFlowSolver.PlanningSession(1, 0, 1_000_000_000L);
        var result = CpSatRankedFlowSolver.solve(graph, "T", q, session);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertEquals(1, session.blockSolves());
        assertEquals(0, session.learnedCuts());
        assertTrue(result.plan().budgetExhausted());
        assertEquals(1L, result.plan().missing().getOrDefault("A", 0L)
                + result.plan().missing().getOrDefault("B", 0L));
        var replenished = CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "T", q);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, replenished.status());
        assertTrue(replenished.plan().feasible());
    }

    @Test void reverseSeedCanProduceTheFirstOutputWithoutConsumingFuel() {
        var graph = CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1), CraftInput.of("R", 1)), "ab"))
                .pattern(new CraftPattern<>("T", 1, List.of(CraftInput.of("B", 1)), List.of(CraftOutput.of("A", 1)), "bt"))
                .stock("B", 1).stock("R", 9).build();
        var plan = CpSatRankedFlowSolver.solve(graph, "T", 10).plan();
        assertNotNull(plan);
        assertTrue(plan.feasible(), () -> "missing=" + plan.missing());
        assertEquals(9L, plan.firings().get(graph.patternsFor("B").get(0)));
    }

    @Test void selectedCutIsALeafButItsAcyclicDownstreamIntermediateIsNot() {
        var graph = CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("B", 2, List.of(CraftInput.of("A", 1)), "ab"))
                .pattern(new CraftPattern<>("A", 2, List.of(CraftInput.of("B", 1)), "ba"))
                .pattern(new CraftPattern<>("C", 1, List.of(CraftInput.of("B", 1)), "bc"))
                .pattern(new CraftPattern<>("T", 1, List.of(CraftInput.of("C", 1)), "ct")).build();
        var result = CpSatRankedFlowSolver.solve(graph, "T", 1);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertEquals(Map.of("A", 1L), result.plan().missing());
        assertTrue(CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "T", 1).plan().feasible());
    }

    @Test void aGainfulSelfLoopRequiresACutInsteadOfBecomingAnExecutableMacro() {
        var gain = new CraftPattern<>("A", 2, List.of(CraftInput.of("A", 1)), "gain");
        var graph = CraftGraph.<String>builder().pattern(gain)
                .pattern(new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1)), "target")).stock("A", 1).build();
        var result = CpSatRankedFlowSolver.solve(graph, "T", 2);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertEquals(Map.of("A", 1L), result.plan().missing());
        assertFalse(result.plan().firings().containsKey(gain));
    }

    @Test void anUnprovenByproductReturnCanBeCutAndFundedExternally() {
        var recipe = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("R", 1)),
                List.of(CraftOutput.of("A", 2)), "gainful-return");
        var graph = CraftGraph.<String>builder().pattern(recipe).stock("A", 1).stock("R", 3).build();
        var result = CpSatRankedFlowSolver.solve(graph, "T", 3);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertEquals(Map.of("A", 2L), result.plan().missing());
        assertEquals(3L, result.plan().firings().get(recipe));
        assertTrue(CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "T", 3).plan().feasible());
    }

    @Test void independentCyclesShareTheSamePhysicalSeedMaterial() {
        var builder = CraftGraph.<String>builder().stock("S", 1).stock("R", 6);
        for (String suffix : List.of("1", "2")) {
            builder.pattern(new CraftPattern<>("A"+suffix, 1, List.of(CraftInput.of("S", 1)), "seed"+suffix))
                    .pattern(new CraftPattern<>("B"+suffix, 1, List.of(CraftInput.of("A"+suffix, 1), CraftInput.of("R", 1)), "ab"+suffix))
                    .pattern(new CraftPattern<>("P"+suffix, 1, List.of(CraftInput.of("B"+suffix, 1)),
                            List.of(CraftOutput.of("A"+suffix, 1)), "bp"+suffix));
        }
        builder.pattern(new CraftPattern<>("T", 1, List.of(CraftInput.of("P1", 1), CraftInput.of("P2", 1)), "target"));
        var graph = builder.build();
        var session = new CpSatRankedFlowSolver.PlanningSession(16, 4096, 1_000_000_000L);
        var result = CpSatRankedFlowSolver.solve(graph, "T", 3, session);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertEquals(Map.of("S", 1L), result.plan().missing(), () -> "runtime=" + CpSatRuntime.loadFailure());
        assertEquals(1L, result.plan().usedStock().get("S"));
        assertEquals(6L, result.plan().usedStock().get("R"));
        assertTrue(CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "T", 3).plan().feasible());
    }
}
