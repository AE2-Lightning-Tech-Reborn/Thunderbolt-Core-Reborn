package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class UnitMaterialFlowTest {
    @Test
    void residualReallocationLeavesTheExclusiveResourceForItsOnlyConsumer() {
        var a = recipe("P", "A");
        var b = recipe("P", "B");
        var exclusive = recipe("Q", "A");
        var graph = CraftGraph.<String>builder().stock("A", 1).stock("B", 1)
                .pattern(a).pattern(b).pattern(exclusive).build();
        var result = solve(graph, List.of("P", "Q", "A", "B"), List.of(a, b, exclusive), Map.of("P", 1L, "Q", 1L));
        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status());
        assertEquals(Map.of(b, 1L, exclusive, 1L), result.firings());
    }

    @Test
    void agreesWithIndependentExhaustiveAssignmentsAtUnitAndTrillionScale() {
        var random = new Random(2026092010L);
        for (int sample = 0; sample < 256; sample++) {
            int[][] choices = new int[6][2];
            int[] stock = new int[4];
            for (int i = 0; i < stock.length; i++) stock[i] = random.nextInt(4);
            for (int[] choice : choices) {
                choice[0] = random.nextInt(4);
                do { choice[1] = random.nextInt(4); } while (choice[0] == choice[1]);
            }
            boolean feasible = assign(choices, stock.clone(), 0);
            for (long n : new long[] {1, 1_000_000_000_000L}) {
                var builder = CraftGraph.<String>builder();
                var patterns = new ArrayList<CraftPattern<String>>();
                var items = new ArrayList<String>();
                var demand = new HashMap<String, Long>();
                for (int r = 0; r < stock.length; r++) {
                    items.add("R"+r);
                    builder.stock("R"+r, stock[r]*n);
                }
                for (int p = 0; p < choices.length; p++) {
                    items.add("P"+p);
                    demand.put("P"+p, n);
                    for (int r : choices[p]) {
                        var pattern = recipe("P"+p, "R"+r);
                        patterns.add(pattern);
                        builder.pattern(pattern);
                    }
                }
                var graph = builder.build();
                var result = solve(graph, items, patterns, demand);
                assertEquals(feasible ? BoundedIntegerLinearSolver.Status.SOLVED
                        : BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status());
                if (feasible) assertBalance(graph, items, demand, result.firings());
            }
        }
    }

    @Test
    void wideGuaranteedAssignmentsUseTheFlowPathWithinTheExistingBudget() {
        for (int outputs : new int[] {64, 256, 1024, 4096}) {
            for (long n : new long[] {1, 1_000_000_000_000L}) {
                var graph = assignmentGraph(outputs, Math.max(16, outputs/8), n, 2026092000L);
                var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                        () -> CraftPlannerV2.planDetailed(graph, "T", n));
                assertTrue(result.plan().feasible(), () -> result.diagnostics().toString());
                assertEquals(1, result.diagnostics().planRuns());
                assertEquals(0, result.diagnostics().lowWidthIntegerNodes());
                result.plan().usedStock().forEach((key, amount) -> assertTrue(amount <= graph.stock(key)));
                var balance = new HashMap<String, BigInteger>();
                result.plan().usedStock().forEach((key, amount) -> balance.put(key, BigInteger.valueOf(amount)));
                result.plan().firings().forEach((pattern, times) -> {
                    var count = BigInteger.valueOf(times);
                    pattern.inputs().forEach(input -> balance.merge(input.key(), input.exactAmount().multiply(count).negate(), BigInteger::add));
                    balance.merge(pattern.output(), pattern.exactOutputAmount().multiply(count), BigInteger::add);
                });
                balance.forEach((key, amount) -> assertTrue(amount.signum() >= 0));
                assertTrue(balance.getOrDefault("T", BigInteger.ZERO).compareTo(BigInteger.valueOf(n)) >= 0);
            }
        }
    }

    @Test
    void longChainsUseAnIterativePathAndAmountsDoNotBecomeIterationCounts() {
        var builder = CraftGraph.<String>builder().stock("P0", 1_000_000_000_000L);
        var patterns = new ArrayList<CraftPattern<String>>();
        var items = new ArrayList<String>();
        items.add("P0");
        for (int i = 1; i <= 5000; i++) {
            items.add("P"+i);
            var pattern = recipe("P"+i, "P"+(i-1));
            patterns.add(pattern);
            builder.pattern(pattern);
        }
        var graph = builder.build();
        var demand = Map.of("P5000", 1_000_000_000_000L);
        var result = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> solve(graph, items, patterns, demand));
        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status());
        assertBalance(graph, items, demand, result.firings());
    }

    @Test
    void inconsistentBatchesSideOutputsReturnedToolsAndRealCyclesStayOutsideThisKernel() {
        var batch = new CraftPattern<>("B", 2, List.of(CraftInput.of("A", 2)), "batch");
        var side = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)),
                List.of(CraftOutput.of("C", 1)), "side");
        var returned = new CraftPattern<>("B", 1, List.of(CraftInput.returned("A", 1)), "returned");
        for (var pattern : List.of(side, returned)) {
            var graph = CraftGraph.<String>builder().stock("A", 2).pattern(pattern).build();
            assertNull(solve(graph, List.of("A", "B", "C"), List.of(pattern), Map.of("B", 1L)));
        }
        var incompatible = new CraftPattern<>("B", 2, List.of(CraftInput.of("A", 1)), "incompatible");
        var batchGraph = CraftGraph.<String>builder().stock("A", 2).pattern(batch).pattern(incompatible).build();
        assertNull(solve(batchGraph, List.of("A", "B"), List.of(batch, incompatible), Map.of("B", 1L)));
        var ab = recipe("B", "A");
        var ba = recipe("A", "B");
        var graph = CraftGraph.<String>builder().stock("A", 1).pattern(ab).pattern(ba).build();
        assertNull(solve(graph, List.of("A", "B"), List.of(ab, ba), Map.of("B", 1L)));
    }

    @Test
    void anExhaustedWorkBudgetOrCancellationCannotBecomeInfeasibility() {
        var pattern = recipe("B", "A");
        var graph = CraftGraph.<String>builder().stock("A", 1).pattern(pattern).build();
        var result = UnitMaterialFlow.trySolve(graph, List.of("A", "B"), List.of(pattern),
                Map.of("B", 1L), Map.of(), BoundedIntegerLinearSolver.WorkBudget.bounded(1, 1, Long.MAX_VALUE));
        assertEquals(BoundedIntegerLinearSolver.Status.BUDGET_EXHAUSTED, result.status());
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,
                    () -> solve(graph, List.of("A", "B"), List.of(pattern), Map.of("B", 1L)));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void totalFlowAcrossDifferentMaterialsMayExceedLongWithoutOverflowingAnEdge() {
        var builder = CraftGraph.<String>builder();
        var patterns = new ArrayList<CraftPattern<String>>();
        var items = new ArrayList<String>();
        var demand = new HashMap<String, Long>();
        for (int i = 0; i < 20; i++) {
            var pattern = recipe("P"+i, "R"+i);
            patterns.add(pattern);
            items.add("R"+i);
            items.add("P"+i);
            demand.put("P"+i, Sat.SAT/2);
            builder.stock("R"+i, Sat.SAT/2).pattern(pattern);
        }
        var graph = builder.build();
        var result = solve(graph, items, patterns, demand);
        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status());
        assertBalance(graph, items, demand, result.firings());
    }

    @Test
    void directDeliveryAndRecipeInputsCompeteForTheSameSourceStock() {
        var pattern = recipe("B", "A");
        var graph = CraftGraph.<String>builder().stock("A", 1).pattern(pattern).build();
        var result = solve(graph, List.of("A", "B"), List.of(pattern), Map.of("A", 1L, "B", 1L));
        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status());
    }

    @Test
    void consistentBatchRowsMatchRawFiringEnumerationIncludingPartialStock() {
        var random = new Random(2026092011L);
        for (int sample = 0; sample < 256; sample++) {
            int rawA = 1+random.nextInt(4), rawB = 1+random.nextInt(4);
            int batchP = 1+random.nextInt(4), batchQ = 1+random.nextInt(4);
            int stockA = random.nextInt(14), stockB = random.nextInt(14);
            int stockP = random.nextInt(4), stockQ = random.nextInt(4);
            int needP = random.nextInt(7), needQ = random.nextInt(7);
            boolean feasible = false;
            // Enumerate actual integer recipe executions against original material quantities.
            // This oracle does not divide balance rows or round material quanta.
            for (int ap = 0; ap <= 6; ap++) for (int bp = 0; bp <= 6; bp++)
                for (int aq = 0; aq <= 6; aq++) for (int bq = 0; bq <= 6; bq++)
                    feasible |= rawA*(ap+aq) <= stockA && rawB*(bp+bq) <= stockB
                            && stockP+batchP*(ap+bp) >= needP && stockQ+batchQ*(aq+bq) >= needQ;
            var patterns = List.of(
                    new CraftPattern<>("P", batchP, List.of(CraftInput.of("A", rawA)), "ap"),
                    new CraftPattern<>("P", batchP, List.of(CraftInput.of("B", rawB)), "bp"),
                    new CraftPattern<>("Q", batchQ, List.of(CraftInput.of("A", rawA)), "aq"),
                    new CraftPattern<>("Q", batchQ, List.of(CraftInput.of("B", rawB)), "bq"));
            var builder = CraftGraph.<String>builder().stock("A", stockA).stock("B", stockB)
                    .stock("P", stockP).stock("Q", stockQ);
            patterns.forEach(builder::pattern);
            var graph = builder.build();
            var items = List.of("A", "B", "P", "Q");
            var demand = Map.of("P", (long) needP, "Q", (long) needQ);
            var result = solve(graph, items, patterns, demand);
            assertEquals(feasible ? BoundedIntegerLinearSolver.Status.SOLVED
                    : BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status());
            if (feasible) assertBalance(graph, items, demand, result.firings());
        }
    }

    @Test
    void differentButConsistentBatchRatiosRemainExactAtTrillionScale() {
        long n = 1_000_000_000_000L;
        var ab = new CraftPattern<>("B", 2, List.of(CraftInput.of("A", 3)), "ab");
        var bc = new CraftPattern<>("C", 5, List.of(CraftInput.of("B", 2)), "bc");
        var ct = new CraftPattern<>("T", 1, List.of(CraftInput.of("C", 5)), "ct");
        var graph = CraftGraph.<String>builder().stock("A", 3*n).stock("B", 1).stock("C", 2)
                .pattern(ab).pattern(bc).pattern(ct).build();
        var result = solve(graph, List.of("A", "B", "C", "T"), List.of(ab, bc, ct), Map.of("T", n));
        assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status());
        assertEquals(Map.of(ab, n, bc, n, ct, n), result.firings());
        assertBalance(graph, List.of("A", "B", "C", "T"), Map.of("T", n), result.firings());
    }

    @Test
    void wideBatchAlternativesUseTheSameExactFlowWithoutRoundingAwayDemand() {
        for (long n : new long[] {1, 1_000_000_000_000L}) {
            var graph = assignmentGraph(1024, 128, n, 2026092001L, true);
            var result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> CraftPlannerV2.planDetailed(graph, "T", n));
            assertTrue(result.plan().feasible(), () -> result.diagnostics().toString());
            assertEquals(0, result.diagnostics().lowWidthIntegerNodes());
            var keys = new LinkedHashSet<String>();
            result.plan().firings().forEach((pattern, count) -> {
                keys.add(pattern.output());
                pattern.inputs().forEach(input -> keys.add(input.key()));
            });
            assertBalance(graph, List.copyOf(keys), Map.of("T", n), result.plan().firings());
        }
    }

    @Test
    void leafSupplementsAreParetoMinimalAgainstOriginalFiringEnumeration() {
        var random = new Random(2026092111L);
        for (int sample = 0; sample < 256; sample++) {
            int rawA = 1+random.nextInt(4), rawB = 1+random.nextInt(4);
            int batchP = 1+random.nextInt(4), batchQ = 1+random.nextInt(4);
            int stockA = random.nextInt(14), stockB = random.nextInt(14);
            int stockP = random.nextInt(4), stockQ = random.nextInt(4);
            int needP = 1+random.nextInt(6), needQ = 1+random.nextInt(6);
            var patterns = List.of(
                    new CraftPattern<>("P", batchP, List.of(CraftInput.of("A", rawA)), "ap"),
                    new CraftPattern<>("P", batchP, List.of(CraftInput.of("B", rawB)), "bp"),
                    new CraftPattern<>("Q", batchQ, List.of(CraftInput.of("A", rawA)), "aq"),
                    new CraftPattern<>("Q", batchQ, List.of(CraftInput.of("B", rawB)), "bq"));
            var root = new CraftPattern<>("T", 1,
                    List.of(CraftInput.of("P", needP), CraftInput.of("Q", needQ)), "root");
            var builder = CraftGraph.<String>builder().stock("A", stockA).stock("B", stockB)
                    .stock("P", stockP).stock("Q", stockQ).pattern(root);
            patterns.forEach(builder::pattern);
            var graph = builder.build();
            var demand = Map.of("P", (long) needP, "Q", (long) needQ);
            var result = UnitMaterialFlow.trySolveWithLeafSupply(graph, List.of("A", "B", "P", "Q"),
                    patterns, demand, Map.of(), BoundedIntegerLinearSolver.WorkBudget.unlimited());
            var counts = new java.util.IdentityHashMap<CraftPattern<String>, Long>();
            counts.putAll(result.firings() != null ? result.firings() : result.leafSupplyFirings());
            counts.put(root, 1L);
            var plan = MaterialDagReplay.tryLeafMissingPlan(graph, counts, "T", 1);
            assertNotNull(plan);
            long missingA = plan.missing().getOrDefault("A", 0L);
            long missingB = plan.missing().getOrDefault("B", 0L);
            assertTrue(plan.missing().keySet().stream().allMatch(k -> k.equals("A") || k.equals("B")));
            for (int ap = 0; ap <= 6; ap++) for (int bp = 0; bp <= 6; bp++)
                for (int aq = 0; aq <= 6; aq++) for (int bq = 0; bq <= 6; bq++) {
                    if (stockP+batchP*(ap+bp) < needP || stockQ+batchQ*(aq+bq) < needQ) continue;
                    int a = Math.max(0, rawA*(ap+aq)-stockA), b = Math.max(0, rawB*(bp+bq)-stockB);
                    assertFalse(a <= missingA && b <= missingB && (a < missingA || b < missingB),
                            "dominated physical leaf supplement at sample "+sample);
                }
            var supplied = graph.withAdditionalStock(plan.missing());
            var refill = solve(supplied, List.of("A", "B", "P", "Q"), patterns, demand);
            assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, refill.status());
            assertBalance(supplied, List.of("A", "B", "P", "Q"), demand, refill.firings());
        }
    }

    @Test
    void aProducerOutsideTheComponentDoesNotTurnAnIntermediateIntoANaturalLeaf() {
        var ab = recipe("B", "A");
        var graph = CraftGraph.<String>builder().pattern(ab).pattern(recipe("A", "Z")).build();
        var result = UnitMaterialFlow.trySolveWithLeafSupply(graph, List.of("A", "B"), List.of(ab),
                Map.of("B", 1L), Map.of(), BoundedIntegerLinearSolver.WorkBudget.unlimited());
        assertEquals(BoundedIntegerLinearSolver.Status.INFEASIBLE, result.status());
        assertNull(result.firings());
        assertNull(result.leafSupplyFirings());
    }

    @Test
    void wideRealShortagesReportOneLeafThresholdAndRefillAtTrillionScale() {
        for (int outputs : new int[] {64, 256, 1024, 4096})
            for (long n : new long[] {1, 1_000_000_000_000L})
                for (boolean batched : new boolean[] {false, true}) {
                    var original = assignmentGraph(outputs, Math.max(16, outputs/8), n, 2026092111L, batched);
                    // Remove one physical unit from a known feasible inventory. The missing
                    // batch has a partial remainder, so the answer may be 1 rather than a batch.
                    int raw = 0;
                    while (original.stock("R"+raw) == 0) raw++;
                    var graph = original.withoutStock(Map.of("R"+raw, 1L));
                    var plan = assertTimeoutPreemptively(Duration.ofSeconds(3),
                            () -> CraftPlannerV2.plan(graph, "T", n));
                    assertFalse(plan.feasible());
                    assertEquals(1, plan.missing().size(), () -> "outputs="+outputs+" missing="+plan.missing());
                    var e = plan.missing().entrySet().iterator().next();
                    assertTrue(graph.patternsFor(e.getKey()).isEmpty());
                    long quantum = batched ? 2+Integer.parseInt(e.getKey().substring(1))%3 : 1;
                    assertEquals(quantum - graph.stock(e.getKey())%quantum, e.getValue(),
                            () -> "outputs="+outputs+" n="+n+" batched="+batched+" missing="+plan.missing());
                    var supplied = graph.withAdditionalStock(plan.missing());
                    var refill = assertTimeoutPreemptively(Duration.ofSeconds(3),
                            () -> CraftPlannerV2.plan(supplied, "T", n));
                    assertTrue(refill.feasible());
                }
    }

    private static boolean assign(int[][] choices, int[] available, int output) {
        if (output == choices.length) return true;
        for (int resource : choices[output]) {
            if (available[resource] == 0) continue;
            available[resource]--;
            boolean feasible = assign(choices, available, output+1);
            available[resource]++;
            if (feasible) return true;
        }
        return false;
    }

    private static CraftPattern<String> recipe(String output, String input) {
        return new CraftPattern<>(output, 1, List.of(CraftInput.of(input, 1)), input+"->"+output);
    }

    private static UnitMaterialFlow.Result<String> solve(CraftGraph<String> graph, List<String> items,
            List<CraftPattern<String>> patterns, Map<String, Long> demand) {
        return UnitMaterialFlow.trySolve(graph, items, patterns, demand, Map.of(),
                BoundedIntegerLinearSolver.WorkBudget.unlimited());
    }

    private static void assertBalance(CraftGraph<String> graph, List<String> items,
            Map<String, Long> demand, Map<CraftPattern<String>, Long> counts) {
        var remaining = new HashMap<String, BigInteger>();
        items.forEach(key -> remaining.put(key, BigInteger.valueOf(graph.stock(key))));
        counts.forEach((pattern, count) -> {
            assertTrue(count > 0);
            pattern.inputs().forEach(input -> remaining.merge(input.key(),
                    input.exactAmount().multiply(BigInteger.valueOf(count)).negate(), BigInteger::add));
            remaining.merge(pattern.output(), pattern.exactOutputAmount().multiply(BigInteger.valueOf(count)), BigInteger::add);
        });
        remaining.forEach((key, amount) -> assertTrue(amount.compareTo(BigInteger.valueOf(demand.getOrDefault(key, 0L))) >= 0));
    }

    private static CraftGraph<String> assignmentGraph(int outputs, int resources, long n, long seed) {
        return assignmentGraph(outputs, resources, n, seed, false);
    }

    private static CraftGraph<String> assignmentGraph(int outputs, int resources, long n, long seed, boolean batched) {
        var random = new Random(seed);
        var builder = CraftGraph.<String>builder();
        var root = new ArrayList<CraftInput<String>>();
        int[] stock = new int[resources];
        for (int i = 0; i < outputs; i++) {
            var choices = new LinkedHashSet<Integer>();
            while (choices.size() < 3) choices.add(random.nextInt(resources));
            var options = new ArrayList<>(choices);
            stock[options.get(random.nextInt(options.size()))]++;
            long outputAmount = batched ? 2+i%4 : 1;
            for (int raw : choices) builder.pattern(new CraftPattern<>("P"+i, outputAmount,
                    List.of(CraftInput.of("R"+raw, batched ? 2+raw%3 : 1)), "R"+raw+"->P"+i));
            root.add(CraftInput.of("P"+i, outputAmount));
        }
        for (int i = 0; i < resources; i++) builder.stock("R"+i, n*stock[i]*(batched ? 2+i%3 : 1));
        return builder.pattern("T", 1, root).build();
    }
}
