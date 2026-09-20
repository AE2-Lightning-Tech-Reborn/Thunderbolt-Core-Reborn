package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reduced and generated counterexamples from the independent finite-state small-graph audit. */
class SmallGraphRegressionTest {
    @ParameterizedTest @ValueSource(ints={80,253,475,1419,1963,2599,2931})
    void v2RequiresExecutablePrefixesForSelectedByproductRecipes(int sample) {
        var graph = audited(sample);
        var plan = CraftPlannerV2.plan(graph, "M5", 1);
        assertExecutable(graph, plan);
        var supplied = graph.withAdditionalStock(plan.missing());
        var next = CraftPlannerV2.plan(supplied, "M5", 1);
        assertTrue(next.feasible(), () -> "first="+plan+" refilled="+next);
        assertExecutable(supplied, next);
    }

    @Test void v2ReplenishmentStillBuildsItsSeedAfterTheReportedRawMaterialsAreAdded() {
        var graph = CraftGraph.<String>builder()
                .pattern("M3",1,List.of(CraftInput.of("M1",1),CraftInput.of("M4",2)),List.of(CraftOutput.of("M1",2)))
                .pattern("M1",1,List.of(CraftInput.of("M2",1)))
                .pattern("M0",1,List.of(CraftInput.of("M1",1),CraftInput.of("M2",2)),List.of(CraftOutput.of("M4",1)))
                .pattern("M5",1,List.of(CraftInput.of("M0",1),CraftInput.of("M3",1))).build();
        var first = CraftPlannerV2.plan(graph,"M5",1);
        assertExecutable(graph,first);
        var supplied = graph.withAdditionalStock(first.missing());
        var next = CraftPlannerV2.plan(supplied,"M5",1);
        assertTrue(next.feasible(), () -> "first="+first+" refilled="+next);
        assertExecutable(supplied,next);
    }

