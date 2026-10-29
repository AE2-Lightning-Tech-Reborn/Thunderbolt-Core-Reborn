package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PrimaryRecipePolicyTest {
    @Test
    void possibleByproductIsNeverPromisedWithoutItsProducer() {
        var middle = pattern("M", 1, CraftInput.of("raw", 1));
        var incidental = CraftPattern.weighted("T", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("M", 1)), new Object(), 5);
        var target = pattern("T", 1, CraftInput.of("M", 1));
        var graph = CraftGraph.<String>builder().pattern(target).pattern(middle).pattern(incidental)
                .stock("raw", 1).build();
        var candidate = propose(graph, "T", 1);
        assertEquals(BigInteger.TWO, candidate.plan().executionCount());
        assertSame(middle, candidate.plan().firings().keySet().stream()
                .filter(p -> p.output().equals("M")).findFirst().orElseThrow());
        assertReplay(graph, candidate.plan(), "T", 1);
    }

    @Test
    void emittedSurplusIsPreservedButNotNeededToFundTheDag() {
        var middle = new CraftPattern<>("M", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("surplus", 3)), new Object());
        var target = pattern("T", 1, CraftInput.of("M", 1));
        var graph = CraftGraph.<String>builder().pattern(middle).pattern(target).stock("raw", 4).build();
        var plan = propose(graph, "T", 4).plan();
        assertEquals(4L, plan.firings().get(middle));
        assertEquals(3L, middle.byproducts().get(0).amount());
        assertReplay(graph, plan, "T", 4);
    }

    @Test
    void smallRequestAvoidsFractionallyCheapOversizedBatch() {
        var slow = pattern("bulkInput", 1, CraftInput.of("raw", 1));
        var bulk = pattern("M", 1_000, CraftInput.of("bulkInput", 100));
        var unit = pattern("M", 1, CraftInput.of("raw", 1));
        var target = pattern("T", 1, CraftInput.of("M", 1));
        var graph = CraftGraph.<String>builder().pattern(target).pattern(bulk).pattern(unit).pattern(slow)
                .stock("raw", 100).build();
        var plan = propose(graph, "T", 1).plan();
        assertEquals(BigInteger.TWO, plan.executionCount());
        assertTrue(plan.firings().containsKey(unit));
        assertReplay(graph, plan, "T", 1);
        assertTrue(propose(graph, "T", 1_000).plan().firings().containsKey(bulk));
    }

    @Test
    void smallRequestUsesNetDemandAfterExistingTargetStock() {
        var bulk = pattern("T", 10_000, CraftInput.of("M", 100));
        var unit = pattern("T", 1, CraftInput.of("raw", 1));
        var middle = pattern("M", 1, CraftInput.of("raw", 1));
        var graph = CraftGraph.<String>builder().pattern(bulk).pattern(unit).pattern(middle)
                .stock("T", 100_000).stock("raw", 1_000).build();
        var plan = propose(graph, "T", 100_001).plan();
        assertEquals(BigInteger.ONE, plan.executionCount());
        assertEquals(1L, plan.firings().get(unit));
        assertEquals(100_000L, plan.usedStock().get("T"));
        assertReplay(graph, plan, "T", 100_001);
    }

    @Test
    void cappedCostTieRetainsTheBetterActualYield() {
        var source = pattern("raw", 1);
        var one = pattern("M", 1, CraftInput.of("raw", 1));
        var two = pattern("M", 2, CraftInput.of("raw", 1));
        var target = pattern("T", 1, CraftInput.of("M", 3));
        var graph = CraftGraph.<String>builder().pattern(target).pattern(one).pattern(two).pattern(source).build();
        var plan = propose(graph, "T", 1).plan();
        assertTrue(plan.firings().containsKey(two));
        assertEquals(BigInteger.valueOf(5), plan.executionCount());
        assertReplay(graph, plan, "T", 1);
    }

    @Test
    void scarceStockCanBeReplenishedButNotOverdrawn() {
        var source = pattern("raw", 1);
        var middle = pattern("M", 1, CraftInput.of("raw", 1));
        var target = pattern("T", 1, CraftInput.of("M", 1));
        var graph = CraftGraph.<String>builder().pattern(target).pattern(middle).pattern(source)
                .stock("M", 2).build();
        var candidate = propose(graph, "T", 20);
        assertFalse(candidate.directStock());
        assertEquals(2L, candidate.plan().usedStock().get("M"));
        assertEquals(18L, candidate.plan().firings().get(middle));
        assertReplay(graph, candidate.plan(), "T", 20);
    }

    @Test
    void finiteRawStockDoesNotBecomeAnInfiniteSource() {
        var target = pattern("T", 1, CraftInput.of("raw", 2));
        var graph = CraftGraph.<String>builder().pattern(target).stock("raw", 3).build();
        var candidate = propose(graph, "T", 2);
        assertTrue(candidate == null || candidate.plan() == null);
    }

    @Test
    void sharedRawStockIsCheckedAcrossAllBranches() {
        var a = pattern("A", 1, CraftInput.of("raw", 2));
        var b = pattern("B", 1, CraftInput.of("raw", 2));
        var target = pattern("T", 1, CraftInput.of("A", 1), CraftInput.of("B", 1));
        var graph = CraftGraph.<String>builder().pattern(target).pattern(a).pattern(b).stock("raw", 3).build();
        var candidate = propose(graph, "T", 1);
        assertTrue(candidate == null || candidate.plan() == null);
    }

    @Test
    void cyclicRecipesWithoutStartupStockCannotBootstrapEachOther() {
        var a = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)),
                List.of(CraftOutput.of("bonus", 1)), null);
        var b = pattern("B", 2, CraftInput.of("A", 1));
        var graph = CraftGraph.<String>builder().pattern(a).pattern(b).build();
        var candidate = propose(graph, "A", 1);
        assertTrue(candidate == null || candidate.plan() == null);
    }

    @Test
    void alternateCycleDirectionKeepsARealSourceWitness() {
        var a = pattern("A", 1, CraftInput.of("B", 1));
        var b = pattern("B", 1, CraftInput.of("A", 1));
        var source = pattern("A", 1);
        var graph = CraftGraph.<String>builder().pattern(a).pattern(b).pattern(source).build();
        var plan = propose(graph, "B", 3).plan();
        assertEquals(BigInteger.valueOf(6), plan.executionCount());
        assertReplay(graph, plan, "B", 3);
    }

    @Test
    void statefulRoutesRemainOutsideTheOrdinaryPolicy() {
        for (var input : List.of(CraftInput.returned("tool", 1), CraftInput.finiteUse("tool", 1, 3),
                CraftInput.consumedReturning("tool", 1, "empty"))) {
            var stateful = pattern("T", 1, input);
            var graph = CraftGraph.<String>builder().pattern(stateful).stock("tool", 10).build();
            var candidate = propose(graph, "T", 1);
            assertTrue(candidate == null || candidate.plan() == null);
            var ordinary = pattern("T", 1, CraftInput.of("raw", 1));
            graph = CraftGraph.<String>builder().pattern(stateful).pattern(ordinary)
                    .stock("tool", 10).stock("raw", 1).build();
            assertTrue(propose(graph, "T", 1).plan().firings().containsKey(ordinary));
        }
    }

    @Test
    void weightedRecipesAndZeroCostTagsKeepTheirOriginalIdentities() {
        var expensive = CraftPattern.weighted("M", 1, List.of(CraftInput.of("raw", 1)), List.of(), new Object(), 8);
        var cheap = CraftPattern.weighted("M", 1, List.of(CraftInput.of("raw", 1)), List.of(), new Object(), 2);
        var tag = CraftPattern.tagConversion("M", "tag", new Object());
        var target = pattern("T", 1, CraftInput.of("tag", 1));
        var graph = CraftGraph.<String>builder().pattern(target).pattern(tag).pattern(expensive).pattern(cheap)
                .stock("raw", 1).build();
        var plan = propose(graph, "T", 1).plan();
        assertEquals(BigInteger.valueOf(3), plan.executionCount());
        assertTrue(plan.firings().containsKey(cheap));
        assertTrue(plan.firings().containsKey(tag));
        assertReplay(graph, plan, "T", 1);
    }

    @Test
    void repeatedRegistrationDoesNotDoubleCountARecipe() {
        var target = pattern("T", 2, CraftInput.of("raw", 1));
        var graph = CraftGraph.<String>builder().pattern(target).pattern(target).stock("raw", 3).build();
        var plan = propose(graph, "T", 5).plan();
        assertEquals(1, plan.firings().size());
        assertEquals(3L, plan.firings().get(target));
        assertReplay(graph, plan, "T", 5);
    }

    @Test
    void workRefusalNeverPublishesAnUncheckedPlan() {
        var graph = CraftGraph.<String>builder().pattern(pattern("T", 1, CraftInput.of("raw", 1)))
                .stock("raw", 1).build();
        var calls = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.primaryPlan(graph, "T", 1,
                new FeasibleConsumptionOptimizer.IndexCache<>(true), work -> { calls.incrementAndGet(); return false; });
        assertNull(result);
        assertEquals(1, calls.get());
    }

    @Test
    void completedWitnessSurvivesARefusedLaterPolicy() {
        var bulk = pattern("T", 100, CraftInput.of("raw", 1));
        var unit = pattern("T", 1, CraftInput.of("raw", 1));
        var graph = CraftGraph.<String>builder().pattern(bulk).pattern(unit).stock("raw", 1).build();
        var calls = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.primaryPlan(graph, "T", 1,
                new FeasibleConsumptionOptimizer.IndexCache<>(true), work -> calls.incrementAndGet() <= 3);
        assertNotNull(result);
        assertNotNull(result.plan());
        assertReplay(graph, result.plan(), "T", 1);
    }

    @Test
    void externalCancellationIsNotOptionalExhaustion() {
        var graph = CraftGraph.<String>builder().pattern(pattern("T", 1)).build();
        var context = new PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void checkpoint() { throw new CancellationException("cancelled"); }
            public void report(PlanningDiagnosticSnapshot snapshot) {}
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertThrows(CancellationException.class, () -> propose(graph, "T", 1));
        }
    }

    @Test
    void cancellationDoesNotPublishAnIncompletePrimaryIndex() {
        var graph = CraftGraph.<String>builder().pattern(pattern("T", 1)).build();
        var cache = new FeasibleConsumptionOptimizer.IndexCache<String>(true);
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            assertNull(FeasibleConsumptionOptimizer.primaryPlan(graph, "T", 1, cache, work -> true));
        }
        assertFalse(cache.isCompiled());
        assertNotNull(FeasibleConsumptionOptimizer.primaryPlan(graph, "T", 1, cache, work -> true).plan());
    }

    @Test
    void saturatedQuantitiesStayOnTheExistingExactContinuation() {
        var graph = CraftGraph.<String>builder().pattern(pattern("T", 1)).build();
        assertNull(propose(graph, "T", Sat.SAT));
        var huge = new CraftPattern<>("T", BigInteger.valueOf(Sat.SAT).add(BigInteger.ONE), List.of(), List.of(), null);
        assertNull(propose(CraftGraph.<String>builder().pattern(huge).build(), "T", 1));
        var hugeSide = new CraftPattern<>("T", 1, List.of(),
                List.of(CraftOutput.exact("side", BigInteger.valueOf(Sat.SAT).add(BigInteger.ONE))), null);
        assertNull(propose(CraftGraph.<String>builder().pattern(hugeSide).build(), "T", 1));
    }

    @Test
    void randomizedOrdinaryPoliciesAlwaysReplayWithinActualInventory() {
        var random = new Random(315159);
        int witnessed = 0;
        for (int fixture = 0; fixture < 200; fixture++) {
            var builder = CraftGraph.<Integer>builder();
            for (int key = 0; key < 8; key++) {
                builder.stock(key, random.nextInt(8));
                for (int route = 0; route < 3; route++) {
                    var inputs = new ArrayList<CraftInput<Integer>>();
                    if (key > 0) for (int slot = 0; slot < 1 + random.nextInt(2); slot++)
                        inputs.add(CraftInput.of(random.nextInt(key), 1 + random.nextInt(3)));
                    var sides = random.nextBoolean() ? List.of(CraftOutput.of(20 + key, 1)) : List.<CraftOutput<Integer>>of();
                    builder.pattern(CraftPattern.weighted(key, 1 + random.nextInt(4), inputs, sides, new Object(), 1 + random.nextInt(3)));
                }
            }
            var graph = builder.build();
            long amount = graph.stock(7) + 1 + random.nextInt(12);
            var candidate = propose(graph, 7, amount);
            if (candidate != null && candidate.plan() != null) {
                witnessed++;
                assertReplay(graph, candidate.plan(), 7, amount);
            }
        }
        assertTrue(witnessed >= 100);
    }

    @Test
    void quantityProbesShareTheirBudgetAndNeverReuseAnOlderQuantityWitness() {
        var graph = broadGraph();
        var session = new CraftPlannerV2.PlanningSession<String>();
        var first = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        assertTrue(first.plan().feasible());
        int afterFirst = session.searchWorkBudget.remaining;
        var second = CraftPlannerV2.planDetailed(graph, "T", 100, session);
        assertTrue(second.plan().feasible());
        assertTrue(session.searchWorkBudget.remaining < afterFirst);
        assertReplay(graph, second.plan(), "T", 100);
        assertTrue(second.diagnostics().consumedSearchBudget() <= afterFirst);
    }

    @Test
    void exactDisplayStillCompilesAfterAQuickOrdinaryWitness() {
        var graph = broadGraph();
        var session = new CraftPlannerV2.PlanningSession<String>();
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 1, session).plan().feasible());
        var exact = CraftPlannerV2.planExactDiagnostic(graph, "T", BigInteger.valueOf(3), session, Set.of());
        assertTrue(exact.missing().isEmpty());
        assertFalse(exact.firings().isEmpty());
    }

    private static CraftGraph<String> broadGraph() {
        var builder = CraftGraph.<String>builder().stock("raw", 1_000);
        builder.pattern(new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("side", 1)), new Object()));
        for (int i = 0; i < 1_400; i++) builder.pattern(pattern("T", 1, CraftInput.of("absent" + i, 1)));
        return builder.build();
    }

    @SafeVarargs
    private static <K> CraftPattern<K> pattern(K output, long amount, CraftInput<K>... inputs) {
        return new CraftPattern<>(output, amount, List.of(inputs), new Object());
    }

    private static <K> FeasibleConsumptionOptimizer.PrimaryResult<K> propose(CraftGraph<K> graph, K target, long amount) {
        return FeasibleConsumptionOptimizer.primaryPlan(graph, target, amount,
                new FeasibleConsumptionOptimizer.IndexCache<>(true), work -> true);
    }

    private static <K> void assertReplay(CraftGraph<K> graph, CraftPlan<K> plan, K target, long amount) {
        assertTrue(plan.feasible());
        assertTrue(plan.missing().isEmpty());
        assertTrue(plan.usedReusableStock().isEmpty());
        var stock = new HashMap<K, BigInteger>();
        plan.usedStock().forEach((key, count) -> {
            assertTrue(count >= 0 && count <= graph.stock(key));
            stock.put(key, BigInteger.valueOf(count));
        });
        var pending = new IdentityHashMap<>(plan.firings());
        while (!pending.isEmpty()) {
            CraftPattern<K> selected = null;
            for (var entry : pending.entrySet()) {
                var required = new HashMap<K, BigInteger>();
                for (var input : entry.getKey().inputs()) {
                    assertFalse(input.returned());
                    assertNull(input.remainder());
                    assertNull(input.reusableStockSource());
                    required.merge(input.key(), input.exactAmount().multiply(BigInteger.valueOf(entry.getValue())), BigInteger::add);
                }
                if (required.entrySet().stream().allMatch(e -> stock.getOrDefault(e.getKey(), BigInteger.ZERO).compareTo(e.getValue()) >= 0)) {
                    selected = entry.getKey();
                    break;
                }
            }
            assertNotNull(selected, "no physical firing is enabled");
            assertTrue(graph.patternsFor(selected.output()).contains(selected));
            BigInteger times = BigInteger.valueOf(pending.remove(selected));
            assertTrue(times.signum() > 0);
            for (var input : selected.inputs()) stock.merge(input.key(), input.exactAmount().multiply(times).negate(), BigInteger::add);
            stock.merge(selected.output(), selected.exactOutputAmount().multiply(times), BigInteger::add);
            for (var side : selected.byproducts()) stock.merge(side.key(), side.exactAmount().multiply(times), BigInteger::add);
        }
        assertTrue(stock.getOrDefault(target, BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
