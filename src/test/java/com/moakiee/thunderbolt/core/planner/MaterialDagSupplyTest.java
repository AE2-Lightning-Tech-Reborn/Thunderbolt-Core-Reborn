package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialDagSupplyTest {
    @Test
    void twoRoutesForTheSameOutputCanSupplyEachOtherWithoutARealMaterialCycle() {
        for (long n : new long[] {1, 2, 1_000_000, 1_000_000_000_000L}) {
            var fromSide = new CraftPattern<>("P", 1, List.of(CraftInput.of("side", 1)), "fromSide");
            var producer = new CraftPattern<>("P", 1, List.of(CraftInput.of("raw", 1)),
                    List.of(CraftOutput.of("side", 1)), "producer");
            var graph = CraftGraph.<String>builder().stock("raw", n)
                    .pattern(fromSide).pattern(producer)
                    .pattern("T", 1, List.of(CraftInput.of("P", 2))).build();
            var plan = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CraftPlannerV2.plan(graph, "T", n));
            assertTrue(plan.feasible(), () -> plan.toString());
            assertEquals(Map.of("raw", n), plan.usedStock());
            assertEquals(n, plan.firings().get(fromSide));
            assertEquals(n, plan.firings().get(producer));
            if (n <= 2) ByproductReplaySafetyTest.assertEveryOrderFinishes(plan, "T", n);
        }
    }

    @Test
    void aStockedSiblingStillFundsTheIntermediateSideOutput() {
        for (boolean reverse : List.of(false, true)) {
            for (long n : new long[] {1, 2, 1_000_000, 1_000_000_000_000L}) {
                var side = new CraftPattern<>("B", 1,
                        List.of(CraftInput.of("raw", 1), CraftInput.of("fuel", 1)),
                        List.of(CraftOutput.of("S", 1)), "side");
                var main = new CraftPattern<>("A", 2,
                        List.of(CraftInput.of("seed", 1), CraftInput.of("S", 2)),
                        List.of(CraftOutput.of("B", 1)), "main");
                var inputs = new ArrayList<>(List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)));
                if (reverse) java.util.Collections.reverse(inputs);
                var graph = CraftGraph.<String>builder().stock("raw", n).stock("fuel", 3*n)
                        .stock("seed", n).stock("S", n).stock("B", 4*n)
                        .pattern(side).pattern(main).pattern("T", 1, inputs).build();
                var plan = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> CraftPlannerV2.plan(graph, "T", 2*n));
                assertTrue(plan.feasible(), () -> plan.toString());
                assertEquals(n, plan.firings().get(side));
                assertEquals(n, plan.firings().get(main));
                if (n <= 2) ByproductReplaySafetyTest.assertEveryOrderFinishes(plan, "T", 2*n);
            }
        }
    }

    @Test
    void manyIndependentSideOutputForksUseOneCountVectorInsteadOfRecursiveRetries() {
        for (int branches : new int[] {16, 256, 1024}) {
            for (long n : new long[] {1, 1_000_000_000_000L}) {
                var builder = CraftGraph.<String>builder();
                var root = new ArrayList<CraftInput<String>>();
                for (int i = 0; i < branches; i++) {
                    String raw = "raw"+i, side = "side"+i, output = "P"+i;
                    builder.stock(raw, n)
                            .pattern(output, 1, List.of(CraftInput.of(side, 1)))
                            .pattern(output, 1, List.of(CraftInput.of(raw, 1)), List.of(CraftOutput.of(side, 1)));
                    root.add(CraftInput.of(output, 2));
                }
                var graph = builder.pattern("T", 1, root).build();
                var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                        () -> CraftPlannerV2.planDetailed(graph, "T", n));
                assertTrue(result.plan().feasible(), () -> result.diagnostics().toString());
                assertEquals(1, result.diagnostics().planRuns());
                assertEquals(branches, result.plan().usedStock().size());
                result.plan().usedStock().values().forEach(used -> assertEquals(n, used));
            }
        }
    }

    @Test
    void stockedSideOutputChoicesStayLocalAcrossThousandsOfIndependentBranches() {
        for (int branches : new int[] {16, 64, 256, 1024}) {
            for (long n : new long[] {1, 1_000_000_000_000L}) {
                var graph = stockedBranches(branches, n, false, false, 0);
                var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                        () -> CraftPlannerV2.planDetailed(graph, "T", n));
                assertTrue(result.plan().feasible(), () -> result.diagnostics().toString());
                assertEquals(1, result.diagnostics().planRuns());
                assertEquals(branches, result.diagnostics().lowWidthSolved());
                assertTrue(result.diagnostics().separatorWidthPeak() <= 3);
                assertStockIsPhysical(graph, result.plan());
            }
        }
    }

    @Test
    void stockAboveTheSideProducerRemainsACraftingChoice() {
        for (int branches : new int[] {1, 64}) {
            for (long n : new long[] {1, 1_000_000_000_000L}) {
                var graph = stockedBranches(branches, n, true, false, 0);
                var plan = assertTimeoutPreemptively(Duration.ofSeconds(3),
                        () -> CraftPlannerV2.plan(graph, "T", n));
                assertTrue(plan.feasible(), () -> plan.toString());
                assertStockIsPhysical(graph, plan);
                for (int i = 0; i < branches; i++) {
                    assertEquals(n, plan.usedStock().get("raw"+i));
                }
                if (branches == 1 && n == 1)
                    ByproductReplaySafetyTest.assertEveryOrderFinishes(plan, "T", n);
            }
        }
    }

    @Test
    void sharedStockStillCouplesTheLocalSideOutputChoices() {
        for (boolean wrapped : List.of(false, true)) {
            for (long shortage : new long[] {0, 1}) {
                var graph = stockedBranches(4, 1, wrapped, true, shortage);
                var plan = CraftPlannerV2.plan(graph, "T", 1);
                assertEquals(shortage == 0, plan.feasible(), () -> plan.toString());
                assertStockIsPhysical(graph, plan);
                ByproductReplaySafetyTest.assertEveryOrderFinishes(plan, "T", 1);
                var readyGraph = graph.withAdditionalStock(plan.missing());
                var ready = CraftPlannerV2.plan(readyGraph, "T", 1);
                assertTrue(ready.feasible(), () -> ready.toString());
                assertStockIsPhysical(readyGraph, ready);
                ByproductReplaySafetyTest.assertEveryOrderFinishes(ready, "T", 1);
            }
        }
    }

    @Test
    void wideSharedRawStockIsVerifiedAfterLocalProposalsAreCombined() {
        for (int branches : new int[] {64, 256, 1024}) {
            for (long n : new long[] {1, 1_000_000_000_000L}) {
                for (long shortage : new long[] {0, 1}) {
                    var graph = stockedBranches(branches, n, false, true, shortage);
                    var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                            () -> CraftPlannerV2.planDetailed(graph, "T", n));
                    var plan = result.plan();
                    var context = "branches=" + branches + " n=" + n + " shortage=" + shortage
                            + " diagnostics=" + result.diagnostics();
                    assertEquals(shortage == 0, plan.feasible(), context);
                    assertEquals(shortage == 0 ? Map.of() : Map.of("raw", shortage), plan.missing(), context);
                    assertStockIsPhysical(graph, plan);
                    var stocked = graph.withAdditionalStock(plan.missing());
                    var ready = assertTimeoutPreemptively(Duration.ofSeconds(3),
                            () -> CraftPlannerV2.plan(stocked, "T", n));
                    assertTrue(ready.feasible(), () -> "branches=" + branches + " n=" + n
                            + " shortage=" + shortage + " missing=" + ready.missing());
                    assertStockIsPhysical(stocked, ready);
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredIndependentProposalReportsCutoffAndKeepsAReplenishableFallback() throws Exception {
        var field = PlanningCancellation.class.getDeclaredField("OPTIONAL_DEADLINE");
        field.setAccessible(true);
        var deadline = (ThreadLocal<Long>) field.get(null);
        var expired = new java.util.concurrent.atomic.AtomicBoolean();
        var graph = stockedBranches(256, 1, false, true, 1);
        var context = new com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() {
                if (expired.get() || deadline.get() == null) return;
                if (StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        frame.getClassName().equals(CraftPlannerV2.class.getName())
                                && frame.getMethodName().equals("tryIndependentRawStock")))) {
                    expired.set(true);
                    deadline.set(System.nanoTime() - 1L);
                }
            }
        };
        PlanningResult<String> result;
        try (var ignored = PlanningCancellation.bind(context)) {
            result = CraftPlannerV2.planDetailed(graph, "T", 1);
        }
        assertTrue(expired.get(), "the original shared-raw proposal path must be reached");
        assertFalse(result.plan().feasible());
        assertTrue(result.plan().budgetExhausted(), "a timed-out proposal is not a completed shortage search");
        assertTrue(result.diagnostics().lowWidthCutoffs() > 0);
        assertStockIsPhysical(graph, result.plan());
        var stocked = graph.withAdditionalStock(result.plan().missing());
        var ready = CraftPlannerV2.plan(stocked, "T", 1);
        assertTrue(ready.feasible());
        assertStockIsPhysical(stocked, ready);
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void freshSharedRawIdentitiesKeepShortageAndReplanningStable() {
        for (long shortage : new long[] {0, 1}) {
            var graph = stockedBranches(1024, 1, false, true, shortage);
            var first = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> CraftPlannerV2.planDetailed(graph, "T", 1));
            assertEquals(shortage == 0 ? Map.of() : Map.of("raw", 1L), first.plan().missing(),
                    () -> first.diagnostics().toString());
            assertFalse(first.plan().budgetExhausted(), () -> first.diagnostics().toString());
            assertStockIsPhysical(graph, first.plan());
            var supplied = graph.withAdditionalStock(first.plan().missing());
            var ready = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> CraftPlannerV2.plan(supplied, "T", 1));
            assertTrue(ready.feasible(), () -> "missing=" + ready.missing());
            assertFalse(ready.budgetExhausted());
            assertStockIsPhysical(supplied, ready);
        }
    }

    @Test
    void sharedRawStockMustAlsoPayForTheRootOutsideTheLocalModels() {
        var graph = stockedBranches(64, 1, false, true, 0, 1);
        var result = CraftPlannerV2.planDetailed(graph, "T", 1);
        var plan = result.plan();
        assertFalse(plan.feasible());
        assertEquals(Map.of("raw", 1L), plan.missing(), () -> result.diagnostics().toString());
        assertStockIsPhysical(graph, plan);
        assertTrue(CraftPlannerV2.plan(graph.withAdditionalStock(plan.missing()), "T", 1).feasible());
    }

    @Test
    void linearSideOutputSchedulingCoversGraphsBeyondTheFormerSixteenThousandWorkCap() {
        for (long n : new long[] {1, 1_000_000_000_000L}) {
            var graph = stockedBranches(4096, n, false, true, 0);
            var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> CraftPlannerV2.planDetailed(graph, "T", n));
            assertTrue(result.plan().feasible(), () -> result.diagnostics().toString());
            assertEquals(1, result.diagnostics().planRuns());
            assertStockIsPhysical(graph, result.plan());
        }
    }

    private static CraftGraph<String> stockedBranches(
            int branches, long n, boolean wrapped, boolean sharedRaw, long shortage) {
        return stockedBranches(branches, n, wrapped, sharedRaw, shortage, 0);
    }

    private static CraftGraph<String> stockedBranches(
            int branches, long n, boolean wrapped, boolean sharedRaw, long shortage, long rootRaw) {
        var builder = CraftGraph.<String>builder();
        var root = new ArrayList<CraftInput<String>>();
        if (sharedRaw) builder.stock("raw", branches*n-shortage);
        for (int i = 0; i < branches; i++) {
            String raw = sharedRaw ? "raw" : "raw"+i;
            String fuel = "fuel"+i, seed = "seed"+i, side = "S"+i, a = "A"+i, b = "B"+i;
            if (!sharedRaw) builder.stock(raw, n);
            builder.stock(fuel, 3*n).stock(seed, n).stock(side, n).stock(b, 4*n)
                    .pattern(b, 1, List.of(CraftInput.of(raw, 1), CraftInput.of(fuel, 1)),
                            List.of(CraftOutput.of(side, 1)))
                    .pattern(a, 2, List.of(CraftInput.of(seed, 1), CraftInput.of(side, 2)),
                            List.of(CraftOutput.of(b, 1)));
            root.add(CraftInput.of(a, 2));
            if (wrapped) {
                String wrapper = "C"+i;
                builder.stock(wrapper, 2*n).pattern(wrapper, 1, List.of(CraftInput.of(b, 1)));
                root.add(CraftInput.of(wrapper, 2));
            } else {
                root.add(CraftInput.of(b, 2));
            }
        }
        if (rootRaw > 0) root.add(CraftInput.of("raw", rootRaw));
        return builder.pattern("T", 1, root).build();
    }

    private static void assertStockIsPhysical(CraftGraph<String> graph, CraftPlan<String> plan) {
        plan.usedStock().forEach((key, used) -> assertTrue(used <= graph.stock(key),
                () -> key+": used="+used+", stock="+graph.stock(key)));
    }

    @Test
    void saturatedSupplyBoundsRemainUpperBoundsAfterDownstreamDivision() {
        long stock = Sat.SAT / 2;
        var graph = CraftGraph.<String>builder().stock("raw", stock)
                .pattern("B", 4, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("B", 2))).build();
        assertTrue(MaterialDagOrders.compile(graph, "T").stream()
                .anyMatch(candidate -> candidate.maySupply(2*stock)),
                "clamping 4*stock before dividing by two must not underestimate real supply");
    }

    @Test
    void optimisticSupplyMayShareStockButAcceptanceMustNotDoubleSpendIt() {
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("B", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).build();
        assertTrue(MaterialDagOrders.compile(graph, "T").stream().anyMatch(candidate -> candidate.maySupply(1)));
        var plan = CraftPlannerV2.plan(graph, "T", 1);
        assertFalse(plan.feasible());
        assertEquals(Map.of("raw", 1L), plan.missing());
    }

    @Test
    void exactInputAmountsCannotBeCertifiedFromClampedStock() {
        var input = new CraftInput<>("raw", Long.MAX_VALUE, false, CraftInput.INFINITE_USES,
                null, null, java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE));
        var pattern = new CraftPattern<>("T", 1, List.of(input), "huge");
        var graph = CraftGraph.<String>builder().stock("raw", Long.MAX_VALUE).pattern(pattern).build();
        assertNull(MaterialDagReplay.tryPlan(graph, Map.of(pattern, 1L), "T", 1));
    }

    @Test
    void vectorCertificateRejectsExtraByproductOnlyFiringsAndRealCycles() {
        var side = new CraftPattern<>("A", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("T", 1)), "side");
        var extra = CraftGraph.<String>builder().stock("raw", 1).pattern(side).build();
        assertNull(MaterialDagReplay.tryPlan(extra, Map.of(side, 1L), "T", 1));

        var forward = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)), "forward");
        var back = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)),
                List.of(CraftOutput.of("T", 1)), "back");
        var cyclic = CraftGraph.<String>builder().stock("A", 1).pattern(forward).pattern(back).build();
        assertNull(MaterialDagReplay.tryPlan(cyclic, Map.of(forward, 1L, back, 1L), "T", 1));
    }

    @Test
    void smallVectorProofMustRejectAnEnabledOrderThatStealsTheSeed() {
        var producer = new CraftPattern<>("A", 1, List.of(CraftInput.of("seed", 1), CraftInput.of("fuel", 1)),
                List.of(CraftOutput.of("seed", 1)), "producer");
        var sink = new CraftPattern<>("B", 1, List.of(CraftInput.of("seed", 1)), "sink");
        var root = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), "root");
        var graph = CraftGraph.<String>builder().stock("seed", 1).stock("fuel", 1)
                .pattern(producer).pattern(sink).pattern(root).build();
        var counts = Map.of(producer, 1L, sink, 1L, root, 1L);
        assertNull(MaterialDagReplay.trySmallPlan(graph, counts, "T", 1),
                "producer first works, but sink first deadlocks and must invalidate the vector");
        var stocked = graph.withAdditionalStock(Map.of("seed", 1L));
        var safe = MaterialDagReplay.trySmallPlan(stocked, counts, "T", 1);
        assertNotNull(safe);
        assertEquals(2L, safe.usedStock().get("seed"));
        ByproductReplaySafetyTest.assertEveryOrderFinishes(safe, "T", 1);
        assertNull(MaterialDagReplay.trySmallPlan(stocked,
                Map.of(producer, 1_000_000_000_000L, sink, 1L, root, 1L), "T", 1),
                "the optional small proof must not become a loop over a large order quantity");
    }

    @Test
    void leafDiagnosisCannotReplaceAConstructibleIntermediateWithMissingStock() {
        var intermediate = new CraftPattern<>("A", 1, List.of(CraftInput.of("raw", 1)), "intermediate");
        var root = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1)), "root");
        var graph = CraftGraph.<String>builder().pattern(intermediate).pattern(root).build();
        assertNull(MaterialDagReplay.tryLeafMissingPlan(graph, Map.of(root, 1L), "T", 1));
        var diagnosis = MaterialDagReplay.tryLeafMissingPlan(graph, Map.of(root, 1L, intermediate, 1L), "T", 1);
        assertNotNull(diagnosis);
        assertEquals(Map.of("raw", 1L), diagnosis.missing());
        ByproductReplaySafetyTest.assertEveryOrderFinishes(diagnosis, "T", 1);
    }
}
