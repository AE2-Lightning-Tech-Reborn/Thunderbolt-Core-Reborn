package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TagConversionCostTest {
    @Test
    void ordinaryOneToOneRecipesRemainRealExecutions() {
        Object source = new Object();
        var ordinary = new CraftPattern<>("#tag", 1, List.of(CraftInput.of("member", 1)), source);
        var conversion = CraftPattern.tagConversion("member", "#tag", source);
        assertEquals(1, ordinary.executionCost());
        assertEquals(0, conversion.executionCost());
        assertSame(source, conversion.source());
        var ordinaryGraph = CraftGraph.<String>builder().stock("member", 2).pattern(ordinary).build();
        var taggedGraph = CraftGraph.<String>builder().stock("member", 2).pattern(conversion).build();
        var real = CraftPlannerV2.plan(ordinaryGraph, "#tag", 2);
        var tagged = CraftPlannerV2.plan(taggedGraph, "#tag", 2);
        assertEquals(BigInteger.TWO, real.executionCount());
        assertEquals(BigInteger.ZERO, tagged.executionCount());
        assertEquals(2L, tagged.firings().get(conversion));
        assertEquals(Map.of("member", 2L), tagged.usedStock());
        replay(ordinaryGraph, real, "#tag", 2);
        replay(taggedGraph, tagged, "#tag", 2);
    }

    @Test
    void oneMachineOperationWithManyTagTransfersBeatsTwoRealOperations() {
        var graph = competingRoutes(true);
        var initial = planWithoutOptimization(graph, "T", 1);
        var result = CraftPlannerV2.plan(graph, "T", 1);
        assertEquals(BigInteger.ONE, result.executionCount());
        assertEquals(100L, result.firings().entrySet().stream()
                .filter(e -> e.getKey().executionCost() == 0).mapToLong(Map.Entry::getValue).sum());
        assertEquals(Map.of("member", 100L), result.usedStock());
        replay(graph, result, "T", 1);

        // The same 1:1 edge exported as a real recipe must remain expensive.
        var ordinary = competingRoutes(false);
        var control = CraftPlannerV2.plan(ordinary, "T", 1);
        assertEquals(BigInteger.TWO, control.executionCount());
        replay(ordinary, control, "T", 1);
        if (initial.executionCount().compareTo(BigInteger.ONE) > 0)
            assertTrue(FeasibleConsumptionOptimizer.improves(initial, result));
    }

    @Test
    void extendingAFreeTagChainDoesNotRaiseTheMachineExecutionBound() {
        for (int length : new int[] {1, 20}) {
            var builder = CraftGraph.<String>builder().stock("raw", 1)
                    .pattern("T", 1, List.of(CraftInput.of("slow1", 1)))
                    .pattern("slow1", 1, List.of(CraftInput.of("slow2", 1)))
                    .pattern("slow2", 1, List.of(CraftInput.of("raw", 1)))
                    .pattern("member", 1, List.of(CraftInput.of("raw", 1)));
            String previous = "member";
            for (int i = 0; i < length; i++) {
                String tag = "tag" + i;
                builder.pattern(CraftPattern.tagConversion(previous, tag, "virtual-" + i));
                previous = tag;
            }
            var graph = builder.pattern("T", 1, List.of(CraftInput.of(previous, 1))).build();
            var result = CraftPlannerV2.plan(graph, "T", 1);
            assertEquals(BigInteger.TWO, result.executionCount(), "tag chain length=" + length);
            replay(graph, result, "T", 1);
        }
    }

    @Test
    void overlappingTagsCannotDoubleSpendOneMember() {
        var builder = CraftGraph.<String>builder()
                .pattern(CraftPattern.tagConversion("member", "left", "left-tag"))
                .pattern(CraftPattern.tagConversion("member", "right", "right-tag"))
                .pattern("T", 1, List.of(CraftInput.of("left", 1), CraftInput.of("right", 1)));
        var insufficient = builder.stock("member", 1).build();
        assertFalse(CraftPlannerV2.plan(insufficient, "T", 1).feasible());
        var sufficient = builder.stock("member", 2).build();
        var result = CraftPlannerV2.plan(sufficient, "T", 1);
        assertEquals(BigInteger.ONE, result.executionCount());
        replay(sufficient, result, "T", 1);
    }

    @Test
    void aTagBatchMayCombineDifferentMembersWithoutDuplicatingTheirStock() {
        var graph = mixedMembers();
        var result = CraftPlannerV2.plan(graph, "T", 1);
        assertEquals(BigInteger.ONE, result.executionCount());
        assertEquals(Map.of("red", 1L, "blue", 1L), result.usedStock());
        replay(graph, result, "T", 1);
    }

    @Test
    void freeCyclesStillNeedRealStockAndCannotMultiplyIt() {
        var builder = CraftGraph.<String>builder()
                .pattern(CraftPattern.tagConversion("A", "B", "A-to-B"))
                .pattern(CraftPattern.tagConversion("B", "A", "B-to-A"));
        assertFalse(CraftPlannerV2.plan(builder.build(), "B", 1).feasible());
        var seeded = builder.stock("A", 1).build();
        var result = CraftPlannerV2.plan(seeded, "B", 1);
        assertEquals(BigInteger.ZERO, result.executionCount());
        replay(seeded, result, "B", 1);
        assertFalse(CraftPlannerV2.plan(seeded, "B", 2).feasible());
    }

    @Test
    void freeFeedbackInvalidatesTheUnitCostTargetOnlyCertificate() {
        var batch = new CraftPattern<>("T", 2, List.of(CraftInput.of("A", 1)), "make-T");
        var recycle = CraftPattern.tagConversion("T", "A", "recycle-tag");
        var graph = CraftGraph.<String>builder().stock("A", 2).pattern(batch).pattern(recycle).build();
        var incumbent = new CraftPlan<>(true, true, Map.of(batch, 2L), Map.of("A", 2L),
                Map.of(), Map.<String, Long>of(), Map.of("T", 3L, "A", 2L), 2, false);
        var witness = new CraftPlan<>(true, true, Map.of(batch, 2L, recycle, 1L), Map.of("A", 1L),
                Map.of(), Map.<String, Long>of(), Map.of("T", 4L, "A", 2L), 2, false);
        replay(graph, incumbent, "T", 3);
        replay(graph, witness, "T", 3);
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, witness));
        assertFalse(FeasibleConsumptionOptimizer.targetOnlyBound(graph, "T", 3, incumbent));
    }

    @Test
    void graphProjectionsKeepTheTagFlagOnlyWhileAnEdgeIsPresent() {
        var tag = CraftPattern.tagConversion("member", "tag", "virtual");
        var real = new CraftPattern<>("T", 1, List.of(CraftInput.of("member", 1)), "real");
        var graph = CraftGraph.<String>builder().stock("member", 2).pattern(tag).pattern(real).build();
        assertTrue(graph.hasTagConversions());
        assertTrue(graph.withAdditionalStock(Map.of("member", 1L)).hasTagConversions());
        assertTrue(graph.withStockLimits(Map.of("member", 1L)).hasTagConversions());
        assertTrue(graph.withoutStock(Map.of("member", 1L)).hasTagConversions());
        assertTrue(graph.withPatterns(Map.of("tag", List.of(tag))).hasTagConversions());
        assertFalse(graph.withPatterns(Map.of("T", List.of(real))).hasTagConversions());
    }

    @Test
    void cpSatUsesTheSameRealExecutionObjectiveAndRetainsTagBalance() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath(), () -> String.valueOf(
                CpSatIntegerLinearSolver.loadFailure()));
        for (var graph : List.of(competingRoutes(true), mixedMembers())) {
            var result = CpSatRankedFlowSolver.solve(graph, "T", 1);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertEquals(BigInteger.ONE, result.plan().executionCount());
            replay(graph, result.plan(), "T", 1);
        }
        var graph = CraftGraph.<String>builder().stock("member", 1)
                .pattern(CraftPattern.tagConversion("member", "tag", "member-to-tag")).build();
        var result = CpSatRankedFlowSolver.solve(graph, "tag", 1);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertEquals(BigInteger.ZERO, result.plan().executionCount());
        replay(graph, result.plan(), "tag", 1);
    }

    private static CraftGraph<String> competingRoutes(boolean virtual) {
        var edge = virtual ? CraftPattern.tagConversion("member", "tag", "member-to-tag")
                : new CraftPattern<>("tag", 1, List.of(CraftInput.of("member", 1)), "real-conversion");
        return CraftGraph.<String>builder().stock("raw", 1).stock("member", 100)
                .pattern("T", 1, List.of(CraftInput.of("intermediate", 1)))
                .pattern("intermediate", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("tag", 100)))
                .pattern(edge).build();
    }

    private static CraftGraph<String> mixedMembers() {
        return CraftGraph.<String>builder().stock("red", 1).stock("blue", 1)
                .pattern(CraftPattern.tagConversion("red", "tag", "red-member"))
                .pattern(CraftPattern.tagConversion("blue", "tag", "blue-member"))
                .pattern("T", 1, List.of(CraftInput.of("tag", 2))).build();
    }

    private static CraftPlan<String> planWithoutOptimization(CraftGraph<String> graph, String target, long amount) {
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        return CraftPlannerV2.planDetailed(graph, target, amount, session).plan();
    }

    /** Independent enabled-firing replay, including virtual edges as actual material transfers. */
    private static void replay(CraftGraph<String> graph, CraftPlan<String> plan, String target, long amount) {
        assertTrue(plan.supported());
        assertTrue(plan.feasible(), () -> plan.missing().toString());
        assertTrue(plan.missing().isEmpty());
        var pool = new HashMap<String, BigInteger>();
        plan.usedStock().forEach((key, n) -> {
            assertTrue(n >= 0 && n <= graph.stock(key));
            pool.put(key, BigInteger.valueOf(n));
        });
        var pending = new LinkedHashMap<>(plan.firings());
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (var iterator = pending.entrySet().iterator(); iterator.hasNext();) {
                var e = iterator.next();
                var p = e.getKey();
                assertTrue(e.getValue() > 0);
                assertTrue(graph.patternsFor(p.output()).stream().anyMatch(q -> q == p));
                var need = new HashMap<String, BigInteger>();
                p.inputs().forEach(i -> need.merge(i.key(), i.exactAmount(), BigInteger::add));
                if (need.entrySet().stream().anyMatch(i -> pool.getOrDefault(i.getKey(), BigInteger.ZERO)
                        .compareTo(i.getValue()) < 0)) continue;
                need.forEach((key, n) -> pool.merge(key, n.negate(), BigInteger::add));
                pool.merge(p.output(), p.exactOutputAmount(), BigInteger::add);
                if (e.getValue() == 1) iterator.remove();
                else e.setValue(e.getValue() - 1);
                progress = true;
            }
            assertTrue(progress, () -> "No executable next firing: " + pending);
        }
        assertTrue(pool.getOrDefault(target, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
