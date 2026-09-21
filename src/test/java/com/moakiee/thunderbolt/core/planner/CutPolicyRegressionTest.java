package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Counterexamples found by independent small-graph search, repeated at quantity scale. */
class CutPolicyRegressionTest {
    @ParameterizedTest @ValueSource(longs = {1L, 1_000_000L, 1_000_000_000_000L})
    void upstreamStockCutsSurviveSmallIntermediateStock(long scale) {
        var graph = fixture("primary/3532", scale);
        var result = CraftPlannerV2.planDetailed(graph, "M5", scale);
        assertTrue(result.plan().feasible(), () -> result.toString());
        assertBatchExecutable(graph, result.plan(), scale);
        assertTrue(result.diagnostics().planRuns() <= 17);
    }

    @ParameterizedTest @ValueSource(longs = {1L, 1_000_000L, 1_000_000_000_000L})
    void twoStockedLeavesKeepTheNeededIntermediateProducer(long scale) {
        var graph = fixture("primary/5215", scale);
        var result = CraftPlannerV2.planDetailed(graph, "M5", scale);
        assertTrue(result.plan().feasible(), () -> result.toString());
        assertBatchExecutable(graph, result.plan(), scale);
        assertTrue(result.diagnostics().planRuns() <= 17);
    }

    @ParameterizedTest @ValueSource(longs = {1L, 1_000_000L, 1_000_000_000_000L})
    void unfundedRawShortcutDoesNotHideAnExecutableRoute(long scale) {
        var graph = fixture("primary/5516", scale);
        var result = CraftPlannerV2.planDetailed(graph, "M5", scale);
        assertTrue(result.plan().feasible(), () -> result.toString());
        assertBatchExecutable(graph, result.plan(), scale);
        assertTrue(result.diagnostics().planRuns() <= 17);
    }

    @ParameterizedTest
    @ValueSource(strings = {"primary/277", "primary/506", "primary/6443", "primary/6688",
            "focused/185", "focused/325", "focused/1324", "focused/4018",
            "byproduct/1987"})
    void smallerSupplyRemainsExecutableAfterOptionalSearchIsSpent(String sample) {
        var graph = fixture(sample, 1);
        var plan = CraftPlannerV2.plan(graph, "M5", 1);
        assertFalse(plan.feasible());
        assertBatchExecutable(graph, plan, 1);
        var supplied = graph.withAdditionalStock(plan.missing());
        var recheck = CraftPlannerV2.planDetailed(supplied, "M5", 1, 1, 1);
        assertTrue(recheck.plan().feasible(), () -> sample + ": " + recheck);
        assertBatchExecutable(supplied, recheck.plan(), 1);
        for (String key : plan.missing().keySet()) {
            var stock = new long[6];
            for (int i = 0; i < stock.length; i++) stock[i] = supplied.stock("M" + i);
            stock[Integer.parseInt(key.substring(1))]--;
            assertFalse(physicallyReachable(graph, stock), () -> sample + ": excess supply " + plan);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"byproduct/101", "byproduct/2819"})
    void ordinaryRoutesRemainAvailableBesideUnusedByproductAlternatives(String sample) {
        var graph = fixture(sample, 1);
        var plan = CraftPlannerV2.plan(graph, "M5", 1);
        assertEquals(sample.endsWith("101") ? Map.of("M1", 1L) : Map.of("M2", 1L), plan.missing());
        assertBatchExecutable(graph, plan, 1);
        var supplied = graph.withAdditionalStock(plan.missing());
        var next = CraftPlannerV2.planDetailed(supplied, "M5", 1, 1, 1);
        assertTrue(next.plan().feasible());
        assertBatchExecutable(supplied, next.plan(), 1);
        // The unrestricted physical oracle can do still better by using unsupported byproduct
        // routes. This test asserts the ordinary-family improvement, not global optimality.
    }

    /** Exhaustive unit-firing oracle, independent of the planner's DAG and amount propagation. */
    private static boolean physicallyReachable(CraftGraph<String> graph, long[] initial) {
        var recipes = new ArrayList<CraftPattern<String>>();
        for (int i = 0; i < 6; i++) recipes.addAll(graph.patternsFor("M" + i));
        var todo = new ArrayDeque<long[]>();
        var seen = new HashSet<String>();
        todo.add(initial); seen.add(Arrays.toString(initial));
        while (!todo.isEmpty()) {
            long[] stock = todo.removeFirst();
            if (stock[5] >= 1) return true;
            for (var recipe : recipes) {
                var next = stock.clone();
                for (var input : recipe.inputs()) next[Integer.parseInt(input.key().substring(1))] -= input.amount();
                if (Arrays.stream(next).anyMatch(value -> value < 0)) continue;
                next[Integer.parseInt(recipe.output().substring(1))] += recipe.outputAmount();
                for (var output : recipe.byproducts()) next[Integer.parseInt(output.key().substring(1))] += output.amount();
                if (seen.add(Arrays.toString(next))) {
                    assertTrue(seen.size() < 100_000, "oracle state bound");
                    todo.addLast(next);
                }
            }
        }
        return false;
    }

