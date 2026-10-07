package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlannerBoundedPortfolioRegressionTest {
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

    @Test void largeStockProjectionsRemainIndependentAndReadOnly() {
        var builder = CraftGraph.<String>builder();
        for (int i = 0; i < 256; i++) builder.stock("raw" + i, i + 1);
        var graph = builder.build();
        var added = graph.withAdditionalStock(Map.of("raw0", 7L));
        assertEquals(1L, graph.stock("raw0"));
        assertEquals(8L, added.stock("raw0"));
        var limited = added.withStockLimits(Map.of("raw0", 3L));
        assertEquals(3L, limited.stock("raw0"));
        assertEquals(8L, added.stock("raw0"));
        var p = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw0", 1)), null);
        var plan = MaterialDagReplay.tryPlan(added, Map.of(p, 1L), "T", 1);
        assertNotNull(plan);
        assertThrows(UnsupportedOperationException.class, () -> plan.usedStock().put("raw0", 2L));
        assertThrows(UnsupportedOperationException.class, () -> plan.firings().put(p, 2L));
    }

    @Test void completedSupportIsReplayedBeforeFurtherPermutations() {
        var graph = CraftGraph.<String>builder().stock("raw", 4)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).build();
        assertTrue(MaterialDagOrders.compile(graph, "T").size() > 1);
        var calls = new AtomicInteger();
        var completed = MaterialDagOrders.compile(graph, "T",
                BoundedIntegerLinearSolver.WorkBudget.unlimited(), candidate -> {
                    calls.incrementAndGet();
                    assertTrue(CraftPlannerV2.plan(candidate.graph(), "T", 1, 1, 1).feasible());
                    return true;
                });
        assertEquals(1, calls.get());
        assertEquals(1, completed.size());
    }

    @Test void refreshStopsAfterAcceptedSupportAndDoesNotMutateTemplates() {
        var graph = CraftGraph.<String>builder()
                .pattern("P", 1, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("side", 1)))
                .pattern("T", 1, List.of(CraftInput.of("P", 1))).build();
        var templates = MaterialDagOrders.compile(graph, "T");
        assertTrue(templates.size() > 1);
        var completed = MaterialDagOrders.withAdditionalStock(templates, Map.of("raw", 1L), "T",
                BoundedIntegerLinearSolver.WorkBudget.unlimited(), candidate -> candidate.maySupply(1));
        assertEquals(1, completed.size());
        assertEquals(1, completed.get(0).graph().stock("raw"));
        assertTrue(templates.stream().allMatch(candidate -> candidate.graph().stock("raw") == 0));
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
