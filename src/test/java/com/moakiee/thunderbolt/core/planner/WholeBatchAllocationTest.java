package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WholeBatchAllocationTest {
    @Test
    void exhaustiveWholeRouteOracleAgreesOnSixHundredRandomAllocations() {
        var random = new Random(2026091819);
        for (int sample = 0; sample < 600; sample++) {
            var builder = CraftGraph.<String>builder();
            var patterns = new ArrayList<CraftPattern<String>>();
            var demand = new java.util.LinkedHashMap<String, Long>();
            long[][][] use = new long[3][3][4];
            long[] cap = new long[4];
            for (int r = 0; r < 4; r++) builder.stock("R"+r, cap[r] = random.nextInt(12));
            for (int g = 0; g < 3; g++) {
                long batch = 1+random.nextInt(3), request = 1+random.nextInt(5);
                demand.put("P"+g, request);
                long count = (request+batch-1)/batch;
                for (int a = 0; a < 3; a++) {
                    var inputs = new ArrayList<CraftInput<String>>();
                    for (int slot = 0; slot < 2; slot++) {
                        int r = random.nextInt(4); long amount = 1+random.nextInt(2);
                        inputs.add(CraftInput.of("R"+r, amount));
                        use[g][a][r] += amount*count;
                    }
                    var p = new CraftPattern<>("P"+g, batch, inputs, null);
                    builder.pattern(p); patterns.add(p);
                }
            }
            var result = solve(builder.build(), patterns, demand, Map.of());
            boolean possible = false;
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) for (int c = 0; c < 3; c++) {
                boolean fits = true;
                for (int r = 0; r < 4; r++) fits &= use[0][a][r]+use[1][b][r]+use[2][c][r] <= cap[r];
                possible |= fits;
            }
            assertEquals(possible, result != null, "sample " + sample);
            if (result != null) {
                assertEquals(BoundedIntegerLinearSolver.Status.SOLVED, result.status());
                long[] consumed = new long[4];
                result.firings().forEach((p, n) -> p.inputs().forEach(i -> consumed[Integer.parseInt(i.key().substring(1))] += i.amount()*n));
                for (int r = 0; r < 4; r++) assertTrue(consumed[r] <= cap[r]);
            }
        }
    }

    @Test
    void splitOnlySolutionFallsThroughInsteadOfProvingInfeasibility() {
        var a = pair("P", "A", "B"); var b = pair("P", "C", "D");
        var graph = CraftGraph.<String>builder().pattern(a).pattern(b)
                .stock("A", 1).stock("B", 1).stock("C", 1).stock("D", 1).build();
        assertNull(solve(graph, List.of(a,b), Map.of("P", 2L), Map.of()));
        assertTrue(CraftPlannerV2.plan(graph, "P", 2).feasible());
    }

    @Test
    void externalSupplyAndRawDemandShareTheSameStock() {
        var a = pair("P", "A", "B"); var b = pair("P", "C", "D");
        var graph = CraftGraph.<String>builder().pattern(a).pattern(b).stock("A", 1).build();
        assertNotNull(solve(graph, List.of(a,b), Map.of("P", 1L), Map.of("B", 1L)));
        assertNull(solve(graph, List.of(a,b), Map.of("P", 1L, "A", 1L), Map.of("B", 1L)));
    }

    @Test
    void hugeBatchesAndOnePhysicalUnitShortUseExactArithmetic() {
        var a = pair("P", "A", "B"); var b = pair("P", "C", "D");
        long n = 1_000_000_000_000L;
        var graph = CraftGraph.<String>builder().pattern(a).pattern(b).stock("A", n).stock("B", n).build();
        assertNotNull(solve(graph, List.of(a,b), Map.of("P", n), Map.of()));
        assertNull(solve(graph.withoutStock(Map.of("B", 1L)), List.of(a,b), Map.of("P", n), Map.of()));
    }

    @Test
    void statefulRecipesAndNonLeafInputsAreNotAdmitted() {
        var returned = new CraftPattern<>("P", 1, List.of(CraftInput.returned("A", 1)), null);
        var graph = CraftGraph.<String>builder().pattern(returned).stock("A", 1).build();
        assertNull(solve(graph, List.of(returned), Map.of("P", 1L), Map.of()));
        var ordinary = pair("P", "A", "B");
        graph = CraftGraph.<String>builder().pattern(ordinary).pattern("A", 1, List.of(CraftInput.of("C", 1)))
                .stock("A", 1).stock("B", 1).build();
        assertNull(solve(graph, List.of(ordinary), Map.of("P", 1L), Map.of()));
    }

    @Test
    void exhaustedBudgetCannotReturnAnUnverifiedAssignment() {
        var p = pair("P", "A", "B");
        var graph = CraftGraph.<String>builder().pattern(p).stock("A", 1).stock("B", 1).build();
        assertNull(WholeBatchAllocation.trySolve(graph, List.of("P","A","B"), List.of(p), Map.of("P",1L), Map.of(),
                BoundedIntegerLinearSolver.WorkBudget.bounded(1,1,Long.MAX_VALUE)));
    }

    private static CraftPattern<String> pair(String p, String a, String b) {
        return new CraftPattern<>(p, 1, List.of(CraftInput.of(a, 1), CraftInput.of(b, 1)), null);
    }

    private static UnitMaterialFlow.Result<String> solve(CraftGraph<String> graph, List<CraftPattern<String>> patterns,
            Map<String, Long> demand, Map<String, Long> supply) {
        var items = new LinkedHashSet<>(demand.keySet());
        patterns.forEach(p -> {items.add(p.output()); p.inputs().forEach(i -> items.add(i.key()));});
        return WholeBatchAllocation.trySolve(graph, List.copyOf(items), patterns, demand, supply,
                BoundedIntegerLinearSolver.WorkBudget.unlimited());
    }
}
