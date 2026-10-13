package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import org.junit.jupiter.api.Test;

class MaterialDagReuseTest {
    @Test
    void completedPrimaryFamilyNeedsOnlyTraversalWorkInsteadOfAnotherCompilation() {
        var graph = ordinaryCycle();
        var primary = MaterialDagOrders.compileCertificateFamily(graph, "T", unlimited(), c -> false, List.of());
        assertTrue(primary.portfolio().complete());
        assertTrue(primary.portfolio().candidates().size() > 1);
        int count = primary.portfolio().candidates().size();
        var reused = MaterialDagOrders.compilePortfolio(graph, "T",
                BoundedIntegerLinearSolver.WorkBudget.bounded(1, count, Long.MAX_VALUE),
                c -> false, List.of(), family -> true, primary);
        assertEquals(count, reused.candidates().size(), "one work unit per retained support is sufficient");
        for (int i = 0; i < count; i++) assertSame(primary.portfolio().candidates().get(i), reused.candidates().get(i));
        assertFalse(reused.complete(), "a stopped family callback is not whole-portfolio completion");
        var insufficient = MaterialDagOrders.compilePortfolio(graph, "T",
                BoundedIntegerLinearSolver.WorkBudget.bounded(1, count - 1, Long.MAX_VALUE),
                c -> false, List.of(), family -> true, primary);
        assertEquals(count - 1, insufficient.candidates().size());
        assertFalse(insufficient.complete(), "reuse does not waive shared work denial");
    }

    @Test
    void ordinaryGraphsDoNotRebuildOrDuplicateTheSameFullFamily() {
        var graph = ordinaryCycle();
        var primary = MaterialDagOrders.compileCertificateFamily(graph, "T", unlimited(), c -> false, List.of());
        var legacy = MaterialDagOrders.compilePortfolio(graph, "T", unlimited(), c -> false, List.of());
        int[] callbacks = {0};
        var reused = MaterialDagOrders.compilePortfolio(graph, "T", unlimited(), c -> false, List.of(),
                family -> { callbacks[0]++; return false; }, primary);
        assertTrue(reused.complete());
        assertEquals(legacy.candidates().size(), reused.candidates().size());
        assertEquals(signatures(legacy), signatures(reused));
        assertEquals(1, callbacks[0], "ordinary supports have only one full-probe callback");
        for (int i = 0; i < reused.candidates().size(); i++)
            assertSame(primary.portfolio().candidates().get(i), reused.candidates().get(i));
    }

