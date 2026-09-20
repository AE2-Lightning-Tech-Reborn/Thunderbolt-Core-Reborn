package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CommonInputFlowTest {
    @Test
    void commonFuelKeepsWideAssignmentsFeasibleAtUnitAndTrillionAmounts() {
        for (int outputs : new int[] {64, 256, 1024, 4096})
            for (long n : new long[] {1, 1_000_000_000_000L}) {
                var graph = assignment(outputs, n, false);
                var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                        () -> CraftPlannerV2.planDetailed(graph, "T", n));
                assertTrue(result.plan().feasible(), () -> result.diagnostics().toString());
                assertEquals(0, result.diagnostics().lowWidthIntegerNodes());
                check(graph, result.plan(), n);
            }
    }

    @Test
    void fixedFuelAndAlternativeRawDeficitsRemainSeparateAndBothRefill() {
        for (long n : new long[] {1, 1_000_000_000_000L}) {
            var original = assignment(1024, n, true);
            int raw = 0;
            while (original.stock("R"+raw) == 0) raw++;
            var graph = original.withoutStock(Map.of("R"+raw, 1L, "F", 1L));
            var plan = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> CraftPlannerV2.plan(graph, "T", n));
            assertFalse(plan.feasible());
            assertEquals(2, plan.missing().size(), plan.missing().toString());
            assertEquals(1L, plan.missing().get("F"));
            for (var e : plan.missing().entrySet()) {
                assertTrue(graph.patternsFor(e.getKey()).isEmpty());
                if (e.getKey().equals("F")) continue;
                long quantum = 2+Integer.parseInt(e.getKey().substring(1))%3;
                assertEquals(quantum - graph.stock(e.getKey())%quantum, e.getValue());
            }
            check(graph, plan, n);
            var supplied = graph.withAdditionalStock(plan.missing());
            var refill = CraftPlannerV2.plan(supplied, "T", n);
            assertTrue(refill.feasible());
            check(supplied, refill, n);
        }
    }

    @Test
    void partialOutputStockAndSharedCommonResourcesMatchIndependentFiringEnumeration() {
        var random = new Random(2026092212L);
        for (int sample = 0; sample < 256; sample++) {
            int rawA = 1+random.nextInt(3), rawB = 1+random.nextInt(3);
            int commonPA = random.nextInt(3), commonQA = random.nextInt(3);
            int commonPF = 1+random.nextInt(3), commonQF = 1+random.nextInt(3);
            int batchP = 1+random.nextInt(3), batchQ = 1+random.nextInt(3);
            int stockA = random.nextInt(12), stockB = random.nextInt(12), stockF = random.nextInt(12);
            int stockP = random.nextInt(4), stockQ = random.nextInt(4);
            int needP = 1+random.nextInt(5), needQ = 1+random.nextInt(5);
            var patterns = List.of(
                    recipe("P", batchP, commonPA+rawA, 0, commonPF),
                    recipe("P", batchP, commonPA, rawB, commonPF),
                    recipe("Q", batchQ, commonQA+rawA, 0, commonQF),
                    recipe("Q", batchQ, commonQA, rawB, commonQF));
            var root = new CraftPattern<>("T", 1,
                    List.of(CraftInput.of("P", needP), CraftInput.of("Q", needQ)), "root");
            var builder = CraftGraph.<String>builder().stock("A", stockA).stock("B", stockB)
                    .stock("F", stockF).stock("P", stockP).stock("Q", stockQ).pattern(root);
            patterns.forEach(builder::pattern);
            var graph = builder.build();
            var result = solve(graph, patterns, Map.of("P", (long)needP, "Q", (long)needQ));
            assertNotNull(result);
            var counts = new IdentityHashMap<CraftPattern<String>, Long>();
            counts.putAll(result.firings() != null ? result.firings() : result.leafSupplyFirings());
            counts.put(root, 1L);
            var plan = MaterialDagReplay.tryLeafMissingPlan(graph, counts, "T", 1);
            assertNotNull(plan);
            check(graph, plan, 1);
            long ma = plan.missing().getOrDefault("A", 0L), mb = plan.missing().getOrDefault("B", 0L);
            long mf = plan.missing().getOrDefault("F", 0L);
            boolean actuallyFeasible = false;
            // Original quantities and integer firing counts, independently of the factorization.
            for (int ap = 0; ap <= 5; ap++) for (int bp = 0; bp <= 5; bp++)
                for (int aq = 0; aq <= 5; aq++) for (int bq = 0; bq <= 5; bq++) {
                    if (stockP+batchP*(ap+bp) < needP || stockQ+batchQ*(aq+bq) < needQ) continue;
                    int a = Math.max(0, commonPA*(ap+bp)+commonQA*(aq+bq)+rawA*(ap+aq)-stockA);
                    int b = Math.max(0, rawB*(bp+bq)-stockB);
                    int f = Math.max(0, commonPF*(ap+bp)+commonQF*(aq+bq)-stockF);
                    actuallyFeasible |= a == 0 && b == 0 && f == 0;
                    assertFalse(a <= ma && b <= mb && f <= mf && (a < ma || b < mb || f < mf),
                            "dominated at sample "+sample);
                }
            assertEquals(actuallyFeasible, result.status() == BoundedIntegerLinearSolver.Status.SOLVED);
            var refill = solve(graph.withAdditionalStock(plan.missing()), patterns,
                    Map.of("P", (long)needP, "Q", (long)needQ));
            assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, refill.status());
        }
    }

    @Test
    void aRouteWithOnlyCommonInputsDominatesRoutesWithAdditionalConsumption() {
        var cheap = recipe("P", 2, 1, 0, 2);
        var costly = recipe("P", 2, 3, 4, 2);
        var graph = CraftGraph.<String>builder().stock("A", 1).stock("F", 2)
                .pattern(cheap).pattern(costly).build();
        var result = solve(graph, List.of(costly, cheap), Map.of("P", 2L));
        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status());
        assertEquals(Map.of(cheap, 1L), result.firings());
        var missing = solve(graph, List.of(costly, cheap), Map.of("P", 4L));
        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, missing.status());
        assertNull(missing.firings());
        assertEquals(Map.of(cheap, 2L), missing.leafSupplyFirings());
    }

    @Test
    void internalProductionVariableBatchSizesAndStatefulInputsStayOnTheGeneralPath() {
        var base = recipe("P", 1, 1, 0, 1);
        var alternative = recipe("P", 2, 0, 1, 1);
        var graph = CraftGraph.<String>builder().pattern(base).pattern(alternative).build();
        assertNull(solve(graph, List.of(base, alternative), Map.of("P", 2L)));
        var inner = new CraftPattern<>("Q", 1, List.of(CraftInput.of("P", 1), CraftInput.of("F", 1)), null);
        graph = CraftGraph.<String>builder().pattern(base).pattern(inner).build();
        assertNull(solve(graph, List.of(base, inner), Map.of("Q", 1L)));
        var returned = new CraftPattern<>("P", 1, List.of(CraftInput.returned("A", 1), CraftInput.of("F", 1)), null);
        graph = CraftGraph.<String>builder().pattern(returned).build();
        assertNull(solve(graph, List.of(returned), Map.of("P", 1L)));
        var arbitrary = recipe("P", 1, 1, 1, 0);
        var arbitraryAlt = new CraftPattern<>("P", 1, List.of(CraftInput.of("F", 1), CraftInput.of("Z", 1)), null);
        graph = CraftGraph.<String>builder().pattern(arbitrary).pattern(arbitraryAlt).build();
        assertNull(solve(graph, List.of(arbitrary, arbitraryAlt), Map.of("P", 1L)));
    }

    @Test
    void boundaryDemandsAndSuppliesShareTheOriginalMaterialInventory() {
        var a = recipe("P", 1, 1, 0, 1);
        var b = recipe("P", 1, 0, 1, 1);
        var graph = CraftGraph.<String>builder().stock("A", 1).stock("F", 1).pattern(a).pattern(b).build();
        var demand = Map.of("P", 1L, "A", 1L, "F", 1L);
        var result = CommonInputFlow.trySolve(graph, List.of("A", "B", "F", "P"), List.of(a, b),
                demand, Map.of("B", 1L, "F", 1L), BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status());
        assertEquals(Map.of(b, 1L), result.firings());
        result = CommonInputFlow.trySolve(graph, List.of("A", "B", "F", "P"), List.of(a, b),
                demand, Map.of("B", 1L), BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status());
        assertNull(result.firings());
        assertEquals(Map.of(b, 1L), result.leafSupplyFirings());
    }

    @Test
    void duplicateInputSlotsAreAggregatedAndBudgetCutoffRemainsDistinct() {
        var duplicate = new CraftPattern<>("P", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("A", 2), CraftInput.of("F", 1)), null);
        var alternative = recipe("P", 1, 0, 1, 1);
        var graph = CraftGraph.<String>builder().stock("A", 3).stock("F", 1)
                .pattern(duplicate).pattern(alternative).build();
        var result = solve(graph, List.of(duplicate, alternative), Map.of("P", 1L));
        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status());
        assertEquals(Map.of(duplicate, 1L), result.firings());
        result = CommonInputFlow.trySolve(graph, List.of("A", "B", "F", "P"), List.of(duplicate, alternative),
                Map.of("P", 1L), Map.of(), BoundedIntegerLinearSolver.WorkBudget.bounded(1, 1, Long.MAX_VALUE));
        assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, result.status());
        assertNull(result.firings());
        assertNull(result.leafSupplyFirings());
    }

    private static CraftPattern<String> recipe(String output, long amount, long a, long b, long f) {
        var inputs = new ArrayList<CraftInput<String>>();
        if (a > 0) inputs.add(CraftInput.of("A", a));
        if (b > 0) inputs.add(CraftInput.of("B", b));
        if (f > 0) inputs.add(CraftInput.of("F", f));
        return new CraftPattern<>(output, amount, inputs, null);
    }

    private static UnitMaterialFlow.Result<String> solve(CraftGraph<String> graph,
            List<CraftPattern<String>> patterns, Map<String, Long> demand) {
        var items = new LinkedHashSet<>(demand.keySet());
        patterns.forEach(p -> {items.add(p.output());p.inputs().forEach(i -> items.add(i.key()));});
        return CommonInputFlow.trySolve(graph, List.copyOf(items), patterns, demand, Map.of(),
                BoundedIntegerLinearSolver.WorkBudget.unlimited());
    }

    private static CraftGraph<String> assignment(int outputs, long n, boolean batched) {
        var random = new Random(2026092200L);
        int resources = Math.max(16, outputs/8);
        int[] stock = new int[resources];
        var builder = CraftGraph.<String>builder();
        var root = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < outputs; i++) {
            var choices = new LinkedHashSet<Integer>();
            while (choices.size() < 3) choices.add(random.nextInt(resources));
            var options = new ArrayList<>(choices);
            stock[options.get(random.nextInt(3))]++;
            long output = batched ? 2+i%4 : 1;
            for (int r : choices) builder.pattern("P"+i, output,
                    List.of(CraftInput.of("R"+r, batched ? 2+r%3 : 1), CraftInput.of("F", 1)));
            root.add(CraftInput.of("P"+i, output));
        }
        for (int r = 0; r < resources; r++) builder.stock("R"+r, n*stock[r]*(batched ? 2+r%3 : 1));
        return builder.stock("F", outputs*n).pattern("T", 1, root).build();
    }

    private static void check(CraftGraph<String> graph, CraftPlan<String> plan, long amount) {
        var balance = new HashMap<String, BigInteger>();
        plan.usedStock().forEach((key, count) -> {
            assertTrue(count <= graph.stock(key));
            balance.put(key, BigInteger.valueOf(count));
        });
        plan.missing().forEach((key, count) -> balance.merge(key, BigInteger.valueOf(count), BigInteger::add));
        plan.firings().forEach((p, n) -> {
            var times = BigInteger.valueOf(n);
            p.inputs().forEach(i -> balance.merge(i.key(), i.exactAmount().multiply(times).negate(), BigInteger::add));
            balance.merge(p.output(), p.exactOutputAmount().multiply(times), BigInteger::add);
        });
        assertTrue(balance.values().stream().allMatch(v -> v.signum() >= 0));
        assertTrue(balance.getOrDefault("T", BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
