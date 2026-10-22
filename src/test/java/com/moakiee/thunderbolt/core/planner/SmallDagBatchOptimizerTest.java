package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SmallDagBatchOptimizerTest {
    @Test
    void sharedIntermediateBatchReducesExecutionsAndRemainsExecutable() {
        var graph = graph(1);
        var initial = initial(graph);
        assertEquals(BigInteger.valueOf(5), initial.executionCount());
        var result = SmallDagBatchOptimizer.solve(graph, "T", 1, initial, work -> true);
        assertNotNull(result);
        assertEquals(BigInteger.valueOf(4), result.executionCount());
        assertEquals(Map.of("raw", 1L), result.usedStock());
        assertTrue(MaterialDagReplay.hasCertificate(graph, result, "T", 1));
        var integrated = CraftPlannerV2.planDetailed(graph, "T", 1).plan();
        assertTrue(integrated.feasible());
        assertEquals(BigInteger.valueOf(4), integrated.executionCount());
        var replayed = MaterialDagReplay.tryPlan(graph, integrated.firings(), "T", 1);
        assertNotNull(replayed);
        assertEquals(Map.of("raw", 1L), integrated.usedStock());
        assertEquals(integrated.usedStock(), replayed.usedStock());
    }

    @Test
    void shorterButUnfundedBatchCannotReplaceTheInitialWitness() {
        var graph = graph(3);
        var initial = initial(graph);
        assertNull(SmallDagBatchOptimizer.solve(graph, "T", 1, initial, work -> true));
        assertTrue(MaterialDagReplay.hasCertificate(graph, initial, "T", 1));
        assertEquals(Map.of("raw", 2L), initial.usedStock());
    }

    @Test
    void exhaustedBudgetKeepsTheWitnessAndDoesNotPoisonTheNextSearch() {
        var graph = graph(1);
        var initial = initial(graph);
        var calls = new AtomicInteger();
        assertNull(SmallDagBatchOptimizer.solve(graph, "T", 1, initial, work -> {
            calls.incrementAndGet();
            return false;
        }));
        assertEquals(1, calls.get());
        assertTrue(MaterialDagReplay.hasCertificate(graph, initial, "T", 1));
        assertNotNull(SmallDagBatchOptimizer.solve(graph, "T", 1, initial, work -> true));
    }

    @Test
    void externalCancellationEscapesTheOptionalSearch() {
        var graph = graph(1);
        var initial = initial(graph);
        try {
            assertThrows(CancellationException.class, () ->
                    SmallDagBatchOptimizer.solve(graph, "T", 1, initial, work -> {
                        Thread.currentThread().interrupt();
                        return true;
                    }));
        } finally {
            Thread.interrupted();
        }
        assertTrue(MaterialDagReplay.hasCertificate(graph, initial, "T", 1));
        assertNotNull(SmallDagBatchOptimizer.solve(graph, "T", 1, initial, work -> true));
    }

    private static CraftGraph<String> graph(long bulkCost) {
        return CraftGraph.<String>builder().stock("raw", 2)
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)))
                .pattern("A", 1, List.of(CraftInput.of("M", 1)))
                .pattern("B", 1, List.of(CraftInput.of("M", 1)))
                .pattern("M", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("M", 2, List.of(CraftInput.of("raw", bulkCost))).build();
    }

    private static CraftPlan<String> initial(CraftGraph<String> graph) {
        var result = MaterialDagReplay.tryPlan(graph, Map.of(
                graph.patternsFor("T").get(0), 1L,
                graph.patternsFor("A").get(0), 1L,
                graph.patternsFor("B").get(0), 1L,
                graph.patternsFor("M").get(0), 2L), "T", 1);
        assertNotNull(result);
        return result;
    }
}
