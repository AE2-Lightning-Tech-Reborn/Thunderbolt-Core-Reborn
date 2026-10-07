package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CpSatSeedPortfolioRegressionTest {
    @Test void freshDefaultSessionsReplanTheSharedSeedSupplement() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        for (int trial = 0; trial < 8; trial++) {
            var builder = CraftGraph.<String>builder().stock("S", 2).stock("R", 6);
            for (String suffix : List.of("1", "2")) {
                builder.pattern("A" + suffix, 1, List.of(CraftInput.of("S", 1)))
                        .pattern("B" + suffix, 1, List.of(CraftInput.of("A" + suffix, 1), CraftInput.of("R", 1)))
                        .pattern("P" + suffix, 1, List.of(CraftInput.of("B" + suffix, 1)),
                                List.of(CraftOutput.of("A" + suffix, 1)));
            }
            builder.pattern("T", 1, List.of(CraftInput.of("P1", 1), CraftInput.of("P2", 1)));
            var result = CpSatRankedFlowSolver.solve(builder.build(), "T", 3);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertTrue(result.plan().feasible(), () -> result.plan().missing().toString());
            assertEquals(Map.of("S", 2L, "R", 6L), result.plan().usedStock());
        }
    }

    @Test void compactCatalogRetainsRoundWitnessWithoutDiscovery() {
        long[][] pre = {{1, 0, 0}, {0, 1, 1}};
        long[][] post = {{0, 1, 0}, {1, 0, 0}};
        var compact = PetriBlockCatalog.build(
                com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge.SparseLongMatrix.fromDense(pre),
                com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge.SparseLongMatrix.fromDense(post),
                new int[]{0, 0, 1}, new int[]{1, 0}, java.util.Set.of(0), List.of(), false);
        assertFalse(compact.isEmpty());
        assertTrue(compact.stream().anyMatch(block -> block.wire()[1] == 1 && block.wire()[2] == 1));
    }

    @ParameterizedTest
    @CsvSource({"2,3", "4,3", "8,3", "2,1000000000000", "4,1000000000000", "8,1000000000000"})
    void sharedSeedCyclesHaveACompressedWitnessBeforeNativeBlockRefinement(int cycles, long quantity) {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        var builder = CraftGraph.<String>builder().stock("S", cycles).stock("R", cycles * quantity);
        var targetInputs = new java.util.ArrayList<CraftInput<String>>();
        for (int i = 0; i < cycles; i++) {
            builder.pattern("A" + i, 1, List.of(CraftInput.of("S", 1)))
                    .pattern("B" + i, 1, List.of(CraftInput.of("A" + i, 1), CraftInput.of("R", 1)))
                    .pattern("P" + i, 1, List.of(CraftInput.of("B" + i, 1)), List.of(CraftOutput.of("A" + i, 1)));
            targetInputs.add(CraftInput.of("P" + i, 1));
        }
        var graph = builder.pattern("T", 1, targetInputs).build();
        var session = new CpSatRankedFlowSolver.PlanningSession();
        var result = CpSatRankedFlowSolver.solve(graph, "T", quantity, session);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().feasible(), () -> "missing=" + result.plan().missing());
        assertEquals(Map.of("S", (long) cycles, "R", cycles * quantity), result.plan().usedStock());
        for (int i = 0; i < cycles; i++) {
            assertEquals(1L, result.plan().firings().get(graph.patternsFor("A" + i).get(0)));
            assertEquals(quantity, result.plan().firings().get(graph.patternsFor("B" + i).get(0)));
            assertEquals(quantity, result.plan().firings().get(graph.patternsFor("P" + i).get(0)));
        }
        assertEquals(quantity, result.plan().firings().get(graph.patternsFor("T").get(0)));
        assertEquals(1, session.compressedSeedAttempts());
        assertEquals(0, session.blockSolves(), "independent rounds need no native scheduling model");
        assertTrue(result.plan().budgetExhausted(), "a seed witness does not prove the global objective optimum");
    }

    @Test void missingSharedSeedIsNotDuplicatedByTheCompressedWitness() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        int cycles = 8;
        long quantity = 1_000_000_000_000L;
        var builder = CraftGraph.<String>builder().stock("S", cycles - 1).stock("R", cycles * quantity);
        var inputs = new java.util.ArrayList<CraftInput<String>>();
        for (int i = 0; i < cycles; i++) {
            builder.pattern("A" + i, 1, List.of(CraftInput.of("S", 1)))
                    .pattern("B" + i, 1, List.of(CraftInput.of("A" + i, 1), CraftInput.of("R", 1)))
                    .pattern("P" + i, 1, List.of(CraftInput.of("B" + i, 1)), List.of(CraftOutput.of("A" + i, 1)));
            inputs.add(CraftInput.of("P" + i, 1));
        }
        var graph = builder.pattern("T", 1, inputs).build();
        var result = CpSatRankedFlowSolver.solve(graph, "T", quantity);
        assertFalse(result.plan().feasible());
        assertEquals(Map.of("S", 1L), result.plan().missing());
        assertEquals((long) cycles - 1, result.plan().usedStock().get("S"));
        assertTrue(CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()),
                "T", quantity).plan().feasible());
    }

    @Test void compressedSeedWitnessCannotBypassAnExhaustedCalculationBudget() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        var builder = CraftGraph.<String>builder().stock("S", 4).stock("R", 12);
        var inputs = new java.util.ArrayList<CraftInput<String>>();
        for (int i = 0; i < 4; i++) {
            builder.pattern("A" + i, 1, List.of(CraftInput.of("S", 1)))
                    .pattern("B" + i, 1, List.of(CraftInput.of("A" + i, 1), CraftInput.of("R", 1)))
                    .pattern("P" + i, 1, List.of(CraftInput.of("B" + i, 1)), List.of(CraftOutput.of("A" + i, 1)));
            inputs.add(CraftInput.of("P" + i, 1));
        }
        var graph = builder.pattern("T", 1, inputs).build();
        for (var session : List.of(new CpSatRankedFlowSolver.PlanningSession(0, 4096, 250_000_000L),
                new CpSatRankedFlowSolver.PlanningSession(16, 4096, 0),
                new CpSatRankedFlowSolver.PlanningSession(16, 0, 250_000_000L))) {
            var result = CpSatRankedFlowSolver.solve(graph, "T", 3, session);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertTrue(result.plan().budgetExhausted());
            assertEquals(0, session.compressedSeedAttempts());
        }
    }
}
