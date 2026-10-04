package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FeasiblePolicyAlternativesTest {
    @Test
    void changingTheAssemblyBatchCanUseStockOutsideTheInitialWithdrawal() {
        for (boolean reversed : List.of(false, true)) {
            var graph = assembly(3, reversed);
            var initial = firstPlan(graph, 9);
            assertEquals(8, executions(initial));
            assertEquals(Map.of("ore", 6L), initial.usedStock());

            var result = CraftPlannerV2.plan(graph, "machine", 9);
            // Three small assemblies + two plate batches + one directly stocked-dust coil batch.
            assertEquals(6, executions(result));
            assertEquals(Map.of("ore", 4L, "dust", 2L), result.usedStock());
            assertExecutable(graph, result, 9);
        }
    }

    @Test
    void joiningTwoBranchesCanAvoidAWholeIntermediateBatch() {
        for (boolean reversed : List.of(false, true)) {
            var graph = sharedSupplier(reversed);
            var initial = firstPlan(graph, 2);
            assertEquals(8, executions(initial));
            assertEquals(Map.of("ore", 1L, "dust", 1L), initial.usedStock());

            var result = CraftPlannerV2.plan(graph, "machine", 2);
            // One six-wire batch supplies both plates and coils: 1 + 2 + 2 + 2 firings.
            assertEquals(7, executions(result));
            assertEquals(Map.of("ore", 2L), result.usedStock());
            assertExecutable(graph, result, 2);
        }
    }

    @Test
    void aCheaperPolicyCannotSpendMoreDustThanTheActualInventory() {
        var graph = assembly(1, false);
        var result = CraftPlannerV2.plan(graph, "machine", 9);
        // The six-firing witness needs two dust. With only one, the ore-only plan is retained.
        assertEquals(8, executions(result));
        assertExecutable(graph, result, 9);
    }

    @Test
    void deniedWorkAndAUsedProbeAllowanceKeepAnExecutableIncumbent() {
        var graph = assembly(3, false);
        var initial = firstPlan(graph, 9);
        var denied = FeasibleConsumptionOptimizer.optimize(graph, "machine", 9, initial, 32,
                candidate -> firstPlan(candidate, 9), work -> false);
        assertEquals(8, executions(denied.plan()));
        assertExecutable(graph, denied.plan(), 9);

        var calls = new AtomicInteger();
        var bounded = FeasibleConsumptionOptimizer.optimize(graph, "machine", 9, initial, 1,
                candidate -> {
                    calls.incrementAndGet();
                    return firstPlan(candidate, 9);
                });
        assertTrue(bounded.probes() <= 1);
        assertTrue(calls.get() <= 1);
        assertTrue(executions(bounded.plan()) <= executions(initial));
        assertExecutable(graph, bounded.plan(), 9);
    }

    @Test
    void cancellationInTheSharedWorkAllowanceStillPropagates() {
        var graph = assembly(3, false);
        var initial = firstPlan(graph, 9);
        assertThrows(CancellationException.class, () -> FeasibleConsumptionOptimizer.optimize(
                graph, "machine", 9, initial, 32, candidate -> firstPlan(candidate, 9),
                work -> { throw new CancellationException("external caller cancelled"); }));
    }

    @Test
    void finalPolicyAlternativeUsesOnlyTheRemainingProbeAllowance() {
        var graph = finalPhaseGraph(0);
        var initial = bulkIncumbent(graph, false);
        assertExecutable(graph, initial, 1);
        var charges = new AtomicInteger();
        var calls = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(graph, "machine", 1, initial, 32,
                candidate -> {
                    calls.incrementAndGet();
                    return firstPlan(candidate, 1);
                }, work -> {
                    if (work == FINAL_PHASE_WORK) charges.incrementAndGet();
                    return true;
                });
        assertEquals(1, executions(result.plan()));
        assertEquals(Map.of("S", 1L), result.plan().usedStock());
        assertEquals(2, charges.get(), "Charge the final phase and its first alternative before probing");
        assertEquals(calls.get(), result.probes());
        assertTrue(result.probes() > 0);
        assertExecutable(graph, result.plan(), 1);

        // Earlier unsuccessful probes retain their allowance. The last available one confirms S.
        var exactCalls = new AtomicInteger();
        var exact = FeasibleConsumptionOptimizer.optimize(graph, "machine", 1, initial, result.probes(),
                candidate -> {
                    exactCalls.incrementAndGet();
                    return firstPlan(candidate, 1);
                });
        assertEquals(result.probes(), exactCalls.get());
        assertEquals(result.probes(), exact.probes());
        assertEquals(1, executions(exact.plan()));
        assertExecutable(graph, exact.plan(), 1);

        var fewerCalls = new AtomicInteger();
        var fewer = FeasibleConsumptionOptimizer.optimize(graph, "machine", 1, initial, result.probes() - 1,
                candidate -> {
                    fewerCalls.incrementAndGet();
                    return firstPlan(candidate, 1);
                });
        assertTrue(fewerCalls.get() <= result.probes() - 1);
        assertTrue(fewer.probes() <= result.probes() - 1);
        assertSame(initial, fewer.plan());
        assertExecutable(graph, fewer.plan(), 1);
    }

    @Test
    void finalPolicyWorkDenialAndCancellationHappenBeforeTheNewProbe() {
        var graph = finalPhaseGraph(0);
        var initial = bulkIncumbent(graph, false);
        for (int stopAt : List.of(1, 2)) {
            var charges = new AtomicInteger();
            var newProbes = new AtomicInteger();
            var denied = FeasibleConsumptionOptimizer.optimize(graph, "machine", 1, initial, 32,
                    candidate -> {
                        if (candidate.stock("S") > 0) newProbes.incrementAndGet();
                        return firstPlan(candidate, 1);
                    }, work -> work != FINAL_PHASE_WORK || charges.incrementAndGet() < stopAt);
            assertEquals(stopAt, charges.get());
            assertEquals(0, newProbes.get());
            assertSame(initial, denied.plan());
            assertExecutable(graph, denied.plan(), 1);

            var cancellation = new CancellationException("cancel the final full-stock policy proposal");
            charges.set(0);
            assertSame(cancellation, assertThrows(CancellationException.class,
                    () -> FeasibleConsumptionOptimizer.optimize(graph, "machine", 1, initial, 32,
                            candidate -> {
                                if (candidate.stock("S") > 0) newProbes.incrementAndGet();
                                return firstPlan(candidate, 1);
                            }, work -> {
                                if (work == FINAL_PHASE_WORK && charges.incrementAndGet() == stopAt)
                                    throw cancellation;
                                return true;
                            })));
            assertEquals(stopAt, charges.get());
            assertEquals(0, newProbes.get());
        }
    }

    @Test
    void aFullInventoryExecutionBoundSkipsTheFinalPolicyCharge() {
        var graph = finalPhaseGraph(2);
        var initial = bulkIncumbent(graph, true);
        assertExecutable(graph, initial, 1);
        // One bulk firing uses two stocked A. The target-only stock bound is just one,
        // so its shortcut does not explain skipping the final full-inventory proposals.
        var finalCharges = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(graph, "machine", 1, initial, 32,
                candidate -> firstPlan(candidate, 1), work -> {
                    if (work == FINAL_PHASE_WORK) finalCharges.incrementAndGet();
                    return true;
                });
        assertEquals(0, finalCharges.get());
        assertSame(initial, result.plan());
        assertExecutable(graph, result.plan(), 1);
    }

    @Test
    void aSubPercentExecutionGainDoesNotLaunchAnotherFullStockProbe() {
        var bulk = pattern("machine", 301, "A", 2);
        var upstream = pattern("A", 1, "R", 1);
        var direct = pattern("machine", 100, "S", 1);
        var builder = CraftGraph.<String>builder().stock("R", 134).stock("S", 200)
                .pattern(bulk).pattern(upstream).pattern(direct)
                // This route makes the fractional full-stock bound loose but lacks one S.
                // The test must reach the final alternatives, not stop at that bound.
                .pattern(pattern("machine", 100_000, "S", 201));
        for (int raw = 3; raw <= 15; raw++) builder.pattern(pattern("machine", 1, "R", raw));
        var graph = builder.build();
        var firings = new LinkedHashMap<CraftPattern<String>, Long>();
        firings.put(bulk, 67L);
        firings.put(upstream, 134L);
        var initial = new CraftPlan<>(true, true, firings, Map.of("R", 134L), Map.of(), Map.of(),
                Map.of("machine", 20_000L, "A", 134L, "R", 134L), 3, false);
        var smallGain = new CraftPlan<>(true, true, Map.of(direct, 200L), Map.of("S", 200L),
                Map.of(), Map.of(), Map.of("machine", 20_000L, "S", 200L), 2, false);
        assertEquals(201, executions(initial));
        assertEquals(200, executions(smallGain));
        assertExecutable(graph, initial, 20_000);
        assertExecutable(graph, smallGain, 20_000);
        assertTrue(FeasibleConsumptionOptimizer.improves(initial, smallGain),
                "The existing objective still accepts fewer executions; only this optional search is gated");

        var finalCharges = new AtomicInteger();
        var newProbes = new AtomicInteger();
        var result = FeasibleConsumptionOptimizer.optimize(graph, "machine", 20_000, initial, 32,
                candidate -> {
                    if (candidate.stock("S") > 0) newProbes.incrementAndGet();
                    return firstPlan(candidate, 20_000);
                }, work -> {
                    if (work == FINAL_PHASE_WORK) finalCharges.incrementAndGet();
                    return true;
                });
        assertTrue(finalCharges.get() >= 3, "The final scan must consider both bulk and direct alternatives");
        assertEquals(0, newProbes.get(), "Saving 1 of 201 executions does not justify a new full-stock probe");
        assertSame(initial, result.plan());
        assertExecutable(graph, result.plan(), 20_000);
    }

    // Only the new full-graph pass charges this amount. The earlier terminal scans use
    // single-unit charges and a 64-route allocation; the small mixer declines 17 patterns.
    private static final int FINAL_PHASE_WORK = 4 + 17 + 17;

    private static CraftGraph<String> finalPhaseGraph(long stockedA) {
        var builder = CraftGraph.<String>builder().stock("R", 2).stock("S", 1).stock("A", stockedA)
                .pattern(pattern("machine", 4, "A", 2))
                .pattern(pattern("A", 1, "R", 1))
                .pattern(pattern("machine", 1, "S", 1));
        for (int raw = 3; raw <= 16; raw++) builder.pattern(pattern("machine", 1, "R", raw));
        return builder.build();
    }

    private static CraftPlan<String> bulkIncumbent(CraftGraph<String> graph, boolean stockedA) {
        var firings = new LinkedHashMap<CraftPattern<String>, Long>();
        firings.put(graph.patternsFor("machine").getFirst(), 1L);
        if (!stockedA) firings.put(graph.patternsFor("A").getFirst(), 2L);
        return new CraftPlan<>(true, true, firings, Map.of(stockedA ? "A" : "R", 2L),
                Map.of(), Map.of(), stockedA ? Map.of("machine", 1L, "A", 2L)
                        : Map.of("machine", 1L, "A", 2L, "R", 2L), stockedA ? 2 : 3, false);
    }

    private static CraftGraph<String> assembly(long dust, boolean reverse) {
        var patterns = new ArrayList<>(List.of(
                pattern("machine", 3, "plate", 2, "coil", 1),
                pattern("machine", 5, "plate", 1, "coil", 3),
                pattern("plate", 2, "alloy", 3),
                pattern("plate", 3, "ore", 2),
                pattern("coil", 2, "alloy", 2),
                pattern("coil", 3, "dust", 2),
                pattern("alloy", 3, "ore", 2),
                pattern("alloy", 5, "dust", 3)));
        if (reverse) Collections.reverse(patterns);
        var builder = CraftGraph.<String>builder().stock("ore", 8).stock("dust", dust);
        patterns.forEach(builder::pattern);
        return builder.build();
    }

    private static CraftGraph<String> sharedSupplier(boolean reverse) {
        var patterns = new ArrayList<>(List.of(
                pattern("machine", 1, "plate", 1, "coil", 1),
                pattern("plate", 1, "ingot", 1),
                pattern("plate", 1, "wire", 2),
                pattern("coil", 1, "ingot", 2),
                pattern("coil", 1, "wire", 1),
                pattern("ingot", 4, "ore", 1),
                pattern("ingot", 5, "dust", 2),
                pattern("wire", 5, "dust", 1),
                pattern("wire", 6, "ore", 2)));
        if (reverse) Collections.reverse(patterns);
        var builder = CraftGraph.<String>builder().stock("ore", 2).stock("dust", 2);
        patterns.forEach(builder::pattern);
        return builder.build();
    }

    private static CraftPattern<String> pattern(String output, long amount, Object... inputs) {
        var use = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < inputs.length; i += 2)
            use.add(CraftInput.of((String) inputs[i], ((Number) inputs[i + 1]).longValue()));
        return new CraftPattern<>(output, amount, use, new Object());
    }

    private static CraftPlan<String> firstPlan(CraftGraph<String> graph, long amount) {
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        return CraftPlannerV2.planDetailed(graph, "machine", amount, session).plan();
    }

    private static long executions(CraftPlan<?> plan) {
        return plan.firings().values().stream().mapToLong(Long::longValue).sum();
    }

    /** Independent gross-input replay: no production replay helper or balance solver is called. */
    private static void assertExecutable(CraftGraph<String> graph, CraftPlan<String> plan, long amount) {
        assertTrue(plan.supported());
        assertTrue(plan.feasible());
        assertTrue(plan.missing().isEmpty());
        assertTrue(plan.usedReusableStock().isEmpty());
        var pool = new HashMap<String, BigInteger>();
        plan.usedStock().forEach((key, count) -> {
            assertTrue(count >= 0 && count <= graph.stock(key));
            pool.put(key, BigInteger.valueOf(count));
        });
        var pending = new LinkedHashMap<>(plan.firings());
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (var iterator = pending.entrySet().iterator(); iterator.hasNext();) {
                var entry = iterator.next();
                var pattern = entry.getKey();
                assertTrue(entry.getValue() > 0);
                assertTrue(graph.patternsFor(pattern.output()).stream().anyMatch(p -> p == pattern));
                var needed = new HashMap<String, BigInteger>();
                pattern.inputs().forEach(i -> needed.merge(i.key(), i.exactAmount(), BigInteger::add));
                if (needed.entrySet().stream().anyMatch(e ->
                        pool.getOrDefault(e.getKey(), BigInteger.ZERO).compareTo(e.getValue()) < 0)) continue;
                needed.forEach((key, n) -> pool.merge(key, n.negate(), BigInteger::add));
                pool.merge(pattern.output(), pattern.exactOutputAmount(), BigInteger::add);
                if (entry.getValue() == 1) iterator.remove();
                else entry.setValue(entry.getValue() - 1);
                progress = true;
            }
            assertTrue(progress, "No unfinished recipe is enabled: " + pending);
        }
        assertTrue(pool.getOrDefault("machine", BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