    @Test void v2UsesAnExplicitDagCutForAuditedCase1669() {
        var graph = graph(new long[]{0,0,0,1,1,0},
                new int[]{2,1,1,0,0,0,0,0,0,0,0,0,0,0},
                new int[]{0,2,0,1,1,0,0,0,0,0,0,0,0,0},
                new int[]{1,2,1,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{0,2,0,0,0,1,1,0,0,0,0,0,0,0},
                new int[]{5,1,1,0,1,0,0,0,0,0,0,0,0,0});
        var result = CraftPlannerV2.plan(graph,"M5",1);
        assertTrue(result.feasible(), () -> result.toString());
        assertExecutable(graph,result);
    }

    @Test void v2UsesAnExplicitDagCutForAuditedCase4017() {
        var graph = graph(new long[]{0,0,0,0,2,0},
                new int[]{0,1,0,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{2,1,0,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{4,1,0,0,1,0,0,0,0,0,0,0,0,0},
                new int[]{4,1,1,0,0,0,0,0,0,0,0,0,0,0},
                new int[]{5,1,1,0,1,0,0,0,0,0,0,0,0,0});
        var result = CraftPlannerV2.plan(graph,"M5",1);
        assertTrue(result.feasible(), () -> result.toString());
        assertExecutable(graph,result);
    }

    @Test void growingFeedbackStillRequiresACutSeed() {
        // Executing the complete M1/M3 cycle would grow M1. A physically reachable target alone
        // does not admit that route: the user-facing cycle policy must keep its external cut.
        var graph = CraftGraph.<String>builder().stock("M1", 1).stock("M2", 1).stock("M4", 1)
                .pattern("M1", 2, List.of(CraftInput.of("M2", 1), CraftInput.of("M3", 1),
                        CraftInput.of("M4", 1)))
                .pattern("M3", 1, List.of(CraftInput.of("M1", 1)))
                .pattern("M5", 1, List.of(CraftInput.of("M1", 1), CraftInput.of("M3", 1)))
                .build();
        var result = CraftPlannerV2.plan(graph, "M5", 1);
        assertFalse(result.feasible());
        assertFalse(result.budgetExhausted());
        assertExecutable(graph, result);
    }

    @Test void incomingProducerCutsDoNotReplaceWorkingDfsOrientations() {
        // Cutting M4 still leaves the M2/M3 subcycle. DFS from the target cuts the needed M3
        // producer; the original orientation rooted at M3 retains M4 -> M2 -> M3 instead.
        var graph = CraftGraph.<String>builder().stock("M4", 2)
                .pattern("M2", 1, List.of(CraftInput.of("M4", 1)))
                .pattern("M4", 1, List.of(CraftInput.of("M3", 1)))
                .pattern("M3", 1, List.of(CraftInput.of("M2", 1)))
                .pattern("M2", 1, List.of(CraftInput.of("M3", 1)))
                .pattern("M5", 1, List.of(CraftInput.of("M2", 1), CraftInput.of("M3", 1)))
                .build();
        var result = CraftPlannerV2.plan(graph, "M5", 1);
        assertTrue(result.feasible());
        assertExecutable(graph, result);
    }

    @Test void aComplexComponentCanKeepTwoIndependentCutBoundaries() {
        var graph = CraftGraph.<String>builder().stock("M0", 2).stock("M2", 2)
                .pattern("M2", 2, List.of(CraftInput.of("M4", 2)))
                .pattern("M0", 1, List.of(CraftInput.of("M1", 1)))
                .pattern("M3", 1, List.of(CraftInput.of("M2", 1)))
                .pattern("M4", 2, List.of(CraftInput.of("M0", 1), CraftInput.of("M1", 1),
                        CraftInput.of("M3", 1)))
                .pattern("M1", 1, List.of(CraftInput.of("M0", 1)))
                .pattern("M1", 2, List.of(CraftInput.of("M0", 1), CraftInput.of("M3", 1)))
                .pattern("M5", 1, List.of(CraftInput.of("M3", 1), CraftInput.of("M4", 1)))
                .build();
        var result = CraftPlannerV2.plan(graph, "M5", 1);
        assertTrue(result.feasible());
        assertExecutable(graph, result);
    }

    @Test void aCutCanHideBehindTheMissingRawMaterialOfACompetingRoute() {
        var graph = CraftGraph.<String>builder().stock("M2", 2).stock("M3", 1).stock("M4", 3)
                .pattern("M2", 1, List.of(CraftInput.of("M4", 1)))
                .pattern("M0", 1, List.of(CraftInput.of("M1", 1)))
                .pattern("M0", 1, List.of(CraftInput.of("M2", 1), CraftInput.of("M3", 1)))
                .pattern("M3", 1, List.of(CraftInput.of("M2", 2), CraftInput.of("M4", 1)))
                .pattern("M2", 1, List.of(CraftInput.of("M1", 1), CraftInput.of("M3", 1),
                        CraftInput.of("M4", 1)))
                .pattern("M5", 1, List.of(CraftInput.of("M0", 2)))
                .build();
        var result = CraftPlannerV2.plan(graph, "M5", 1);
        assertTrue(result.feasible());
        assertExecutable(graph, result);
    }

    @Test void manufacturingAFeedbackSeedCannotReuseCommittedRawStock() {
        var graph = CraftGraph.<String>builder().stock("M2", 2).stock("M4", 2)
                .pattern("M3", 1, List.of(CraftInput.of("M1", 1), CraftInput.of("M4", 2)),
                        List.of(CraftOutput.of("M1", 2)))
                .pattern("M1", 1, List.of(CraftInput.of("M2", 1)))
                .pattern("M0", 1, List.of(CraftInput.of("M1", 1), CraftInput.of("M2", 2)),
                        List.of(CraftOutput.of("M4", 1)))
                .pattern("M5", 1, List.of(CraftInput.of("M0", 1), CraftInput.of("M3", 1)))
                .build();
        var result = CraftPlannerV2.plan(graph, "M5", 1);
        assertFalse(result.feasible());
        assertExecutable(graph, result);
        var supplied = graph.withAdditionalStock(result.missing());
        var next = CraftPlannerV2.plan(supplied, "M5", 1);
        assertTrue(next.feasible());
        assertExecutable(supplied, next);
    }

    @ParameterizedTest
    @ValueSource(ints = {168, 929, 936, 2302, 2462, 2650, 2664})
    void usefulPrimaryProductionCanSupplyAnotherBranchesByproduct(int sample) {
        var graph = productiveByproductAudit(sample);
        var plan = CraftPlannerV2.plan(graph, "M5", 1);
        assertTrue(plan.feasible(), () -> plan.toString());
        assertFalse(plan.budgetExhausted());
        assertExecutable(graph, plan);
    }

    @Test void anUnneededPrimaryStillCannotBeManufacturedSolelyForItsByproduct() {
        var graph = CraftGraph.<String>builder().stock("M1", 1).stock("M2", 2).stock("M3", 3)
                .pattern("M3", 1, List.of(CraftInput.of("M1", 1), CraftInput.of("M2", 1)),
                        List.of(CraftOutput.of("M4", 1)))
                .pattern("M2", 1, List.of(CraftInput.of("M0", 1)))
                .pattern("M2", 1, List.of(CraftInput.of("M3", 1), CraftInput.of("M0", 1)))
                .pattern("M4", 1, List.of(CraftInput.of("M0", 1)))
                .pattern("M5", 1, List.of(CraftInput.of("M2", 1), CraftInput.of("M4", 1)))
                .build();
        var plan = CraftPlannerV2.plan(graph, "M5", 1);
        assertFalse(plan.feasible());
        assertExecutable(graph, plan);
    }

    private static CraftGraph<String> productiveByproductAudit(int sample) {
        return switch (sample) {
            case 168 -> graph(new long[]{0, 2, 1, 0, 0, 0},
                new int[]{2, 1, 0, 2, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0},
                new int[]{5, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            case 929 -> graph(new long[]{1, 1, 1, 0, 0, 0},
                new int[]{2, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0},
                new int[]{5, 1, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0});
            case 936 -> graph(new long[]{0, 0, 3, 0, 1, 0},
                new int[]{4, 2, 0, 0, 3, 0, 0, 0, 0, 1, 0, 0, 0, 0},
                new int[]{5, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0});
            case 2302 -> graph(new long[]{0, 1, 0, 2, 0, 0},
                new int[]{0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new int[]{4, 2, 1, 1, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0},
                new int[]{1, 2, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                new int[]{0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                new int[]{4, 3, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0},
                new int[]{5, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            case 2462 -> graph(new long[]{0, 0, 2, 0, 0, 0},
                new int[]{1, 2, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                new int[]{2, 3, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                new int[]{4, 1, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 0, 0},
                new int[]{5, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            case 2650 -> graph(new long[]{0, 1, 0, 2, 0, 0},
                new int[]{1, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 1, 0},
                new int[]{5, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0});
            case 2664 -> graph(new long[]{0, 0, 1, 1, 0, 0},
                new int[]{0, 2, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                new int[]{3, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                new int[]{1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0},
                new int[]{5, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            default -> throw new IllegalArgumentException();
        };
    }

    static void assertExecutable(CraftGraph<String> graph,CraftPlan<String> plan) {
        var patterns = new ArrayList<>(plan.firings().keySet());
        long[][] pre = new long[patterns.size()][6],post = new long[patterns.size()][6];
        long[] counts = new long[patterns.size()], stock = new long[6];
        for (int i=0;i<6;i++) {
            long drawn=plan.usedStock().getOrDefault("M"+i,0L);
            assertTrue(drawn>=0 && drawn<=graph.stock("M"+i));
            stock[i]=drawn+plan.missing().getOrDefault("M"+i,0L);
        }
        for (int r=0;r<patterns.size();r++) {
            var pattern=patterns.get(r); counts[r]=plan.firings().get(pattern);
            for (var input:pattern.inputs()) pre[r][Integer.parseInt(input.key().substring(1))]+=input.amount();
            post[r][Integer.parseInt(pattern.output().substring(1))]+=pattern.outputAmount();
            for (var output:pattern.byproducts()) post[r][Integer.parseInt(output.key().substring(1))]+=output.amount();
        }
        assertTrue(PetriExecutionVerifierTest.oracle(pre,post,counts,stock,5,1,new HashSet<>()),
                () -> "non-executable plan: "+plan);
    }
    private static CraftGraph<String> graph(long[] stock,int[]... rows) {
        var b=CraftGraph.<String>builder();
        for(int i=0;i<6;i++) b.stock("M"+i,stock[i]);
        for(int[] row:rows) {
            var pre=new ArrayList<CraftInput<String>>(); var by=new ArrayList<CraftOutput<String>>();
            for(int i=0;i<6;i++) {
                if(row[2+i]>0) pre.add(CraftInput.of("M"+i,row[2+i]));
                if(row[8+i]>0) by.add(CraftOutput.of("M"+i,row[8+i]));
            }
            b.pattern("M"+row[0],row[1],pre,by);
        }
        return b.build();
    }
    private static CraftGraph<String> audited(int sample) {
        return switch(sample) {
            case 80 -> graph(new long[]{0,2,0,3,1,0},
                new int[]{1,1,1,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{0,1,0,0,1,1,1,0,0,0,0,0,2,0},
                new int[]{2,2,1,0,0,2,0,0,0,1,0,0,0,0},
                new int[]{3,3,0,2,1,0,0,0,0,0,0,0,0,0},
                new int[]{1,1,1,0,0,0,0,0,0,0,0,0,0,0},
                new int[]{3,1,0,3,0,0,0,0,0,0,0,0,0,0},
                new int[]{1,1,0,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{3,2,0,1,0,0,1,0,0,0,0,0,0,0},
                new int[]{0,1,0,1,0,0,1,0,0,1,0,0,0,0},
                new int[]{5,1,1,0,1,0,0,0,0,0,0,0,0,0});
            case 253 -> graph(new long[]{2,1,0,0,3,0},
                new int[]{0,1,0,1,0,0,0,0,0,0,0,0,0,0},
                new int[]{1,2,1,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{0,1,0,2,0,0,0,0,0,0,0,1,0,0},
                new int[]{2,1,1,0,0,2,0,0,0,0,0,0,0,0},
                new int[]{0,1,0,0,1,1,0,0,0,0,0,0,0,0},
                new int[]{2,1,0,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{2,2,1,0,0,0,2,0,1,0,0,0,0,0},
                new int[]{3,2,0,2,1,0,0,0,0,1,0,0,0,0},
                new int[]{0,1,0,1,0,0,0,0,0,0,0,0,0,0},
                new int[]{5,1,1,0,0,1,0,0,0,0,0,0,0,0});
            case 475 -> graph(new long[]{0,1,2,1,2,0},
                new int[]{2,1,1,0,0,0,0,0,0,0,0,0,0,0},
                new int[]{4,2,0,2,0,0,0,0,0,0,0,0,0,0},
                new int[]{4,1,0,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{3,1,1,0,0,0,0,0,0,0,0,0,0,0},
                new int[]{0,1,0,1,1,0,1,0,0,0,2,0,0,0},
                new int[]{3,3,0,0,2,0,1,0,0,0,0,0,0,0},
                new int[]{4,1,1,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{2,1,0,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{3,1,1,0,0,0,0,0,0,0,0,0,0,0},
                new int[]{5,1,1,0,1,0,0,0,0,0,0,0,0,0});
            case 1419 -> graph(new long[]{2,0,1,0,3,0},
                new int[]{4,1,1,0,0,0,0,0,0,0,0,0,0,0},
                new int[]{0,1,0,0,1,0,0,0,0,0,0,0,0,0},
                new int[]{2,1,0,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{3,1,0,0,1,0,2,0,0,0,2,0,0,0},
                new int[]{0,1,0,1,0,1,0,0,0,1,0,0,0,0},
                new int[]{4,1,0,1,1,1,0,0,0,0,0,1,0,0},
                new int[]{0,1,0,1,0,0,0,0,0,0,0,0,0,0},
                new int[]{2,1,0,1,0,0,0,0,0,0,0,0,0,0},
                new int[]{1,1,0,0,1,0,1,0,0,0,0,0,0,0},
                new int[]{5,1,0,0,0,2,0,0,0,0,0,0,0,0});
            case 1963 -> graph(new long[]{1,2,2,0,1,0},
                new int[]{0,1,0,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{2,1,0,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{2,2,1,1,0,1,0,0,0,1,0,0,0,0},
                new int[]{2,2,2,1,0,0,0,0,0,0,0,0,0,0},
                new int[]{4,1,0,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{0,1,0,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{3,1,0,1,1,0,1,0,0,0,2,0,0,0},
                new int[]{0,1,0,0,1,0,1,0,0,0,0,0,0,0},
                new int[]{0,1,0,0,1,0,0,0,0,0,0,0,0,0},
                new int[]{5,1,0,0,0,2,0,0,0,0,0,0,0,0});
            case 2599 -> graph(new long[]{0,2,0,3,1,0},
                new int[]{2,2,2,0,0,0,1,0,0,0,0,1,0,0},
                new int[]{3,1,0,0,2,0,0,0,0,1,0,0,0,0},
                new int[]{2,2,1,2,0,0,0,0,0,0,0,1,0,0},
                new int[]{1,1,0,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{3,3,2,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{0,2,0,1,0,1,0,0,0,0,0,0,0,0},
                new int[]{1,2,1,0,0,0,1,0,0,0,0,0,0,0},
                new int[]{4,1,0,0,1,1,0,0,0,0,1,0,0,0},
                new int[]{1,2,2,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{5,1,0,0,2,0,0,0,0,0,0,0,0,0});
            case 2931 -> graph(new long[]{1,1,0,1,3,0},
                new int[]{2,1,0,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{2,1,0,1,0,0,1,0,0,0,0,0,0,0},
                new int[]{0,2,0,1,1,0,0,0,0,0,0,0,0,0},
                new int[]{2,1,2,0,0,0,0,0,0,0,0,0,0,0},
                new int[]{4,1,0,0,1,0,0,0,0,0,0,0,0,0},
                new int[]{2,2,1,1,0,1,0,0,1,0,0,0,0,0},
                new int[]{0,1,0,0,0,1,0,0,0,0,0,0,0,0},
                new int[]{2,1,0,2,0,0,0,0,0,0,0,0,1,0},
                new int[]{3,1,1,0,0,0,1,0,1,0,0,0,0,0},
                new int[]{5,1,0,0,2,0,0,0,0,0,0,0,0,0});
            default -> throw new IllegalArgumentException();
        };
    }
}
