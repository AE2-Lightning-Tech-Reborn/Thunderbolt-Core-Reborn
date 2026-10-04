package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UpstreamBatchOptimizerTest {
    @Test
    void increasingUpstreamProductionCanReduceTotalExecutions() {
        var fixture = singleUpstream(1, 1, 10);
        var incumbent = singleRoute(fixture, 0);
        assertEquals(6, executions(incumbent));
        assertEquals(Map.of("raw", 2L), incumbent.usedStock());
        var oracle = exactMinimum(fixture);
        assertEquals(5, oracle.executions());
        assertEquals(Map.of("raw", 3L), oracle.usedStock());
        var currentPlanner = CraftPlannerV2.plan(fixture.graph(), "T", 4);
        assertTrue(currentPlanner.feasible());
        System.out.println("Upstream regression: current planner=" + executions(currentPlanner)
                + " executions; independent exact minimum=" + oracle.executions());

        // Existing pair allocation cannot produce the extra A required by the optimal mix.
        assertNull(OrdinaryBatchOptimizer.tryImprove(fixture.graph(), "T", 4, incumbent,
                fixture.keyOrder(), work -> true, () -> true));
        var result = improve(fixture, incumbent);

        assertNotNull(result);
        assertEquals(oracle.executions(), executions(result));
        assertEquals(Map.of(fixture.upstream().getFirst(), 3L,
                fixture.routes().getFirst(), 1L, fixture.routes().get(1), 1L), result.firings());
        assertEquals(oracle.usedStock(), result.usedStock());
        assertCertified(fixture, incumbent, result, oracle);
        assertEquals(Map.of(fixture.upstream().getFirst(), 2L, fixture.routes().getFirst(), 4L),
                incumbent.firings());
    }

    @Test
    void oneMoreDownstreamExecutionCanSaveMoreUpstreamExecutions() {
        var graph = CraftGraph.<String>builder().stock("raw", 10)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 3, List.of(CraftInput.of("A", 4))).build();
        var fixture = fixture(graph, 5, "A");
        var incumbent = singleRoute(fixture, 0);
        var oracle = exactMinimum(fixture);
        assertEquals(10, executions(incumbent));
        assertEquals(9, oracle.executions());
        // The two-big-batch local optimum needs A=8 and ten total executions; its adjacent
        // three-batch mix needs A=6, so it saves one execution across the complete DAG.
        var result = improve(fixture, incumbent);
        assertNotNull(result);
        assertEquals(9, executions(result));
        assertEquals(Map.of(graph.patternsFor("A").getFirst(), 6L,
                graph.patternsFor("T").getFirst(), 2L, graph.patternsFor("T").get(1), 1L), result.firings());
        assertEquals(Map.of("raw", 6L), result.usedStock());
        assertCertified(fixture, incumbent, result, oracle);
    }

    @Test
    void expandingOneIntermediateAndTrimmingAnotherAggregatesSharedRawDemand() {
        var graph = CraftGraph.<String>builder().stock("raw", 10)
                .pattern("A", 2, List.of(CraftInput.of("raw", 1)))
                .pattern("B", 3, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)))
                .pattern("T", 3, List.of(CraftInput.of("A", 2), CraftInput.of("A", 2),
                        CraftInput.of("B", 1))).build();
        var fixture = fixture(graph, 4, "A", "B");
        var incumbent = singleRoute(fixture, 0);
        var oracle = exactMinimum(fixture);
        assertEquals(8, executions(incumbent));
        assertEquals(6, oracle.executions());

        var result = improve(fixture, incumbent);

        assertNotNull(result);
        assertEquals(6, executions(result));
        assertEquals(3, result.firings().get(graph.patternsFor("A").getFirst()));
        assertEquals(1, result.firings().get(graph.patternsFor("B").getFirst()));
        assertEquals(Map.of("raw", 4L), result.usedStock());
        assertEquals(5, result.grossDemand().get("A"));
        assertEquals(2, result.grossDemand().get("B"));
        assertCertified(fixture, incumbent, result, oracle);
    }

    @Test
    void scaledPairFamilyAgreesWithIndependentExactMinimum() {
        for (int scale = 1; scale <= 24; scale++) {
            var fixture = singleUpstream(scale, 1 + scale % 3, 30);
            var incumbent = singleRoute(fixture, 0);
            var oracle = exactMinimum(fixture);

            var result = improve(fixture, incumbent);

            assertNotNull(result, "scale=" + scale);
            assertEquals(oracle.executions(), executions(result), "scale=" + scale);
            assertCertified(fixture, incumbent, result, oracle);
        }
    }

    @Test
    void expandedUpstreamDemandCannotSpendMoreRawThanTheSnapshotContains() {
        var fixture = singleUpstream(1, 1, 2);
        var incumbent = singleRoute(fixture, 0);
        assertEquals(6, exactMinimum(fixture).executions());

        assertNull(improve(fixture, incumbent));
        assertEquals(Map.of("raw", 2L), incumbent.usedStock());
    }

    @Test
    void independentlyAffordableUpstreamExpansionsCannotDoubleSpendSharedRaw() {
        var graph = CraftGraph.<String>builder().stock("raw", 6)
                .pattern("A", 2, List.of(CraftInput.of("raw", 2)))
                .pattern("B", 3, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)))
                .pattern("T", 3, List.of(CraftInput.of("A", 4), CraftInput.of("B", 1))).build();
        var fixture = fixture(graph, 4, "A", "B");
        var incumbent = singleRoute(fixture, 0);
        assertEquals(8, executions(incumbent));
        assertEquals(8, exactMinimum(fixture).executions());
        // The relaxed capacities each permit A=6 and B=3, but together they cost seven raw.
        assertNull(improve(fixture, incumbent));
        assertEquals(Map.of("raw", 6L), incumbent.usedStock());
    }

    @Test
    void trillionScaleAmountsKeepTheSameSmallCountWitnessAndBoundedWork() {
        var fixture = singleUpstream(1_000_000_000_000L, 1_000_000_000_000L, 10_000_000_000_000L);
        var incumbent = singleRoute(fixture, 0);
        var work = new AtomicInteger();
        var result = UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), incumbent,
                fixture.keyOrder(), cost -> { work.addAndGet(cost); return true; }, () -> true);
        assertNotNull(result);
        assertEquals(5, executions(result));
        assertEquals(Map.of("raw", 3_000_000_000_000L), result.usedStock());
        assertCertified(fixture, incumbent, result, exactMinimum(fixture));
        assertTrue(work.get() < 1_000, "amount scaling must not cause per-unit iteration");
    }

    @Test
    void equalLargeBoxedCountsOnlySpendAProbeForTheDistinctNeighbor() {
        var graph = CraftGraph.<String>builder().stock("raw", 300)
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1))).build();
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(graph.patternsFor("T").getFirst(), 300L), "T", 300);
        assertNotNull(incumbent);
        var probes = new AtomicInteger();
        assertNull(UpstreamBatchOptimizer.tryImprove(graph, "T", 300, incumbent, List.of("T", "raw"),
                work -> true, () -> { probes.incrementAndGet(); return true; }));
        // The original 300/0 candidate is numerically unchanged even outside Long's cache;
        // only the 299/1 neighbor may enter certification.
        assertEquals(1, probes.get());
    }

    @Test
    void intermediateInventoryIsCountedOnceWhenExpandingProduction() {
        var original = singleUpstream(1, 1, 10);
        var fixture = new Fixture(original.graph().withAdditionalStock(Map.of("A", 1L)),
                original.amount(), original.routes(), original.upstream(), original.keyOrder());
        var incumbent = singleRoute(fixture, 0);
        var oracle = exactMinimum(fixture);

        var result = improve(fixture, incumbent);

        assertNotNull(result);
        assertEquals(oracle.executions(), executions(result));
        assertEquals(4, executions(result));
        assertEquals(Map.of("raw", 2L, "A", 1L), result.usedStock());
        assertCertified(fixture, incumbent, result, oracle);
    }

    @Test
    void randomTwoLayerGraphsWithTwoOrThreeRoutesReturnOnlyIndependentOracleValidImprovements() {
        var random = new Random(3102026);
        int feasibleCases = 0, witnesses = 0, opportunities = 0, improvements = 0, exactHits = 0;
        for (int caseIndex = 0; caseIndex < 160; caseIndex++) {
            var builder = CraftGraph.<String>builder().stock("raw", 24)
                    .stock("A", random.nextInt(3)).stock("B", random.nextInt(3))
                    .pattern("A", 1 + random.nextInt(4), List.of(CraftInput.of("raw", 1 + random.nextInt(3))))
                    .pattern("B", 1 + random.nextInt(4), List.of(CraftInput.of("raw", 1 + random.nextInt(3))));
            int routeCount = 2 + random.nextInt(2);
            for (int route = 0; route < routeCount; route++) {
                var inputs = new ArrayList<CraftInput<String>>();
                inputs.add(CraftInput.of("A", 1 + random.nextInt(4)));
                if (random.nextBoolean()) inputs.add(CraftInput.of("B", 1 + random.nextInt(3)));
                builder.pattern("T", 1 + random.nextInt(4), inputs);
            }
            var fixture = fixture(builder.build(), 1 + random.nextInt(6), "A", "B");
            var oracle = exactMinimum(fixture);
            if (oracle == null) continue;
            feasibleCases++;
            for (int route = 0; route < routeCount; route++) {
                var incumbent = singleRouteOrNull(fixture, route);
                if (incumbent == null) continue;
                witnesses++;
                if (executions(incumbent) > oracle.executions()) opportunities++;
                var result = improve(fixture, incumbent);
                if (result == null) continue;
                improvements++;
                if (executions(result) == oracle.executions()) exactHits++;
                assertCertified(fixture, incumbent, result, oracle);
            }
        }
        assertTrue(improvements > 0, "the random audit must exercise accepted candidates");
        System.out.println("Upstream two-layer oracle audit: generated=160; feasible=" + feasibleCases
                + "; single-route witnesses=" + witnesses + "; execution improvement opportunities=" + opportunities
                + "; accepted improvements=" + improvements + "; accepted at exact minimum=" + exactHits);
    }

    @Test
    void refusedWorkAndCertificationProbesLeaveTheIncumbentUntouched() {
        var fixture = singleUpstream(1, 1, 10);
        var incumbent = singleRoute(fixture, 0);
        var originalFirings = Map.copyOf(incumbent.firings());
        var fullWork = new AtomicInteger();
        var fullProbes = new AtomicInteger();
        assertNotNull(UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), incumbent,
                fixture.keyOrder(), work -> {
                    assertTrue(work > 0);
                    fullWork.addAndGet(work);
                    return true;
                }, () -> { fullProbes.incrementAndGet(); return true; }));
        assertTrue(fullWork.get() > 0);
        assertTrue(fullProbes.get() > 0);
        var oracle = exactMinimum(fixture);
        for (int limit : new int[] {0, 1, fullWork.get() / 3, fullWork.get() - 1}) {
            int[] remaining = {limit};
            var result = UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), incumbent,
                    fixture.keyOrder(), work -> {
                        assertTrue(work > 0);
                        if (work > remaining[0]) return false;
                        remaining[0] -= work;
                        return true;
                    }, () -> true);
            if (result != null) assertCertified(fixture, incumbent, result, oracle);
            assertTrue(remaining[0] >= 0);
        }
        assertNull(UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), incumbent,
                fixture.keyOrder(), work -> false,
                () -> { throw new AssertionError("refused work entered certification"); }));
        var deniedProbes = new AtomicInteger();
        assertNull(UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), incumbent,
                fixture.keyOrder(), work -> true, () -> { deniedProbes.incrementAndGet(); return false; }));
        assertTrue(deniedProbes.get() > 0);
        assertEquals(originalFirings, incumbent.firings());
        assertEquals(Map.of("raw", 2L), incumbent.usedStock());
    }

    @Test
    void callerCancellationAndOptionalDeadlineRemainVisible() {
        var fixture = singleUpstream(1, 1, 10);
        var incumbent = singleRoute(fixture, 0);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> improve(fixture, incumbent));
        } finally {
            Thread.interrupted();
        }
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> improve(fixture, incumbent));
        }
    }

    @Test
    void cyclicAlternativeIsNotAnOrdinaryDagCandidate() {
        var graph = CraftGraph.<String>builder().stock("raw", 10)
                .pattern("A", 2, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 2, List.of(CraftInput.of("T", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 3, List.of(CraftInput.of("A", 4))).build();
        var incumbent = MaterialDagReplay.tryPlan(graph,
                Map.of(graph.patternsFor("A").getFirst(), 2L, graph.patternsFor("T").getFirst(), 4L), "T", 4);
        assertNotNull(incumbent);
        assertNull(UpstreamBatchOptimizer.tryImprove(graph, "T", 4, incumbent, List.of("T", "A", "raw"),
                work -> true, () -> { throw new AssertionError("cyclic graph entered certification"); }));
    }

    @Test
    void moreThanSixteenReachablePatternsDeclineBeforeCertification() {
        var builder = CraftGraph.<String>builder().stock("raw", 10)
                .pattern("T", 1, List.of(CraftInput.of("A0", 1)))
                .pattern("T", 3, List.of(CraftInput.of("A0", 4)));
        var order = new ArrayList<>(List.of("T"));
        for (int index = 0; index < 15; index++) {
            builder.pattern("A" + index, index == 14 ? 2 : 1,
                    List.of(CraftInput.of(index == 14 ? "raw" : "A" + (index + 1), 1)));
            order.add("A" + index);
        }
        order.add("raw");
        var graph = builder.build();
        var counts = new IdentityHashMap<CraftPattern<String>, Long>();
        counts.put(graph.patternsFor("T").getFirst(), 4L);
        for (int index = 0; index < 15; index++) counts.put(graph.patternsFor("A" + index).getFirst(), index == 14 ? 2L : 4L);
        var incumbent = MaterialDagReplay.tryPlan(graph, counts, "T", 4);
        assertNotNull(incumbent);
        assertNull(UpstreamBatchOptimizer.tryImprove(graph, "T", 4, incumbent, order, work -> true,
                () -> { throw new AssertionError("oversized graph entered certification"); }));
    }

    @Test
    void statefulOrByproductWitnessesAreOutsideTheHelperScope() {
        var specialInputs = List.of(CraftInput.returned("tool", 1),
                CraftInput.finiteUse("tool", 1, 5), CraftInput.consumedReturning("tool", 1, "empty"),
                CraftInput.returnedFrom("tool", 1, new ReusableStockSource("host", "pool")));
        for (var special : specialInputs) {
            var graph = CraftGraph.<String>builder().stock("raw", 10).stock("tool", 10)
                    .reusableStock("host", "tool", 1)
                    .pattern("A", 2, List.of(CraftInput.of("raw", 1), special))
                    .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                    .pattern("T", 3, List.of(CraftInput.of("A", 4))).build();
            var session = new CraftPlannerV2.PlanningSession<String>();
            session.optimizeFeasible = false;
            var incumbent = CraftPlannerV2.planDetailed(graph, "T", 4, session).plan();
            assertTrue(incumbent.feasible());
            assertNull(UpstreamBatchOptimizer.tryImprove(graph, "T", 4, incumbent,
                    List.of("T", "A", "raw", "tool", "empty"), work -> true,
                    () -> { throw new AssertionError("stateful graph entered certification"); }));
        }
        var graph = CraftGraph.<String>builder().stock("raw", 10)
                .pattern("A", 2, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("side", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 3, List.of(CraftInput.of("A", 4))).build();
        var incumbent = MaterialDagReplay.tryPlan(graph,
                Map.of(graph.patternsFor("A").getFirst(), 2L, graph.patternsFor("T").getFirst(), 4L), "T", 4);
        assertNotNull(incumbent);
        assertNull(UpstreamBatchOptimizer.tryImprove(graph, "T", 4, incumbent,
                List.of("T", "A", "raw", "side"), work -> true,
                () -> { throw new AssertionError("byproduct graph entered certification"); }));
    }

    private static Fixture singleUpstream(long scale, long rawCost, long rawStock) {
        var graph = CraftGraph.<String>builder().stock("raw", rawStock)
                .pattern("A", 2 * scale, List.of(CraftInput.of("raw", rawCost)))
                .pattern("T", scale, List.of(CraftInput.of("A", scale)))
                .pattern("T", 3 * scale, List.of(CraftInput.of("A", 4 * scale))).build();
        return fixture(graph, 4 * scale, "A");
    }

    private static Fixture fixture(CraftGraph<String> graph, long amount, String... intermediates) {
        var upstream = new ArrayList<CraftPattern<String>>();
        var order = new ArrayList<>(List.of("T"));
        for (String key : intermediates) {
            assertEquals(1, graph.patternsFor(key).size(), "the independent oracle assumes one upstream producer");
            upstream.add(graph.patternsFor(key).getFirst());
            order.add(key);
        }
        order.add("raw");
        return new Fixture(graph, amount, graph.patternsFor("T"), upstream, order);
    }

    private static CraftPlan<String> singleRoute(Fixture fixture, int route) {
        var result = singleRouteOrNull(fixture, route);
        assertNotNull(result);
        return result;
    }

    private static CraftPlan<String> singleRouteOrNull(Fixture fixture, int route) {
        long[] counts = new long[fixture.routes().size()];
        counts[route] = ceil(Math.max(0, fixture.amount() - fixture.graph().stock("T")),
                fixture.routes().get(route).outputAmount());
        var expected = evaluate(fixture, counts);
        if (expected == null) return null;
        var result = MaterialDagReplay.tryPlan(fixture.graph(), expected.counts(), "T", fixture.amount());
        assertNotNull(result);
        assertEquals(expected.usedStock(), result.usedStock());
        return result;
    }

    private static CraftPlan<String> improve(Fixture fixture, CraftPlan<String> incumbent) {
        return UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", fixture.amount(), incumbent,
                fixture.keyOrder(), work -> true, () -> true);
    }

    /** Exhaustive target-route vectors plus exact single-producer upstream ceilings; no planner or replay calls. */
    private static Oracle exactMinimum(Fixture fixture) {
        return enumerate(fixture, new long[fixture.routes().size()], 0, null);
    }

    private static Oracle enumerate(Fixture fixture, long[] counts, int route, Oracle best) {
        if (route == counts.length) {
            var candidate = evaluate(fixture, counts);
            if (candidate == null) return best;
            return best == null || candidate.executions() < best.executions()
                    || candidate.executions() == best.executions() && stockCount(candidate) < stockCount(best)
                    ? candidate : best;
        }
        long limit = ceil(Math.max(0, fixture.amount() - fixture.graph().stock("T")),
                fixture.routes().get(route).outputAmount());
        for (long count = 0; count <= limit; count++) {
            counts[route] = count;
            best = enumerate(fixture, counts, route + 1, best);
        }
        return best;
    }

    private static Oracle evaluate(Fixture fixture, long[] targetCounts) {
        var counts = new IdentityHashMap<CraftPattern<String>, Long>();
        var demanded = new HashMap<String, Long>();
        var produced = new HashMap<String, Long>();
        demanded.put("T", fixture.amount());
        for (int index = 0; index < targetCounts.length; index++) {
            long count = targetCounts[index];
            if (count > 0) add(fixture.routes().get(index), count, counts, demanded, produced);
        }
        if (produced.getOrDefault("T", 0L) + fixture.graph().stock("T") < fixture.amount()) return null;
        for (var upstream : fixture.upstream()) {
            long count = ceil(Math.max(0, demanded.getOrDefault(upstream.output(), 0L)
                    - fixture.graph().stock(upstream.output())), upstream.outputAmount());
            if (count > 0) add(upstream, count, counts, demanded, produced);
        }
        var used = new HashMap<String, Long>();
        for (var entry : demanded.entrySet()) {
            long needed = Math.max(0, entry.getValue() - produced.getOrDefault(entry.getKey(), 0L));
            if (needed > fixture.graph().stock(entry.getKey())) return null;
            if (needed > 0) used.put(entry.getKey(), needed);
        }
        return new Oracle(counts.values().stream().mapToLong(Long::longValue).sum(), Map.copyOf(counts), Map.copyOf(used));
    }

    private static void add(CraftPattern<String> pattern, long count, Map<CraftPattern<String>, Long> counts,
            Map<String, Long> demanded, Map<String, Long> produced) {
        counts.put(pattern, count);
        produced.merge(pattern.output(), pattern.outputAmount() * count, Long::sum);
        for (var input : pattern.inputs()) demanded.merge(input.key(), input.amount() * count, Long::sum);
    }

    private static void assertCertified(Fixture fixture, CraftPlan<String> incumbent, CraftPlan<String> result,
            Oracle oracle) {
        assertTrue(result.feasible());
        assertTrue(result.missing().isEmpty());
        assertTrue(result.usedReusableStock().isEmpty());
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
        assertTrue(executions(result) >= oracle.executions(), "a returned witness cannot beat the independent exact minimum");
        long[] counts = fixture.routes().stream().mapToLong(pattern -> result.firings().getOrDefault(pattern, 0L)).toArray();
        var independent = evaluate(fixture, counts);
        assertNotNull(independent, "the candidate must satisfy the inventory snapshot independently of replay");
        assertEquals(independent.counts(), result.firings(), "upstream counts must match propagated demand");
        assertEquals(independent.usedStock(), result.usedStock());
        var certified = MaterialDagReplay.tryPlan(fixture.graph(), result.firings(), "T", fixture.amount());
        assertNotNull(certified);
        assertEquals(certified.usedStock(), result.usedStock());
        assertEquals(certified.grossDemand(), result.grossDemand());
    }

    private static long ceil(long amount, long output) { return amount / output + (amount % output == 0 ? 0 : 1); }
    private static long stockCount(Oracle oracle) { return oracle.usedStock().values().stream().mapToLong(Long::longValue).sum(); }
    private static long executions(CraftPlan<?> plan) { return plan.firings().values().stream().mapToLong(Long::longValue).sum(); }

    private record Fixture(CraftGraph<String> graph, long amount, List<CraftPattern<String>> routes,
            List<CraftPattern<String>> upstream, List<String> keyOrder) {}
    private record Oracle(long executions, Map<CraftPattern<String>, Long> counts, Map<String, Long> usedStock) {}
}