    @Test
    void sideOutputSupportsRemainAvailableAndRestoreTheOriginalPhysicalRecipes() {
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).build();
        var primary = MaterialDagOrders.compileCertificateFamily(graph, "T", unlimited(), c -> false, List.of());
        assertTrue(primary.portfolio().candidates().stream().allMatch(c -> c.tryFixedPlan("T", 1) == null));
        var legacy = MaterialDagOrders.compilePortfolio(graph, "T", unlimited(), c -> false, List.of());
        var reused = MaterialDagOrders.compilePortfolio(graph, "T", unlimited(), c -> false, List.of(), f -> false, primary);
        assertTrue(reused.complete());
        assertEquals(signatures(legacy), signatures(reused));
        var full = reused.candidates().stream().filter(c -> c.supplyOrder().stream()
                .anyMatch(p -> !p.byproducts().isEmpty())).findFirst().orElseThrow();
        var ready = full.restore(CraftPlannerV2.plan(full.graph(), "T", 1, 1, 1));
        assertTrue(ready.feasible());
        assertEquals(Map.of("raw", 1L), ready.usedStock());
        assertEquals(Map.of(graph.patternsFor("A").get(0), 1L, graph.patternsFor("T").get(0), 1L), ready.firings());
        ByproductReplaySafetyTest.assertEveryOrderFinishes(ready, "T", 1);
    }

    @Test
    void anotherStockSnapshotOrTargetRecompilesInsteadOfBorrowingOldCapacities() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("raw", 2)))
                .pattern("U", 1, List.of(CraftInput.of("raw", 1))).build();
        var primary = MaterialDagOrders.compileCertificateFamily(graph, "T", unlimited(), c -> false, List.of());
        var supplied = graph.withAdditionalStock(Map.of("raw", 2L));
        assertFalse(primary.reusableFor(supplied, "T"));
        assertFalse(primary.reusableFor(graph, "U"));
        for (String target : List.of("T", "U")) {
            var fresh = MaterialDagOrders.compilePortfolio(supplied, target, unlimited(), c -> false,
                    List.of(), f -> false, primary);
            assertTrue(fresh.complete());
            var ready = fresh.candidates().get(0).tryFixedPlan(target, 1);
            assertNotNull(ready);
            assertEquals(Map.of("raw", target.equals("T") ? 2L : 1L), ready.usedStock());
            assertEquals(2L, fresh.candidates().get(0).graph().stock("raw"));
        }
        assertEquals(0L, graph.stock("raw"));
        assertTrue(primary.portfolio().candidates().stream().noneMatch(c -> c.maySupply(1)));
    }

    @Test
    void anEarlyAcceptedPrefixIsRecompiledAndCannotSuppressUnseenSupports() {
        var graph = ordinaryCycle();
        var prefix = MaterialDagOrders.compileCertificateFamily(graph, "T", unlimited(), c -> true, List.of());
        assertFalse(prefix.portfolio().complete());
        assertFalse(prefix.reusableFor(graph, "T"));
        var legacy = MaterialDagOrders.compilePortfolio(graph, "T", unlimited(), c -> false, List.of());
        var fresh = MaterialDagOrders.compilePortfolio(graph, "T", unlimited(), c -> false, List.of(), f -> false, prefix);
        assertTrue(fresh.complete());
        assertTrue(fresh.candidates().size() > prefix.portfolio().candidates().size());
        assertEquals(signatures(legacy), signatures(fresh));
        assertNotSame(prefix.portfolio().candidates().get(0), fresh.candidates().get(0));
    }

    @Test
    void reusedTraversalHonorsTheParentDeadlineAndPropagatesOuterCancellation() throws Exception {
        var graph = ordinaryCycle();
        var primary = MaterialDagOrders.compileCertificateFamily(graph, "T", unlimited(), c -> false, List.of());
        var field = PlanningCancellation.class.getDeclaredField("OPTIONAL_DEADLINE");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var deadline = (ThreadLocal<Long>) field.get(null);
        try (var scope = PlanningCancellation.limitOptionalWork(Long.MAX_VALUE / 4)) {
            long expired = System.nanoTime() - 1;
            deadline.set(expired);
            var result = MaterialDagOrders.compilePortfolio(graph, "T", unlimited(), c -> false, List.of(), f -> false, primary);
            assertFalse(result.complete());
            assertTrue(result.candidates().isEmpty());
            assertEquals(expired, deadline.get());
        }
        assertDoesNotThrow(PlanningCancellation::check);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> MaterialDagOrders.compilePortfolio(
                    graph, "T", unlimited(), c -> false, List.of(), f -> false, primary));
        } finally {
            Thread.interrupted();
        }
        var exit = new PlanningExitException("cancel reused family");
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() { throw exit; }
        };
        try (var bound = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class, () -> MaterialDagOrders.compilePortfolio(
                    graph, "T", unlimited(), c -> false, List.of(), f -> false, primary)));
        }
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void aRejectedCoreProposalIsNotRepeatedBeforeTheFullProbe() {
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("B", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        session.refineMissing = false;
        int[] core = {0}, repeated = {0};
        var walker = StackWalker.getInstance();
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() {
                var frames = walker.walk(stream -> stream.map(StackWalker.StackFrame::getMethodName)
                        .collect(Collectors.toSet()));
                if (frames.contains("tryFixedPlan")) {
                    if (frames.contains("tryMaterialDagCertificate")) core[0]++;
                    if (frames.contains("tryMaterialDagOrder")) repeated[0]++;
                }
            }
        };
        CraftPlan<String> plan;
        try (var bound = PlanningCancellation.bind(context)) {
            plan = CraftPlannerV2.planDetailed(graph, "T", 1, session).plan();
        }
        assertTrue(core[0] > 0, "the optimistic capacity must reach the real shared-stock check");
        assertEquals(0, repeated[0], "only the full planning probe remains after the failed core proof");
        assertFalse(plan.feasible());
        assertEquals(Map.of("raw", 1L), plan.missing());
        var ready = CraftPlannerV2.plan(graph.withAdditionalStock(plan.missing()), "T", 1, 1, 1);
        assertTrue(ready.feasible());
        assertEquals(Map.of("raw", 2L), ready.usedStock());
    }

    private static BoundedIntegerLinearSolver.WorkBudget unlimited() {
        return BoundedIntegerLinearSolver.WorkBudget.unlimited();
    }

    private static CraftGraph<String> ordinaryCycle() {
        return CraftGraph.<String>builder().stock("raw", 4)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 1)))
                .pattern("B", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("B", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1))).build();
    }

    private static Set<Map<CraftPattern<String>, List<CraftOutput<String>>>> signatures(
            MaterialDagOrders.Portfolio<String> portfolio) {
        return portfolio.candidates().stream().map(candidate -> {
            var signature = new HashMap<CraftPattern<String>, List<CraftOutput<String>>>();
            for (var pattern : candidate.supplyOrder())
                signature.put(candidate.originals().getOrDefault(pattern, pattern), pattern.byproducts());
            return Map.copyOf(signature);
        }).collect(Collectors.toSet());
    }
}
