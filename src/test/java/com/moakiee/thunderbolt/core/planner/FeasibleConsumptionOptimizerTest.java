package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeasibleConsumptionOptimizerTest {
    @Test
    void configuredAllowanceMatchesTheRetainedForgeJvmOverride() {
        assertEquals(Math.max(1_000_000L,
                Long.getLong("thunderbolt.maxConsumptionOptimizationNanos", 2_800_000_000L)),
                FeasibleConsumptionOptimizer.MAX_NANOS);
    }

    @Test
    void savingStockNeverJustifiesMoreMachineExecutions() {
        var batch = new CraftPattern<>("T", 10, List.of(CraftInput.of("raw", 10)), null);
        var unit = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), null);
        var initial = plan(batch, Map.of("raw", 10L));
        var cheaperButSlower = new CraftPlan<String>(true, true, Map.of(unit, 2L),
                Map.of("raw", 2L), Map.of(), Map.of(), Map.of(), 0, false);
        assertFalse(FeasibleConsumptionOptimizer.improves(initial, cheaperButSlower));
        assertTrue(FeasibleConsumptionOptimizer.improves(cheaperButSlower, plan(unit, Map.of("raw", 1L))));
    }

    @Test
    void invalidProbesNeverReplaceTheCertifiedWitness() {
        var graph = batchGraph(1);
        var initial = baseline(graph, "T", 1);
        var calls = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32, candidate -> {
            calls.incrementAndGet();
            return new CraftPlan<>(true, false, Map.of(), Map.of(), Map.of(), Map.of("T", 1L),
                    Map.of(), 0, false);
        }, work -> false); // Isolate oracle rejection from independently certified batch search.
        assertSame(initial, result.plan());
        assertEquals(0, result.improvements());
        assertEquals(calls.get(), result.probes());
        assertTrue(result.probes() > 0 && result.probes() <= FeasibleConsumptionOptimizer.MAX_PROBES);
    }

    @Test
    void exhaustedProbeBudgetStopsWithoutReplayingTheOracle() {
        var graph = batchGraph(1);
        var initial = baseline(graph, "T", 1);
        var calls = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32, candidate -> {
            calls.incrementAndGet();
            return null;
        });
        assertSame(initial, result.plan());
        assertEquals(1, calls.get());
        assertEquals(1, result.probes());
        assertEquals(0, result.improvements());
    }

    @Test
    void overdrawnCandidateCannotBecomeTheNewIncumbent() {
        var graph = batchGraph(1);
        var initial = baseline(graph, "T", 1);
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32,
                candidate -> plan(graph.patternsFor("T").get(1), Map.of("raw", 11L)),
                work -> false); // A separate valid batch witness must not mask oracle rejection.
        assertSame(initial, result.plan());
        assertEquals(0, result.improvements());
    }

    @Test
    void indexCacheIsCalculationScopedAndCancellationDoesNotPublishPartialState() {
        var graph = batchGraph(1);
        var cache = new FeasibleConsumptionOptimizer.IndexCache<String>();
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> cache.get(graph, "T"));
        }
        var index = cache.get(graph, "T");
        assertNotNull(index);
        assertSame(index, cache.get(graph, "T"));
        assertNotSame(index, cache.get(graph.withStockLimits(Map.of("raw", 2L)), "T"));
        assertNotSame(index, cache.get(graph, "raw"));
        assertNotSame(index, cache.get(graph, "T"));
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertThrows(PlanningCancellation.OptionalWorkLimit.class, () -> cache.get(graph, "T"));
        }
    }

    @Test
    void cachedIndexRecomputesQuantityAndStockPropagation() {
        var graph = batchGraph(1);
        var cache = new FeasibleConsumptionOptimizer.IndexCache<String>();
        for (long amount : new long[] {1, 2, 1}) {
            var initial = baseline(graph, "T", amount);
            var uncached = FeasibleConsumptionOptimizer.optimize(graph, "T", amount, initial,
                    32, candidate -> baseline(candidate, "T", amount));
            var cached = FeasibleConsumptionOptimizer.optimize(graph, "T", amount, initial,
                    32, candidate -> baseline(candidate, "T", amount), cache);
            assertEquals(uncached.plan().usedStock(), cached.plan().usedStock());
            assertEquals(executions(uncached.plan()), executions(cached.plan()));
            assertBalance(graph, cached.plan(), "T", amount);
        }
    }

    @Test
    void cachedIndexDoesNotReuseMixedBatchWorkOrBudgetRejection() {
        var graph = mixedBatchGraph();
        var initial = baseline(graph, "T", 8);
        var cache = new FeasibleConsumptionOptimizer.IndexCache<String>();
        var rejectedWork = new AtomicInteger();
        var denied = FeasibleConsumptionOptimizer.optimize(graph, "T", 8, initial, 32,
                candidate -> baseline(candidate, "T", 8), cache, units -> {
                    rejectedWork.addAndGet(units);
                    return false;
                });
        assertTrue(rejectedWork.get() > 0);
        assertEquals(3, executions(denied.plan()));
        assertEquals(9, denied.plan().usedStock().get("raw"));
        assertBalance(graph, denied.plan(), "T", 8);

        for (int repeat = 0; repeat < 2; repeat++) {
            var work = new AtomicInteger();
            var accepted = FeasibleConsumptionOptimizer.optimize(graph, "T", 8, initial, 32,
                    candidate -> baseline(candidate, "T", 8), cache, units -> {
                        work.addAndGet(units);
                        return true;
                    });
            assertTrue(work.get() > 0);
            assertEquals(3, executions(accepted.plan()));
            assertEquals(7, accepted.plan().usedStock().get("raw"));
            assertTrue(accepted.probes() <= FeasibleConsumptionOptimizer.MAX_PROBES);
            assertBalance(graph, accepted.plan(), "T", 8);
        }
    }

    @Test
    void wideGraphsStillOptimizeWhenOnlyTheLocalProbeFitsTheSearchBudget() {
        var builder = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)));
        // Unfunded alternatives enlarge the reachable graph, but cannot fire inside the
        // incumbent's stock limits. A useful probe only needs the direct raw -> T route.
        for (int i = 0; i < 6_000; i++) {
            builder.pattern("T", 1, List.of(CraftInput.of("unavailable" + i, 1)));
        }
        var graph = builder.build();
        assertTrue(CraftPlannerV2.reachableWorkEstimate(graph, "T") > 16_384);
        var initial = baseline(graph, "T", 1);
        assertEquals(2, executions(initial));
        var result = CraftPlannerV2.planDetailed(graph, "T", 1, CraftPlannerV2.DEFAULT_VISIT_CAP, 256);
        assertTrue(result.plan().feasible(), result::toString);
        assertEquals(1, executions(result.plan()));
        assertEquals(initial.usedStock(), result.plan().usedStock());
        assertTrue(result.diagnostics().consumptionOptimizationProbes() > 0);
        assertBalance(graph, result.plan(), "T", 1);
    }

    @Test
    void insufficientSearchBudgetRetainsTheCertifiedIncumbent() {
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1))).build();
        var result = CraftPlannerV2.planDetailed(graph, "T", 1, CraftPlannerV2.DEFAULT_VISIT_CAP, 1);
        assertTrue(result.plan().feasible());
        assertEquals(2, executions(result.plan()));
        assertEquals(0, result.diagnostics().consumptionOptimizationImprovements());
        assertBalance(graph, result.plan(), "T", 1);
    }

    @Test
    void narrowAndWideSlotsPreserveIndexOrderAndExactMergedAmounts() throws ReflectiveOperationException {
        var source = new com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource("host", "pool");
        for (int inputSlots : new int[] {16, 32, 33, 257}) {
            // The inferred container remainder adds one more normalized side-output slot.
            for (int sideSlots : new int[] {15, 31, 32, 256}) {
                var inputs = new ArrayList<>(List.of(CraftInput.returned("held", 1),
                        CraftInput.of("rawA", Sat.SAT / 2 + 1), CraftInput.returnedFrom("private", 1, source),
                        CraftInput.consumedReturning("filled", 2, "container"), CraftInput.of("rawB", 3),
                        CraftInput.of("rawA", Sat.SAT / 2 + 1)));
                while (inputs.size() < inputSlots) inputs.add(inputs.size() % 3 == 0
                        ? CraftInput.returned("held", 1) : CraftInput.of("rawB", 2));
                var sides = new ArrayList<>(List.of(CraftOutput.of("T", 2), CraftOutput.of("side", 1)));
                while (sides.size() < sideSlots) sides.add(CraftOutput.of(
                        sides.size() % 3 == 0 ? "side" : "side" + sides.size(), 1));
                var wide = new CraftPattern<>("T", 1, inputs, sides, null);
                var other = new CraftPattern<>("T", 1,
                        List.of(CraftInput.of("rawB", 1), CraftInput.of("rawA", 2)), null);
                var graph = CraftGraph.<String>builder().pattern(wide).pattern(wide).pattern(other).build();
                assertIndexSlots(indexFor(graph, "T"), List.of(wide, other));
            }
        }
    }

    @Test
    void cancellationInsideWideInputAndSideLoopsDoesNotWaitForTheNextItem() throws ReflectiveOperationException {
        for (boolean sideOnly : new boolean[] {false, true}) {
            var target = new IndexCancelKey(0);
            var inputs = new ArrayList<CraftInput<IndexCancelKey>>();
            var sides = new ArrayList<CraftOutput<IndexCancelKey>>();
            for (int i = 1; i <= 4096; i++) {
                var key = new IndexCancelKey(i);
                if (sideOnly) sides.add(CraftOutput.of(key, 1));
                else inputs.add(CraftInput.of(key, 1));
            }
            var graph = CraftGraph.<IndexCancelKey>builder().pattern(target, 1, inputs, sides).build();
            IndexCancelKey.calls = 0;
            IndexCancelKey.armed = true;
            try {
                var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> indexFor(graph, target), "sideOnly=" + sideOnly);
                assertInstanceOf(CancellationException.class, failure.getCause());
                assertTrue(IndexCancelKey.calls < 1024,
                        "cancellation was delayed across wide slots: " + IndexCancelKey.calls);
            } finally {
                IndexCancelKey.armed = false;
                Thread.interrupted();
            }
        }
    }

    private static final class IndexCancelKey {
        private final int id;
        private static int calls;
        private static boolean armed;

        private IndexCancelKey(int id) { this.id = id; }

        @Override
        public int hashCode() {
            if (armed && ++calls == 100) Thread.currentThread().interrupt();
            return id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IndexCancelKey key && id == key.id;
        }
    }

    private static Object indexFor(CraftGraph<?> graph, Object target) throws ReflectiveOperationException {
        var index = Class.forName(FeasibleConsumptionOptimizer.class.getName() + "$Index");
        var build = index.getDeclaredMethod("build", CraftGraph.class, Object.class);
        build.setAccessible(true);
        return build.invoke(null, graph, target);
    }

    private static Object indexField(Object index, String name) throws ReflectiveOperationException {
        var field = index.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(index);
    }

    private static List<?> indexKeyVector(Object index, String name) throws ReflectiveOperationException {
        var keys = (List<?>) indexField(index, "keys");
        return Arrays.stream((int[]) indexField(index, name)).mapToObj(keys::get).toList();
    }

    /** Independent collection/BigInteger oracle for the index's compact slot arrays. */
    private static void assertIndexSlots(Object index, List<CraftPattern<String>> patterns)
            throws ReflectiveOperationException {
        var keys = new LinkedHashSet<String>();
        keys.add("T");
        var needs = new ArrayList<String>();
        var uses = new ArrayList<String>();
        var amounts = new ArrayList<Long>();
        var sides = new ArrayList<String>();
        var needStart = new ArrayList<>(List.of(0));
        var useStart = new ArrayList<>(List.of(0));
        var sideStart = new ArrayList<>(List.of(0));
        var produced = new ArrayList<Long>();
        for (var pattern : patterns) {
            var sideKeys = new LinkedHashSet<String>();
            var totalOutput = pattern.exactOutputAmount();
            for (var side : pattern.byproducts()) {
                keys.add(side.key());
                if (side.key().equals("T")) totalOutput = totalOutput.add(side.exactAmount());
                else sideKeys.add(side.key());
            }
            var needKeys = new LinkedHashSet<String>();
            var consumed = new LinkedHashMap<String, BigInteger>();
            for (var input : pattern.inputs()) {
                keys.add(input.key());
                if (input.reusableStockSource() == null) needKeys.add(input.key());
                if (!input.returned()) consumed.merge(input.key(), input.exactAmount(), BigInteger::add);
            }
            needs.addAll(needKeys);
            consumed.forEach((key, value) -> {
                uses.add(key);
                amounts.add(value.min(BigInteger.valueOf(Sat.SAT)).longValueExact());
            });
            sides.addAll(sideKeys);
            needStart.add(needs.size());
            useStart.add(uses.size());
            sideStart.add(sides.size());
            produced.add(totalOutput.min(BigInteger.valueOf(Sat.SAT)).longValueExact());
        }
        assertEquals(new ArrayList<>(keys), indexField(index, "keys"));
        assertEquals(patterns, indexField(index, "patterns"));
        assertEquals(Map.of(patterns.get(0), 0, patterns.get(1), 1), indexField(index, "patternIds"));
        assertEquals(true, indexField(index, "choices"));
        assertEquals(List.of("T", "T"), indexKeyVector(index, "out"));
        assertArrayEquals(produced.stream().mapToLong(Long::longValue).toArray(), (long[]) indexField(index, "outAmount"));
        assertArrayEquals(new boolean[] {true, false}, (boolean[]) indexField(index, "stateful"));
        assertEquals(needs, indexKeyVector(index, "needKey"));
        assertEquals(uses, indexKeyVector(index, "useKey"));
        assertEquals(sides, indexKeyVector(index, "sideKey"));
        assertArrayEquals(amounts.stream().mapToLong(Long::longValue).toArray(), (long[]) indexField(index, "useAmount"));
        assertArrayEquals(needStart.stream().mapToInt(Integer::intValue).toArray(), (int[]) indexField(index, "needStart"));
        assertArrayEquals(useStart.stream().mapToInt(Integer::intValue).toArray(), (int[]) indexField(index, "useStart"));
        assertArrayEquals(sideStart.stream().mapToInt(Integer::intValue).toArray(), (int[]) indexField(index, "sideStart"));
        int[] producerStart = new int[keys.size() + 1];
        Arrays.fill(producerStart, 1, producerStart.length, 2);
        assertArrayEquals(producerStart, (int[]) indexField(index, "producerStart"));
        assertArrayEquals(new int[] {0, 1}, (int[]) indexField(index, "producer"));
    }

    @Test
    void targetAndStockLowerBoundsSkipBothOracleAndMixedBatchWork() {
        var graph = CraftGraph.<String>builder().stock("raw", 100)
                .pattern("T", 1_000, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 500, List.of(CraftInput.of("raw", 1))).build();
        var initial = MaterialDagReplay.tryPlan(graph, Map.of(graph.patternsFor("T").get(0), 100L), "T", 100_000);
        assertNotNull(initial);
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 100_000, initial, 32,
                candidate -> { throw new AssertionError("optimal target-only plan entered oracle"); },
                work -> { throw new AssertionError("optimal target-only plan entered mixed-batch work"); });
        assertSame(initial, result.plan());
        assertEquals(0, result.probes());
    }

    @Test
    void wholeInventoryPolicyReachingBothBoundsStopsBeforeFurtherSearch() {
        var graph = keroseneGraph(100);
        var initial = baseline(graph, "kerosene", 100_000);
        var calls = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(graph, "kerosene", 100_000, initial, 32,
                candidate -> {
                    assertEquals(1, calls.incrementAndGet(), "the first policy already reaches both bounds");
                    return baseline(candidate, "kerosene", 100_000);
                }, work -> { throw new AssertionError("certified target-only policy entered mixed-batch work"); });
        assertEquals(1, result.probes());
        assertEquals(100, executions(result.plan()));
        assertEquals(Map.of("darkTank", 100L), result.plan().usedStock());
    }

    @Test
    void minimumTargetFiringsAloneDoNotHideAnEqualExecutionStockSaving() {
        var graph = CraftGraph.<String>builder().stock("raw", 6)
                .pattern("T", 2, List.of(CraftInput.of("raw", 3)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
        var initial = MaterialDagReplay.tryPlan(graph, Map.of(graph.patternsFor("T").get(0), 2L), "T", 4);
        assertNotNull(initial);
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 4, initial, 32,
                candidate -> baseline(candidate, "T", 4));
        assertEquals(2, executions(result.plan()));
        assertEquals(Map.of("raw", 2L), result.plan().usedStock());
        assertTrue(result.improvements() > 0);
    }

    @Test
    void targetOnlyProofExcludesTargetStockSelfConsumptionAndSpecialRoutes() throws ReflectiveOperationException {
        var ordinary = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), null);
        var initial = plan(ordinary, Map.of("raw", 1L));
        var control = CraftGraph.<String>builder().stock("raw", 1).pattern(ordinary).build();
        assertTrue(targetOnlyBound(control, "T", 1, initial));
        assertFalse(targetOnlyBound(CraftGraph.<String>builder().stock("raw", 1).stock("T", 1)
                .pattern(ordinary).build(), "T", 1, initial));
        var source = new com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource("host", "pool");
        var excluded = List.of(
                new CraftPattern<>("T", 2, List.of(CraftInput.of("T", 1)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.returned("tool", 1)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.finiteUse("tool", 1, 2)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.returnedFrom("tool", 1, source)), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.consumedReturning("raw", 1, "container")), null),
                new CraftPattern<>("T", 2, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("side", 1)), null));
        for (var special : excluded) {
            var graph = CraftGraph.<String>builder().stock("raw", 1).pattern(ordinary).pattern(special).build();
            assertFalse(targetOnlyBound(graph, "T", 1, initial), special.toString());
        }
        var upstreamSide = CraftGraph.<String>builder().stock("raw", 1).pattern(ordinary)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("T", 99))).build();
        assertFalse(targetOnlyBound(upstreamSide, "T", 1, initial));
    }

    @Test
    void targetOnlyProofSumsInventoryExactlyBeyondTheSaturationSentinel() throws ReflectiveOperationException {
        long q = Sat.SAT / 2;
        var graph = CraftGraph.<String>builder().stock("A", q).stock("B", q).stock("C", q)
                .pattern("T", 1, List.of(CraftInput.of("A", q), CraftInput.of("B", q), CraftInput.of("C", q)))
                .pattern("T", 1, List.of(CraftInput.of("A", q - 1), CraftInput.of("B", q), CraftInput.of("C", q))).build();
        var initial = MaterialDagReplay.tryPlan(graph, Map.of(graph.patternsFor("T").get(0), 1L), "T", 1);
        assertNotNull(initial);
        assertFalse(targetOnlyBound(graph, "T", 1, initial));
    }

    @Test
    void normalizedContainerRemaindersContributeToPrimaryYieldOnce() throws ReflectiveOperationException {
        var returning = List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.consumedReturning("filled", 1, "T")), null),
                new CraftPattern<>("T", 1, List.of(CraftInput.consumedReturning("filled", 2, "T")),
                        List.of(CraftOutput.of("T", 5)), null),
                new CraftPattern<>("T", 1, List.of(CraftInput.consumedReturning("filledA", 2, "T"),
                        CraftInput.consumedReturning("filledB", 3, "T")), null));
        long[] expected = {2, 6, 6};
        var indexClass = Class.forName(FeasibleConsumptionOptimizer.class.getName() + "$Index");
        var build = indexClass.getDeclaredMethod("build", CraftGraph.class, Object.class);
        build.setAccessible(true);
        var outAmount = indexClass.getDeclaredField("outAmount");
        outAmount.setAccessible(true);
        for (int i = 0; i < returning.size(); i++) {
            var graph = CraftGraph.<String>builder().pattern(returning.get(i))
                    .pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
            var index = build.invoke(null, graph, "T");
            assertNotNull(index);
            assertArrayEquals(new long[] {expected[i], 2}, (long[]) outAmount.get(index));
        }
    }

    private static boolean targetOnlyBound(CraftGraph<String> graph, String target, long amount,
            CraftPlan<String> initial) throws ReflectiveOperationException {
        var search = Class.forName(FeasibleConsumptionOptimizer.class.getName() + "$Search");
        var constructor = search.getDeclaredConstructor(CraftGraph.class, Object.class, long.class,
                CraftPlan.class, int.class, java.util.function.Function.class,
                FeasibleConsumptionOptimizer.IndexCache.class, java.util.function.IntPredicate.class);
        constructor.setAccessible(true);
        var state = constructor.newInstance(graph, target, amount, initial, 32,
                (java.util.function.Function<CraftGraph<String>, CraftPlan<String>>) candidate -> null,
                new FeasibleConsumptionOptimizer.IndexCache<String>(),
                (java.util.function.IntPredicate) work -> true);
        var proof = search.getDeclaredMethod("targetOnlyBound");
        proof.setAccessible(true);
        return (boolean) proof.invoke(state);
    }

    @Test
    void improvesAnAlreadyFeasibleBatchChoiceAtUnitAndTrillionScale() {
        for (long scale : new long[] {1, 1_000_000, 1_000_000_000_000L}) {
            var graph = batchGraph(scale);
            var initial = baseline(graph, "T", scale);
            assertEquals(10 * scale, initial.usedStock().get("raw"));
            var result = CraftPlannerV2.planDetailed(graph, "T", scale);
            assertTrue(result.plan().feasible());
            assertEquals(2 * scale, result.plan().usedStock().get("raw"));
            assertTrue(result.diagnostics().consumptionOptimizationImprovements() > 0);
            assertTrue(result.diagnostics().consumptionOptimizationProbes() <= 5);
            assertBalance(graph, result.plan(), "T", scale);
        }
    }

    @Test
    void equalMaterialsPreferFewerExecutions() {
        var graph = CraftGraph.<String>builder().stock("raw", 1)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1))).build();
        var initial = baseline(graph, "T", 1);
        var result = CraftPlannerV2.planDetailed(graph, "T", 1);
        assertEquals(2, executions(initial));
        assertEquals(1, executions(result.plan()));
        assertEquals(initial.usedStock(), result.plan().usedStock());
        assertBalance(graph, result.plan(), "T", 1);
    }

    @Test
    void equalExecutionsDoNotExchangeIronForDiamonds() {
        var graph = CraftGraph.<String>builder().stock("iron", 100).stock("diamond", 1)
                .pattern("T", 1, List.of(CraftInput.of("iron", 8)))
                .pattern("T", 1, List.of(CraftInput.of("diamond", 1))).build();
        var initial = baseline(graph, "T", 1);
        assertEquals(Map.of("iron", 8L), initial.usedStock());
        var result = CraftPlannerV2.plan(graph, "T", 1);
        assertEquals(initial.usedStock(), result.usedStock());
        assertBalance(graph, result, "T", 1);
    }

    @Test
    void issue5UsesPreviouslyUntouchedStockToReplaceAStockFreeRoute() {
        var graph = keroseneGraph(100);
        var initial = baseline(graph, "kerosene", 100_000);
        assertTrue(initial.feasible());
        assertTrue(initial.usedStock().isEmpty());
        assertEquals(373_341, executions(initial));

        var result = CraftPlannerV2.planDetailed(graph, "kerosene", 100_000);
        assertTrue(result.plan().feasible());
        assertEquals(100, executions(result.plan()));
        assertEquals(Map.of("darkTank", 100L), result.plan().usedStock());
        assertTrue(result.diagnostics().consumptionOptimizationImprovements() > 0);
        assertBalance(graph, result.plan(), "kerosene", 100_000);
    }

    @Test
    void issue5OptimizesStockFreeAlternativesWithoutAnyInventory() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1_000, List.of(CraftInput.of("B", 1)))
                .pattern("A", 1, List.of())
                .pattern("B", 1, List.of()).build();
        var initial = baseline(graph, "T", 100_000);
        assertEquals(200_000, executions(initial));
        var result = CraftPlannerV2.plan(graph, "T", 100_000);
        assertTrue(result.feasible());
        assertEquals(200, executions(result));
        assertTrue(result.usedStock().isEmpty());
        assertBalance(graph, result, "T", 100_000);
    }

    @Test
    void issue5CannotOverdrawTheNewlyAvailableStock() {
        for (long tanks : new long[] {0, 1, 99}) {
            var graph = keroseneGraph(tanks);
            var initial = baseline(graph, "kerosene", 100_000);
            var result = CraftPlannerV2.plan(graph, "kerosene", 100_000);
            assertTrue(result.feasible());
            assertTrue(executions(result) <= executions(initial));
            assertTrue(result.usedStock().getOrDefault("darkTank", 0L) <= tanks);
            assertBalance(graph, result, "kerosene", 100_000);
        }
    }

    @Test
    void fewerExecutionsMayUseMoreOfAnExistingMaterial() {
        var graph = CraftGraph.<String>builder().stock("raw", 100)
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 100, List.of(CraftInput.of("raw", 100))).build();
        var initial = baseline(graph, "T", 10);
        assertEquals(10, initial.usedStock().get("raw"));
        var result = CraftPlannerV2.plan(graph, "T", 10);
        assertTrue(result.feasible());
        assertEquals(1, executions(result));
        assertEquals(100, result.usedStock().get("raw"));
        assertBalance(graph, result, "T", 10);
    }

    @Test
    void fewerExecutionsStillRequireValidInventoryDraws() {
        var graph = keroseneGraph(100);
        var initial = baseline(graph, "kerosene", 100_000);
        for (long invalid : new long[] {-1, 101}) {
            var candidate = plan(graph.patternsFor("kerosene").get(1), Map.of("darkTank", invalid));
            var result = FeasibleConsumptionOptimizer.optimize(graph, "kerosene", 100_000,
                    initial, 1, ignored -> candidate);
            assertSame(initial, result.plan());
            assertEquals(0, result.improvements());
        }
    }

    private static CraftGraph<String> keroseneGraph(long tanks) {
        return CraftGraph.<String>builder().stock("darkTank", tanks)
                .pattern("kerosene", 3, List.of(CraftInput.of("oilTag", 10)))
                .pattern("kerosene", 1_000, List.of(CraftInput.of("darkTank", 1)))
                .pattern("oilTag", 1, List.of(CraftInput.of("oil", 1)))
                .pattern("oil", 50, List.of()).build();
    }

    @Test
    void reducingDrawsMustNotIncreaseNetConsumptionByDiscardingReturns() {
        var returning = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 3)),
                List.of(CraftOutput.of("A", 2)), null);
        var consuming = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 2)), null);
        assertFalse(FeasibleConsumptionOptimizer.improves(plan(returning, Map.of("A", 3L)),
                plan(consuming, Map.of("A", 2L))));
    }

    @Test
    void ordinaryBranchesCanImproveWithoutChangingDurabilityUse() {
        var graph = CraftGraph.<String>builder().stock("raw", 100).stock("tool", 1)
                .pattern("T", 1, List.of(CraftInput.of("P", 1), CraftInput.finiteUse("tool", 1, 5)))
                .pattern("P", 10, List.of(CraftInput.of("raw", 10)))
                .pattern("P", 1, List.of(CraftInput.of("raw", 2))).build();
        var initial = baseline(graph, "T", 1);
        var result = CraftPlannerV2.plan(graph, "T", 1);
        assertTrue(result.feasible());
        assertEquals(2, result.usedStock().get("raw"));
        assertEquals(initial.usedStock().get("tool"), result.usedStock().get("tool"));
        assertEquals(initial.firings().get(graph.patternsFor("T").get(0)),
                result.firings().get(graph.patternsFor("T").get(0)));
    }

    @Test
    void optionalTimeoutRetainsTheLatestCompletedWitness() {
        var graph = batchGraph(1);
        var initial = baseline(graph, "T", 1);
        var better = plan(graph.patternsFor("T").get(1), Map.of("raw", 2L));
        var calls = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32, candidate -> {
            if (calls.incrementAndGet() == 1) return better;
            try (var ignored = PlanningCancellation.limitOptionalWork(0)) { PlanningCancellation.check(); }
            throw new AssertionError();
        });
        assertSame(better, result.plan());
        assertEquals(1, result.improvements());
        assertEquals(2, result.probes());
    }

    @Test
    void optionalTimeoutBeforeTheFirstCandidateRetainsOriginalAndExternalCancelPropagates() {
        var graph = batchGraph(1);
        var initial = baseline(graph, "T", 1);
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertSame(initial, FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32,
                    candidate -> { throw new AssertionError(); }).plan());
        }
        assertThrows(CancellationException.class, () -> FeasibleConsumptionOptimizer.optimize(
                graph, "T", 1, initial, 32, candidate -> { throw new CancellationException("user"); }));
        assertThrows(com.moakiee.thunderbolt.api.crafting.PlanningExitException.class,
                () -> FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32,
                        candidate -> { throw new com.moakiee.thunderbolt.api.crafting.PlanningExitException("router"); }));
    }

    @Test
    void fixedChainsAndMissingPlansDoNotEnterFeasibleOptimization() {
        var chain = CraftGraph.<String>builder().stock("raw", 100)
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("A", 1))).build();
        assertEquals(0, CraftPlannerV2.planDetailed(chain, "T", 1).diagnostics().consumptionOptimizationProbes());
        var missing = batchGraph(1).withStockLimits(Map.of());
        var result = CraftPlannerV2.planDetailed(missing, "T", 1);
        assertFalse(result.plan().feasible());
        assertEquals(0, result.diagnostics().consumptionOptimizationProbes());
    }

    @Test
    void quantityProbesShareTheOptionalProbeBudget() {
        var graph = batchGraph(1);
        var session = new CraftPlannerV2.PlanningSession<String>();
        int probes = 0;
        for (int i = 0; i < 20; i++) {
            var result = CraftPlannerV2.planDetailed(graph, "T", 1, session);
            assertTrue(result.plan().feasible());
            probes += result.diagnostics().consumptionOptimizationProbes();
        }
        assertTrue(probes > 0 && probes <= FeasibleConsumptionOptimizer.MAX_PROBES, () -> "probes exceeded");
    }

    @Test
    void smallMixedBatchesShareTheProbeAndWorkBudgets() {
        var graph = mixedBatchGraph();
        var initial = baseline(graph, "T", 8);
        var work = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(graph, "T", 8, initial, 32,
                candidate -> baseline(candidate, "T", 8), units -> {
                    work.addAndGet(units);
                    return true;
                });
        assertEquals(3, executions(result.plan()));
        assertEquals(7, result.plan().usedStock().get("raw"));
        assertTrue(work.get() > 0);
        assertTrue(result.probes() <= 32);

        var denied = FeasibleConsumptionOptimizer.optimize(graph, "T", 8, initial, 32,
                candidate -> baseline(candidate, "T", 8), units -> false);
        assertEquals(3, executions(denied.plan()));
        assertEquals(9, denied.plan().usedStock().get("raw"));
        assertBalance(graph, denied.plan(), "T", 8);

        var oneProbe = FeasibleConsumptionOptimizer.optimize(graph, "T", 8, initial, 1,
                candidate -> baseline(candidate, "T", 8), units -> {
                    throw new AssertionError("mixing exceeded the probe budget");
                });
        assertEquals(1, oneProbe.probes());
        assertEquals(9, oneProbe.plan().usedStock().get("raw"));
    }

    @Test
    void smallMixedBatchTimeoutKeepsTheCompletedPolicyAndExternalCancellationPropagates() {
        var graph = mixedBatchGraph();
        var initial = baseline(graph, "T", 8);
        var timedOut = FeasibleConsumptionOptimizer.optimize(graph, "T", 8, initial, 32,
                candidate -> baseline(candidate, "T", 8), units -> {
                    try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
                        PlanningCancellation.check();
                    }
                    throw new AssertionError();
                });
        assertEquals(3, executions(timedOut.plan()));
        assertEquals(9, timedOut.plan().usedStock().get("raw"));
        assertBalance(graph, timedOut.plan(), "T", 8);
        assertThrows(CancellationException.class, () -> FeasibleConsumptionOptimizer.optimize(
                graph, "T", 8, initial, 32, candidate -> baseline(candidate, "T", 8), units -> {
                    throw new CancellationException("user");
                }));
    }

    private static CraftGraph<String> mixedBatchGraph() {
        return CraftGraph.<String>builder().stock("raw", 128)
                .pattern("T", 3, List.of(CraftInput.of("raw", 3)))
                .pattern("T", 2, List.of(CraftInput.of("raw", 1))).build();
    }

    @Test
    void nearOuterDeadlineSkipsOptimizationAndReturnsInitial() {
        var graph = batchGraph(1);
        var context = new com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext() {
            public long deadlineNanos() { return System.nanoTime() + 1_000_000L; }
            public void checkpoint() {}
            public void report(com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot ignored) {}
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            var result = CraftPlannerV2.planDetailed(graph, "T", 1);
            assertTrue(result.plan().feasible());
            assertEquals(0, result.diagnostics().consumptionOptimizationProbes());
        }
    }

    @Test
    void saturatedDisplayAmountsAreNotTreatedAsExecutableOptimizationBounds() {
        var graph = batchGraph(1);
        var initial = plan(graph.patternsFor("T").get(0), Map.of("raw", Sat.SAT));
        assertSame(initial, FeasibleConsumptionOptimizer.optimize(graph, "T", 1, initial, 32,
                candidate -> { throw new AssertionError("display-only amount was probed"); }).plan());
    }

    @Test
    void randomBatchAlternativesAgreeWithIndependentMinimumAndNeverLoseFeasibility() {
        var random = new Random(180926);
        int improved = 0;
        for (int sample = 0; sample < 256; sample++) {
            int n = 1 + random.nextInt(8);
            int aOut = 1 + random.nextInt(8), bOut = 1 + random.nextInt(8);
            int aCost = 1 + random.nextInt(8), bCost = 1 + random.nextInt(8);
            var graph = CraftGraph.<String>builder().stock("raw", 128)
                    .pattern("T", aOut, List.of(CraftInput.of("raw", aCost)))
                    .pattern("T", bOut, List.of(CraftInput.of("raw", bCost))).build();
            var initial = baseline(graph, "T", n);
            var optimized = CraftPlannerV2.plan(graph, "T", n);
            assertTrue(optimized.feasible());
            long limit = initial.usedStock().get("raw"), raw = optimized.usedStock().get("raw");
            long executions = executions(optimized), before = executions(initial);
            // Fewer executions may spend additional stock; equal executions must not do so.
            assertTrue(raw <= graph.stock("raw"), "sample="+sample);
            assertTrue(executions < before || executions == before && raw <= limit, "sample="+sample);
            long fewest = Long.MAX_VALUE, optimum = Long.MAX_VALUE;
            for (int a = 0; a <= n; a++) for (int b = 0; b <= n; b++) {
                long cost = a*aCost+b*bCost;
                if (a*aOut+b*bOut < n || cost > graph.stock("raw")) continue;
                if (a+b < fewest || a+b == fewest && cost < optimum) {
                    fewest = a+b;
                    optimum = cost;
                }
            }
            assertEquals(fewest, executions, "executions sample=" + sample);
            assertEquals(optimum, raw, "stock sample=" + sample);
            if (executions < before || raw < limit) improved++;
            assertBalance(graph, optimized, "T", n);
        }
        assertTrue(improved > 20, "the corpus must exercise real optimization");
    }

    @Test
    void finalStockNormalizationPreservesExecutionQualityOnAuditRegressions() {
        // FinalAudit's previously feasible plans bound quality across the complete optimization pipeline.
        int[][] regressions = {{876, 18, 8}, {974, 13, 8}, {2261, 20, 11}, {2849, 16, 6}};
        for (int[] regression : regressions) {
            int sampleId = regression[0];
            var sample = auditSample(sampleId);
            assertEquals(regression[1], sample.amount(), "generator amount sample=" + sampleId);
            var result = CraftPlannerV2.plan(sample.graph(), 6, sample.amount());
            assertTrue(result.feasible(), "feasibility sample=" + sampleId);
            assertTrue(executions(result) <= regression[2],
                    () -> "executions sample=" + sampleId + ": " + executions(result));
            assertBalance(sample.graph(), result, 6, sample.amount());
        }
    }

    @Test
    void finalStockNormalizationPreservesEqualExecutionTargetStockVector() {
        var sample = auditSample(783);
        assertEquals(8, sample.amount());
        var result = CraftPlannerV2.plan(sample.graph(), 6, sample.amount());
        assertTrue(result.feasible());
        assertEquals(2, executions(result));
        var previousStock = Map.of(1, 5L, 6, 1L);
        result.usedStock().forEach((key, amount) -> assertTrue(
                amount <= previousStock.getOrDefault(key, 0L),
                () -> "sample=783 stock=" + key + ": " + amount + " > "
                        + previousStock.getOrDefault(key, 0L)));
        assertBalance(sample.graph(), result, 6, sample.amount());
    }

    private record AuditSample(CraftGraph<Integer> graph, long amount) {}

    private static AuditSample auditSample(int sampleId) {
        var random = new Random(725318L + sampleId);
        int mode = sampleId % 3;
        var builder = CraftGraph.<Integer>builder();
        builder.stock(0, mode == 2 ? random.nextInt(16) : 128);
        builder.stock(1, mode == 2 ? random.nextInt(16) : 128);
        for (int item = 2; item <= 6; item++) {
            builder.stock(item, random.nextInt(4));
            // FinalAudit draws a fresh loop bound at each condition check; keep that exact sequence.
            for (int recipe = 0; recipe < 2 + random.nextInt(2); recipe++) {
                var inputs = new ArrayList<CraftInput<Integer>>();
                inputs.add(CraftInput.of(random.nextInt(item), 1 + random.nextInt(4)));
                if (random.nextBoolean()) {
                    inputs.add(CraftInput.of(random.nextInt(item), 1 + random.nextInt(3)));
                }
                var byproducts = mode == 1 && random.nextInt(3) == 0
                        ? List.of(CraftOutput.of(random.nextInt(item), 1 + random.nextInt(2)))
                        : List.<CraftOutput<Integer>>of();
                builder.pattern(new CraftPattern<>(item, 1 + random.nextInt(5), inputs, byproducts, null));
            }
        }
        return new AuditSample(builder.build(), 1 + random.nextInt(20));
    }

    private static CraftGraph<String> batchGraph(long scale) {
        return CraftGraph.<String>builder().stock("raw", 100 * scale)
                .pattern("T", 10 * scale, List.of(CraftInput.of("raw", 10 * scale)))
                .pattern("T", scale, List.of(CraftInput.of("raw", 2 * scale))).build();
    }

    private static CraftPlan<String> baseline(CraftGraph<String> graph, String target, long amount) {
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        return CraftPlannerV2.planDetailed(graph, target, amount, session).plan();
    }

    private static CraftPlan<String> plan(CraftPattern<String> p, Map<String, Long> stock) {
        return new CraftPlan<>(true, true, Map.of(p, 1L), stock, Map.of(), Map.of(), Map.of(), 0, false);
    }

    private static long executions(CraftPlan<?> plan) { return plan.firings().values().stream().mapToLong(n -> n).sum(); }

    private static <K> void assertBalance(CraftGraph<K> graph, CraftPlan<K> plan, K target, long amount) {
        var balance = new HashMap<K, BigInteger>();
        plan.usedStock().forEach((key, value) -> {
            assertTrue(value <= graph.stock(key));
            balance.put(key, BigInteger.valueOf(value));
        });
        plan.firings().forEach((p, count) -> {
            var n = BigInteger.valueOf(count);
            p.inputs().forEach(i -> balance.merge(i.key(), i.exactAmount().multiply(n).negate(), BigInteger::add));
            balance.merge(p.output(), p.exactOutputAmount().multiply(n), BigInteger::add);
            p.byproducts().forEach(o -> balance.merge(o.key(), o.exactAmount().multiply(n), BigInteger::add));
        });
        assertTrue(balance.values().stream().allMatch(n -> n.signum() >= 0));
        assertTrue(balance.getOrDefault(target, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
