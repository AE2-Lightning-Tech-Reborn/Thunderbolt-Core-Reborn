package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrdinaryBatchOptimizerTest {
    @Test
    void impossibleFiringReductionDoesNotStarveAnEqualExecutionThreeSourceSaving() {
        // demand, stock, expected executions, expected draw, then output/input pairs.
        for (long[] sample : new long[][] {
                {12, 12, 3, 11, 3, 1, 4, 4, 5, 6, 1, 1000, 1, 1000, 1, 1000},
                {12, 12, 3, 11, 1, 1000, 3, 1, 4, 4, 5, 6, 1, 1000},
                {27, 62, 3, 13, 2, 1, 12, 5, 2, 9, 8, 10, 13, 7, 9, 12, 6, 11, 5, 5}}) {
            var builder = CraftGraph.<String>builder().stock("raw", sample[1]);
            for (int i = 4; i < sample.length; i += 2)
                builder.pattern("T", sample[i], List.of(CraftInput.of("raw", sample[i + 1])));
            var graph = builder.build();
            var result = CraftPlannerV2.plan(graph, "T", sample[0]);
            assertTrue(result.feasible());
            assertEquals(sample[2], result.firings().values().stream().mapToLong(n -> n).sum());
            assertEquals(Map.of("raw", sample[3]), result.usedStock());
            assertEquals(3, result.firings().size());
        }
    }

    @Test
    void duplicateRegistrationDoesNotHideTheSecondBatchSizeOrConsumeRouteSlots() {
        for (int repetitions : new int[] {1, 2, 16, 32}) {
            var first = new CraftPattern<>("T", 2, List.of(CraftInput.of("raw", 1)), null);
            var second = new CraftPattern<>("T", 4, List.of(CraftInput.of("raw", 3)), null);
            var builder = CraftGraph.<String>builder().stock("raw", 4);
            for (int i = 0; i < repetitions; i++) builder.pattern(first);
            var graph = builder.pattern(second).build();
            var initial = MaterialDagReplay.tryPlan(graph, Map.of(first, 3L), "T", 5);
            assertNotNull(initial);
            var candidate = OrdinaryBatchOptimizer.tryImprove(graph, "T", 5, initial,
                    List.of("T", "raw"), work -> true, () -> true);
            assertNotNull(candidate, "repetitions=" + repetitions);
            assertEquals(Map.of(first, 1L, second, 1L), candidate.firings());
            var integrated = CraftPlannerV2.plan(graph, "T", 5);
            assertTrue(integrated.feasible());
            assertEquals(Map.of(first, 1L, second, 1L), integrated.firings());
            assertEquals(Map.of("raw", 4L), integrated.usedStock());
        }
    }

    @Test
    void distinctPatternObjectsSharingTheirSourceRemainSeparateChoices() {
        var source = new Object();
        var first = new CraftPattern<>("T", 2, List.of(CraftInput.of("raw", 1)), source);
        var second = new CraftPattern<>("T", 4, List.of(CraftInput.of("raw", 3)), source);
        var builder = CraftGraph.<String>builder().stock("raw", 4);
        for (int i = 0; i < 16; i++) builder.pattern(first);
        var graph = builder.pattern(second).build();
        var initial = MaterialDagReplay.tryPlan(graph, Map.of(first, 3L), "T", 5);
        assertNotNull(initial);
        var candidate = OrdinaryBatchOptimizer.tryImprove(graph, "T", 5, initial,
                List.of("T", "raw"), work -> true, () -> true);
        assertNotNull(candidate);
        assertEquals(Map.of(first, 1L, second, 1L), candidate.firings());
        var integrated = CraftPlannerV2.plan(graph, "T", 5);
        assertTrue(integrated.feasible());
        assertEquals(Map.of(first, 1L, second, 1L), integrated.firings());
        assertEquals(Map.of("raw", 4L), integrated.usedStock());
    }

    @Test
    void publicPlannerDrawsOnlyTheIntermediateStockNotReplacedByBatchSurplus() {
        var graph = surplusStockGraph();
        var result = CraftPlannerV2.plan(graph, "T", 1);
        assertTrue(result.feasible());
        assertEquals(Map.of(graph.patternsFor("A").get(0), 1L,
                graph.patternsFor("T").get(1), 1L), result.firings());
        assertEquals(Map.of("raw", 2L, "A", 1L), result.usedStock());
    }

    @Test
    void surplusStockNormalizationKeepsFiringsAndDoesNotSpendAnotherProbeOnceNormalized() {
        var graph = surplusStockGraph();
        var incumbent = surplusStockIncumbent(graph);
        var original = Map.copyOf(incumbent.firings());
        assertNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 1, incumbent, List.of("T", "A", "raw"),
                work -> true, () -> { throw new AssertionError("route search normalized stock before finishing"); }));
        var probes = new AtomicInteger();
        var result = OrdinaryBatchOptimizer.normalizeStock(graph, "T", 1, incumbent,
                work -> true, () -> { probes.incrementAndGet(); return true; });
        assertNotNull(result);
        assertEquals(1, probes.get());
        assertEquals(original, result.firings());
        assertEquals(Map.of("raw", 2L, "A", 1L), result.usedStock());
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
        assertNull(OrdinaryBatchOptimizer.normalizeStock(graph, "T", 1, result,
                work -> true, () -> { throw new AssertionError("normalized stock entered certification"); }));
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("raw", 2L, "A", 2L), incumbent.usedStock());
    }

    @Test
    void surplusStockNormalizationHonorsWorkAndProbeBudgetsWithoutChangingTheWitness() {
        var graph = surplusStockGraph();
        var incumbent = surplusStockIncumbent(graph);
        var original = Map.copyOf(incumbent.firings());
        var fullWork = new AtomicInteger();
        assertNotNull(OrdinaryBatchOptimizer.normalizeStock(graph, "T", 1, incumbent,
                work -> { assertTrue(work > 0); fullWork.addAndGet(work); return true; }, () -> true));
        for (int limit = 0; limit < fullWork.get(); limit++) {
            int[] remaining = {limit};
            assertNull(OrdinaryBatchOptimizer.normalizeStock(graph, "T", 1, incumbent, work -> {
                assertTrue(work > 0);
                if (work > remaining[0]) return false;
                remaining[0] -= work;
                return true;
            }, () -> true), "insufficient normalization work=" + limit);
            assertTrue(remaining[0] >= 0);
        }
        var deniedProbes = new AtomicInteger();
        assertNull(OrdinaryBatchOptimizer.normalizeStock(graph, "T", 1, incumbent,
                work -> true, () -> { deniedProbes.incrementAndGet(); return false; }));
        assertEquals(1, deniedProbes.get());
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("raw", 2L, "A", 2L), incumbent.usedStock());
    }

    @Test
    void cancellationDuringSurplusStockCertificationPreservesTheOriginalWitness() {
        var graph = surplusStockGraph();
        var incumbent = surplusStockIncumbent(graph);
        var original = Map.copyOf(incumbent.firings());
        var probes = new AtomicInteger();
        try {
            assertThrows(CancellationException.class, () -> OrdinaryBatchOptimizer.normalizeStock(
                    graph, "T", 1, incumbent, work -> true, () -> {
                        probes.incrementAndGet();
                        Thread.currentThread().interrupt();
                        return true;
                    }));
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, probes.get());
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> OrdinaryBatchOptimizer.normalizeStock(
                    graph, "T", 1, incumbent, work -> true, () -> true));
        }
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("raw", 2L, "A", 2L), incumbent.usedStock());
    }

    @Test
    void integratedThreeSourceMixesImproveBothExecutionAndStockObjectives() {
        // demand, stock, three output/input pairs, expected executions, expected stock
        for (long[] sample : new long[][] {
                {12, 100, 3, 1, 4, 4, 5, 6, 3, 11},
                {13, 15, 1, 1, 7, 8, 4, 5, 4, 15},
                {16, 100, 3, 1, 7, 8, 6, 7, 3, 16}}) {
            var graph = CraftGraph.<String>builder().stock("raw", sample[1])
                    .pattern("T", sample[2], List.of(CraftInput.of("raw", sample[3])))
                    .pattern("T", sample[4], List.of(CraftInput.of("raw", sample[5])))
                    .pattern("T", sample[6], List.of(CraftInput.of("raw", sample[7]))).build();
            var result = CraftPlannerV2.plan(graph, "T", sample[0]);
            assertTrue(result.feasible());
            assertEquals(sample[8], result.firings().values().stream().mapToLong(n -> n).sum());
            assertEquals(sample[9], result.usedStock().get("raw"));
            assertTrue(result.firings().values().stream().allMatch(n -> n > 0));
            assertEquals(3, result.firings().size());
        }
    }

    @Test
    void integratedMixMayGrowUpstreamWhenTheCompletePlanUsesFewerFirings() {
        var graph = CraftGraph.<String>builder().stock("raw", 10)
                .pattern("A", 2, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 3, List.of(CraftInput.of("A", 4))).build();
        var result = CraftPlannerV2.plan(graph, "T", 4);
        assertTrue(result.feasible());
        assertEquals(5, result.firings().values().stream().mapToLong(n -> n).sum());
        assertEquals(3, result.usedStock().get("raw"));
        assertEquals(3, result.firings().get(graph.patternsFor("A").get(0)));
    }

    @Test
    void equalTargetFiringsMayUseExistingIntermediateStockToTrimUpstreamFirings() {
        var graph = CraftGraph.<String>builder().stock("A", 2).stock("raw", 31)
                .pattern("T", 2, List.of(CraftInput.of("A", 2)))
                .pattern("T", 2, List.of(CraftInput.of("B", 3)))
                .pattern("A", 2, List.of(CraftInput.of("I", 3)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 2)))
                .pattern("B", 3, List.of(CraftInput.of("raw", 1)))
                .pattern("I", 3, List.of(CraftInput.of("raw", 1))).build();
        var targetRoutes = graph.patternsFor("T");
        var b = graph.patternsFor("B").get(0);
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(targetRoutes.get(1), 3L, b, 3L), "T", 5);
        assertNotNull(incumbent);
        var result = OrdinaryBatchOptimizer.tryImprove(graph, "T", 5, incumbent,
                List.of("T", "A", "B", "I", "raw"), work -> true, () -> true);
        assertNotNull(result);
        assertEquals(Map.of(targetRoutes.get(0), 1L, targetRoutes.get(1), 2L, b, 2L), result.firings());
        assertEquals(Map.of("A", 2L, "raw", 2L), result.usedStock());
        assertEquals(5L, result.firings().values().stream().mapToLong(Long::longValue).sum());
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
        assertEquals(Map.of(targetRoutes.get(1), 3L, b, 3L), incumbent.firings());
        var integrated = CraftPlannerV2.plan(graph, "T", 5);
        assertTrue(integrated.feasible());
        assertEquals(5L, integrated.firings().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of("A", 2L, "raw", 2L), integrated.usedStock());
    }

    @Test
    void fewerFiringsCanUseAdditionalAvailableOrdinaryStock() {
        var graph = CraftGraph.<String>builder().stock("raw", 4)
                .pattern("T", 2, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 4, List.of(CraftInput.of("raw", 3))).build();
        var first = graph.patternsFor("T").get(0);
        var second = graph.patternsFor("T").get(1);
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(first, 3L), "T", 5);
        assertNotNull(incumbent);
        var result = OrdinaryBatchOptimizer.tryImprove(graph, "T", 5, incumbent, List.of("T", "raw"),
                work -> true, () -> true);
        assertNotNull(result);
        assertEquals(Map.of(first, 1L, second, 1L), result.firings());
        assertEquals(Map.of("raw", 4L), result.usedStock());
        assertEquals(2, CraftPlannerV2.plan(graph, "T", 5).firings().values().stream().mapToLong(n -> n).sum());
    }

    @Test
    void extraInventoryStillCannotExchangeMaterialsAtEqualExecutionCount() {
        var graph = CraftGraph.<String>builder().stock("iron", 8).stock("diamond", 1)
                .pattern("T", 1, List.of(CraftInput.of("iron", 8)))
                .pattern("T", 1, List.of(CraftInput.of("diamond", 1))).build();
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(graph.patternsFor("T").get(0), 1L), "T", 1);
        assertNotNull(incumbent);
        assertNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 1, incumbent, List.of("T", "iron", "diamond"),
                work -> true, () -> true));
        assertNull(OrdinaryBatchOptimizer.certify(graph, Map.of(graph.patternsFor("T").get(1), 1L),
                incumbent, "T", 1, Map.of("iron", 8L, "diamond", 1L), work -> true, () -> true));
    }

    @Test
    void fewerFiringsRespectEveryMaterialInTheInventorySnapshot() {
        for (long[] stock : new long[][] {{3, 1}, {4, 0}, {4, 1}}) {
            var graph = CraftGraph.<String>builder().stock("iron", stock[0]).stock("copper", stock[1])
                    .pattern("T", 2, List.of(CraftInput.of("iron", 1)))
                    .pattern("T", 4, List.of(CraftInput.of("iron", 3), CraftInput.of("copper", 1))).build();
            var first = graph.patternsFor("T").get(0);
            var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(first, 3L), "T", 5);
            assertNotNull(incumbent);
            var result = OrdinaryBatchOptimizer.tryImprove(graph, "T", 5, incumbent,
                    List.of("T", "iron", "copper"), work -> true, () -> true);
            if (stock[0] == 4 && stock[1] == 1) {
                assertNotNull(result);
                assertEquals(Map.of("iron", 4L, "copper", 1L), result.usedStock());
                assertEquals(2, result.firings().values().stream().mapToLong(n -> n).sum());
            } else assertNull(result);
        }
    }

    @Test
    void extraStockDoesNotAdmitStatefulOrByproductAlternatives() {
        var specialRoutes = List.of(
                new CraftPattern<>("T", 4, List.of(CraftInput.of("raw", 3), CraftInput.returned("tool", 1)), null),
                new CraftPattern<>("T", 4, List.of(CraftInput.consumedReturning("raw", 3, "container")), null),
                new CraftPattern<>("T", 4, List.of(CraftInput.of("raw", 3)), List.of(CraftOutput.of("side", 1)), null));
        for (var special : specialRoutes) {
            var graph = CraftGraph.<String>builder().stock("raw", 4).stock("tool", 1)
                    .pattern("T", 2, List.of(CraftInput.of("raw", 1))).pattern(special).build();
            var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(graph.patternsFor("T").get(0), 3L), "T", 5);
            assertNotNull(incumbent);
            assertNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 5, incumbent, List.of("T", "raw", "tool"),
                    work -> true, () -> { throw new AssertionError("special alternative entered certification"); }));
        }
    }

    @Test
    void mixedFinalBatchSavesStockAtTheSameMinimumExecutionCount() {
        var graph = directGraph();
        var first = graph.patternsFor("T").get(0);
        var second = graph.patternsFor("T").get(1);
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(first, 3L), "T", 8);
        assertNotNull(incumbent);

        var result = OrdinaryBatchOptimizer.tryImprove(graph, "T", 8, incumbent, List.of("T", "raw"),
                work -> true, () -> true);

        assertNotNull(result);
        assertEquals(Map.of(first, 2L, second, 1L), result.firings());
        assertEquals(Map.of("raw", 7L), result.usedStock());
        assertNotNull(MaterialDagReplay.tryPlan(graph, result.firings(), "T", 8));
        assertEquals(Map.of(first, 3L), incumbent.firings());
        assertEquals(Map.of("raw", 9L), incumbent.usedStock());
    }

    @Test
    void reducingIntermediateDemandTrimsTheNowUnneededUpstreamFirings() {
        var graph = CraftGraph.<String>builder().stock("raw", 128)
                .pattern("T", 3, List.of(CraftInput.of("I", 3)))
                .pattern("T", 2, List.of(CraftInput.of("I", 1)))
                .pattern("I", 1, List.of(CraftInput.of("raw", 1))).build();
        var first = graph.patternsFor("T").get(0);
        var second = graph.patternsFor("T").get(1);
        var upstream = graph.patternsFor("I").get(0);
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(first, 3L, upstream, 9L), "T", 8);
        assertNotNull(incumbent);

        var result = OrdinaryBatchOptimizer.tryImprove(graph, "T", 8, incumbent, List.of("T", "I", "raw"),
                work -> true, () -> true);

        assertNotNull(result);
        assertEquals(Map.of(first, 2L, second, 1L, upstream, 7L), result.firings());
        assertEquals(Map.of("raw", 7L), result.usedStock());
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
        assertNotNull(MaterialDagReplay.tryPlan(graph, result.firings(), "T", 8));
    }

    @Test
    void unchangedIntermediateCountsOutsideTheLongCacheDoNotSpendAProbe() {
        for (long count : new long[] {127, 128, 1_000}) {
            var graph = CraftGraph.<String>builder().stock("raw", count)
                    .pattern("T", 1, List.of(CraftInput.of("I", 1)))
                    .pattern("T", 1, List.of(CraftInput.of("I", 2)))
                    .pattern("I", 1, List.of(CraftInput.of("raw", 1))).build();
            var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(graph.patternsFor("T").get(0), count,
                    graph.patternsFor("I").get(0), count), "T", count);
            assertNotNull(incumbent);
            var probes = new AtomicInteger();
            assertNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", count, incumbent,
                    List.of("T", "I", "raw"), work -> true, () -> { probes.incrementAndGet(); return true; }));
            assertEquals(0, probes.get(), "unchanged firing count=" + count);
        }
    }

    @Test
    void statefulAndByproductWitnessesAreNotRewritten() {
        var specialRoutes = List.of(
                new CraftPattern<>("T", 3, List.of(CraftInput.of("raw", 3), CraftInput.returned("tool", 1)), null),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("raw", 3), CraftInput.finiteUse("tool", 1, 5)), null),
                new CraftPattern<>("T", 3, List.of(CraftInput.consumedReturning("raw", 3, "container")), null),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("raw", 3)), List.of(CraftOutput.of("side", 1)), null));
        for (var special : specialRoutes) {
            var graph = CraftGraph.<String>builder().stock("raw", 128).stock("tool", 1)
                    .pattern(special).pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
            var session = new CraftPlannerV2.PlanningSession<String>();
            session.optimizeFeasible = false;
            var incumbent = CraftPlannerV2.planDetailed(graph.withPatterns(Map.of("T", List.of(special))),
                    "T", 8, session).plan();
            assertTrue(incumbent.feasible());
            assertTrue(incumbent.firings().containsKey(special));
            assertNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 8, incumbent, List.of("T", "raw", "tool"),
                    work -> true, () -> { throw new AssertionError("stateful witness entered certification"); }));
        }
    }

    @Test
    void aBalancedReplacementWithoutAnAcyclicExecutionCertificateIsRejected() {
        var graph = CraftGraph.<String>builder().stock("raw", 2)
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 3, List.of(CraftInput.of("B", 2)))
                .pattern("B", 3, List.of(CraftInput.of("A", 1))).build();
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(graph.patternsFor("T").get(0), 1L,
                graph.patternsFor("A").get(0), 2L, graph.patternsFor("B").get(0), 1L), "T", 1);
        assertNotNull(incumbent);
        assertNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 1, incumbent, List.of("T", "A", "B", "raw"),
                work -> true, () -> true));
    }

    @Test
    void exhaustedWorkOrProbeBudgetsPreserveTheOriginalWitness() {
        var graph = directGraph();
        var original = Map.of(graph.patternsFor("T").get(0), 3L);
        var incumbent = MaterialDagReplay.tryPlan(graph, original, "T", 8);
        assertNotNull(incumbent);
        var fullWork = new AtomicInteger();
        assertNotNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 8, incumbent, List.of("T", "raw"),
                work -> { assertTrue(work > 0); fullWork.addAndGet(work); return true; }, () -> true));
        for (int limit = 0; limit < fullWork.get(); limit++) {
            int[] remaining = {limit};
            var result = OrdinaryBatchOptimizer.tryImprove(graph, "T", 8, incumbent, List.of("T", "raw"), work -> {
                assertTrue(work > 0);
                if (work > remaining[0]) return false;
                remaining[0] -= work;
                return true;
            }, () -> true);
            assertNull(result, "insufficient work=" + limit);
            assertTrue(remaining[0] >= 0);
        }
        var deniedProbes = new AtomicInteger();
        assertNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 8, incumbent, List.of("T", "raw"),
                work -> true, () -> { deniedProbes.incrementAndGet(); return false; }));
        assertEquals(1, deniedProbes.get());
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("raw", 9L), incumbent.usedStock());
    }

    @Test
    void cancellationRemainsVisibleToTheCaller() {
        var graph = directGraph();
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(graph.patternsFor("T").get(0), 3L), "T", 8);
        assertNotNull(incumbent);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> OrdinaryBatchOptimizer.tryImprove(
                    graph, "T", 8, incumbent, List.of("T", "raw"), work -> true, () -> true));
        } finally {
            Thread.interrupted();
        }
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> OrdinaryBatchOptimizer.tryImprove(
                    graph, "T", 8, incumbent, List.of("T", "raw"), work -> true, () -> true));
        }
    }

    @Test
    void impossiblePositiveTriplesDoNotConsumeTheEqualExecutionSearchAllowance() {
        // The expensive six-output routes defeat a largest-output-only feasibility bound.
        // One case needs three positive firings; the other has enough firings but cannot
        // afford even one copy of an expensive route together with either useful route.
        for (long demand : new long[] {12, 17}) for (int extraRoute : new int[] {0, 1}) {
            long stock = demand == 12 ? 12 : 18;
            var builder = CraftGraph.<String>builder().stock("raw", stock);
            long[] output = {6, 3, 4, 5, 6};
            long[] use = {stock, 1, 4, 6, stock};
            for (int i = 0; i < output.length; i++)
                builder.pattern("T", output[i], List.of(CraftInput.of("raw", use[i])));
            if (extraRoute != 0) builder.pattern("T", 6, List.of(CraftInput.of("raw", stock)));
            var graph = builder.build();
            var routes = graph.patternsFor("T");
            var original = demand == 12 ? Map.of(routes.get(2), 3L)
                    : Map.of(routes.get(2), 3L, routes.get(3), 1L);
            var incumbent = MaterialDagReplay.tryPlan(graph, original, "T", demand);
            assertNotNull(incumbent);

            var result = OrdinaryBatchOptimizer.tryImprove(graph, "T", demand, incumbent,
                    List.of("T", "raw"), work -> true, () -> true);

            String context = "demand=" + demand + ", routes=" + routes.size();
            assertNotNull(result, context);
            assertEquals(Map.of(routes.get(1), 1L, routes.get(2), 1L,
                    routes.get(3), demand == 12 ? 1L : 2L), result.firings(), context);
            assertEquals(Map.of("raw", demand == 12 ? 11L : 17L), result.usedStock(), context);
            assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result), context);
            assertNotNull(MaterialDagReplay.tryPlan(graph, result.firings(), "T", demand), context);
            assertEquals(original, incumbent.firings(), context);
            assertEquals(Map.of("raw", stock), incumbent.usedStock(), context);
        }
    }

    @Test
    void replacingOneProducerKeepsAnotherProducerForAFourRouteImprovement() {
        var graph = unitCostBatchGraph(25);
        var routes = graph.patternsFor("T");
        var original = Map.of(routes.get(4), 1L, routes.get(2), 4L);
        var incumbent = MaterialDagReplay.tryPlan(graph, original, "T", 25);
        assertNotNull(incumbent);

        var result = OrdinaryBatchOptimizer.tryImprove(graph, "T", 25, incumbent,
                List.of("T", "raw"), work -> true, () -> true);

        assertNotNull(result);
        assertEquals(Map.of(routes.get(1), 1L, routes.get(2), 1L,
                routes.get(3), 1L, routes.get(4), 1L), result.firings());
        assertEquals(4L, result.firings().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of("raw", 25L), result.usedStock());
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
        assertNotNull(MaterialDagReplay.tryPlan(graph, result.firings(), "T", 25));
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("raw", 25L), incumbent.usedStock());
    }

    @Test
    void aReplacementAddsFiringsToTheSameIdentityAsAKeptProducer() {
        var graph = unitCostBatchGraph(51);
        var routes = graph.patternsFor("T");
        var original = Map.of(routes.get(2), 1L, routes.get(3), 5L, routes.get(4), 1L);
        var incumbent = MaterialDagReplay.tryPlan(graph, original, "T", 51);
        assertNotNull(incumbent);

        // Keep 3 + 13 and replace five 7s by 2 + 7 + 13 + 13. The kept 13 must
        // be added to the two replacement firings; overwriting it leaves only 38 output.
        var result = OrdinaryBatchOptimizer.tryImprove(graph, "T", 51, incumbent,
                List.of("T", "raw"), work -> true, () -> true);

        assertNotNull(result);
        assertEquals(Map.of(routes.get(1), 1L, routes.get(2), 1L,
                routes.get(3), 1L, routes.get(4), 3L), result.firings());
        assertEquals(6L, result.firings().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of("raw", 51L), result.usedStock());
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
        assertNotNull(MaterialDagReplay.tryPlan(graph, result.firings(), "T", 51));
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("raw", 51L), incumbent.usedStock());
    }

    @Test
    void rejectedWorkAndProbeReservationsLeaveTheRetainedProducerWitnessIntact() {
        var graph = unitCostBatchGraph(25);
        var routes = graph.patternsFor("T");
        var original = Map.of(routes.get(4), 1L, routes.get(2), 4L);
        var incumbent = MaterialDagReplay.tryPlan(graph, original, "T", 25);
        assertNotNull(incumbent);
        var fullDebits = new AtomicInteger();
        assertNotNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 25, incumbent,
                List.of("T", "raw"), work -> {
                    assertTrue(work > 0);
                    fullDebits.incrementAndGet();
                    return true;
                }, () -> true));

        for (int allowed : new int[] {0, 1, fullDebits.get() / 2, fullDebits.get() - 1}) {
            var debits = new AtomicInteger();
            assertNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 25, incumbent,
                    List.of("T", "raw"), work -> {
                        assertTrue(work > 0);
                        return debits.incrementAndGet() <= allowed;
                    }, () -> true), "allowed work debits=" + allowed);
            assertEquals(original, incumbent.firings());
            assertEquals(Map.of("raw", 25L), incumbent.usedStock());
        }

        var deniedProbes = new AtomicInteger();
        assertNull(OrdinaryBatchOptimizer.tryImprove(graph, "T", 25, incumbent,
                List.of("T", "raw"), work -> true, () -> {
                    deniedProbes.incrementAndGet();
                    return false;
                }));
        assertEquals(1, deniedProbes.get(), "a refused certificate ends the remaining local search");
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("raw", 25L), incumbent.usedStock());
    }

    @Test
    void cancellationDuringRetainedProducerCertificationPreservesTheIncumbent() {
        var graph = unitCostBatchGraph(25);
        var routes = graph.patternsFor("T");
        var original = Map.of(routes.get(4), 1L, routes.get(2), 4L);
        var incumbent = MaterialDagReplay.tryPlan(graph, original, "T", 25);
        assertNotNull(incumbent);
        var signal = new CancellationException("stop retained-producer certification");
        assertSame(signal, assertThrows(CancellationException.class,
                () -> OrdinaryBatchOptimizer.tryImprove(graph, "T", 25, incumbent,
                        List.of("T", "raw"), work -> true, () -> { throw signal; })));

        var probes = new AtomicInteger();
        try {
            assertThrows(CancellationException.class, () -> OrdinaryBatchOptimizer.tryImprove(
                    graph, "T", 25, incumbent, List.of("T", "raw"), work -> true, () -> {
                        probes.incrementAndGet();
                        Thread.currentThread().interrupt();
                        return true;
                    }));
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, probes.get());
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("raw", 25L), incumbent.usedStock());
    }

    private static CraftGraph<String> unitCostBatchGraph(long stock) {
        var builder = CraftGraph.<String>builder().stock("raw", stock);
        for (long output : new long[] {1, 2, 3, 7, 13})
            builder.pattern("T", output, List.of(CraftInput.of("raw", output)));
        return builder.build();
    }

    private static CraftGraph<String> directGraph() {
        return CraftGraph.<String>builder().stock("raw", 128)
                .pattern("T", 3, List.of(CraftInput.of("raw", 3)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
    }

    private static CraftGraph<String> surplusStockGraph() {
        return CraftGraph.<String>builder().stock("raw", 24).stock("A", 2)
                .pattern("A", 2, List.of(CraftInput.of("raw", 2)))
                .pattern("T", 2, List.of(CraftInput.of("A", 4)))
                .pattern("T", 3, List.of(CraftInput.of("A", 3))).build();
    }

    private static CraftPlan<String> surplusStockIncumbent(CraftGraph<String> graph) {
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        var incumbent = CraftPlannerV2.planDetailed(graph.withPatterns(Map.of("T",
                List.of(graph.patternsFor("T").get(1)), "A", graph.patternsFor("A"))), "T", 1, session).plan();
        assertTrue(incumbent.feasible());
        assertEquals(Map.of(graph.patternsFor("A").get(0), 1L,
                graph.patternsFor("T").get(1), 1L), incumbent.firings());
        assertEquals(Map.of("raw", 2L, "A", 2L), incumbent.usedStock());
        return incumbent;
    }
}
