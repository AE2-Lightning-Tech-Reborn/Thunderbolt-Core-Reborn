package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