    private static CraftGraph<String> fixture(String sample, long scale) {
        return switch (sample) {
            case "primary/3532" -> graph(scale, new long[] {0, 1, 3, 0, 2, 0},
                    new int[] {0, 2, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 2, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 2, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0});
            case "primary/5215" -> graph(scale, new long[] {1, 0, 1, 2, 2, 0},
                    new int[] {0, 1, 0, 0, 0, 1, 2, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 2, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 2, 0, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 3, 1, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            case "primary/5516" -> graph(scale, new long[] {1, 0, 2, 2, 1, 0},
                    new int[] {1, 1, 2, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 2, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 2, 0, 0, 2, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 2, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 2, 0, 1, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0});
            case "primary/277" -> graph(scale, new long[] {0, 0, 3, 0, 3, 0},
                    new int[] {1, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 2, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 3, 2, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 1, 0, 0, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 3, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            case "primary/506" -> graph(scale, new long[] {3, 3, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 1, 0, 1, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 2, 0, 0, 0, 1, 2, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0});
            case "primary/6443" -> graph(scale, new long[] {0, 2, 1, 0, 3, 0},
                    new int[] {4, 2, 2, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 3, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 2, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0});
            case "primary/6688" -> graph(scale, new long[] {0, 1, 3, 2, 0, 0},
                    new int[] {1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 1, 0, 1, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 3, 2, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 3, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 2, 1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 2, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0});
            case "focused/185" -> graph(scale, new long[] {2, 0, 0, 1, 1, 0},
                    new int[] {1, 2, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 2, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 1, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 1, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 2, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            case "focused/325" -> graph(scale, new long[] {0, 3, 0, 1, 0, 0},
                    new int[] {4, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 2, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0});
            case "focused/1324" -> graph(scale, new long[] {0, 3, 1, 0, 0, 0},
                    new int[] {1, 2, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 2, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0});
            case "focused/4018" -> graph(scale, new long[] {0, 0, 0, 0, 4, 0},
                    new int[] {1, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 2, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 2, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0});
            case "byproduct/101" -> graph(scale, new long[] {0, 1, 2, 3, 0, 0},
                    new int[] {3, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0},
                    new int[] {2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 2, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 2, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 2, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 2, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0});
            case "byproduct/1987" -> graph(scale, new long[] {3, 3, 0, 0, 0, 0},
                    new int[] {1, 1, 0, 0, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 3, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 2, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {4, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 1, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0},
                    new int[] {3, 1, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0},
                    new int[] {5, 1, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0});
            case "byproduct/2819" -> graph(scale, new long[] {1, 0, 1, 0, 4, 0},
                    new int[] {0, 2, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 3, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 3, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {2, 3, 0, 0, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {1, 3, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {0, 2, 0, 0, 0, 2, 1, 0, 0, 1, 0, 0, 0, 0},
                    new int[] {1, 3, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {3, 3, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0},
                    new int[] {5, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0});
            default -> throw new IllegalArgumentException(sample);
        };
    }

    private static CraftGraph<String> graph(long scale, long[] stock, int[]... recipes) {
        var builder = CraftGraph.<String>builder();
        for (int i = 0; i < stock.length; i++) builder.stock("M" + i, Math.multiplyExact(stock[i], scale));
        for (int[] recipe : recipes) {
            var inputs = new ArrayList<CraftInput<String>>();
            var byproducts = new ArrayList<CraftOutput<String>>();
            for (int i = 0; i < stock.length; i++) {
                if (recipe[2 + i] > 0) inputs.add(CraftInput.of("M" + i, recipe[2 + i]));
                if (recipe[8 + i] > 0) byproducts.add(CraftOutput.of("M" + i, recipe[8 + i]));
            }
            builder.pattern("M" + recipe[0], recipe[1], inputs, byproducts);
        }
        return builder.build();
    }

    static void assertBatchExecutable(CraftGraph<String> graph, CraftPlan<String> plan, long amount) {
        var inventory = new HashMap<String, BigInteger>();
        plan.usedStock().forEach((key, value) -> {
            assertTrue(value >= 0 && value <= graph.stock(key));
            inventory.put(key, BigInteger.valueOf(value));
        });
        plan.missing().forEach((key, value) -> inventory.merge(key, BigInteger.valueOf(value), BigInteger::add));
        var remaining = new LinkedHashMap<>(plan.firings());
        while (!remaining.isEmpty()) {
            boolean progress = false;
            var iterator = remaining.entrySet().iterator();
            while (iterator.hasNext()) {
                var firing = iterator.next();
                var pattern = firing.getKey();
                var count = BigInteger.valueOf(firing.getValue());
                var inputs = new HashMap<String, BigInteger>();
                pattern.inputs().forEach(input -> inputs.merge(input.key(),
                        input.exactAmount().multiply(count), BigInteger::add));
                if (inputs.entrySet().stream().anyMatch(input -> inventory.getOrDefault(
                        input.getKey(), BigInteger.ZERO).compareTo(input.getValue()) < 0)) continue;
                inputs.forEach((key, value) -> inventory.merge(key, value.negate(), BigInteger::add));
                inventory.merge(pattern.output(), pattern.exactOutputAmount().multiply(count), BigInteger::add);
                pattern.byproducts().forEach(output -> inventory.merge(output.key(),
                        BigInteger.valueOf(output.amount()).multiply(count), BigInteger::add));
                iterator.remove(); progress = true;
            }
            assertTrue(progress, () -> "Selected firings cannot finish: " + plan);
        }
        assertTrue(inventory.getOrDefault("M5", BigInteger.ZERO).compareTo(BigInteger.valueOf(amount)) >= 0);
    }
}
