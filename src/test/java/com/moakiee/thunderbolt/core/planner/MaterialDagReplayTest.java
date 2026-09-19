package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fixed counterexamples from the independent unit-firing small-graph oracle. */
class MaterialDagReplayTest {
    private static void check(CraftGraph<String> graph) {
        check(graph, "M5", 1);
    }

    private static void check(CraftGraph<String> graph, String target, long amount) {
        var result = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> CraftPlannerV2.planDetailed(graph, target, amount));
        var plan = result.plan();
        assertTrue(plan.feasible(), () -> result.toString());
        plan.usedStock().forEach((key, n) -> assertTrue(n <= graph.stock(key)));
        plan.firings().keySet().forEach(pattern ->
                assertTrue(graph.patternsFor(pattern.output()).contains(pattern), "export the original recipe identity"));
        if (amount <= 3) ByproductReplaySafetyTest.assertEveryOrderFinishes(plan, target, amount);
        assertTrue(result.diagnostics().consumedSearchBudget() <= result.diagnostics().configuredSearchBudget());
    }

    private static CraftPattern<String> recipe(String output, long amount,
            Map<String, Long> inputs, Map<String, Long> byproducts) {
        var in = new ArrayList<CraftInput<String>>();
        var out = new ArrayList<CraftOutput<String>>();
        inputs.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> in.add(CraftInput.of(e.getKey(), e.getValue())));
        byproducts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.add(CraftOutput.of(e.getKey(), e.getValue())));
        return new CraftPattern<>(output, amount, in, out, output);
    }

    @Test
    void preservesConservativeLoop14() {
        check(CraftGraph.<String>builder()
                .stock("M1", 2)
                .stock("M3", 1)
                .stock("M4", 3)
                .pattern(recipe("M3", 2, Map.of("M1", 2L, "M2", 1L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M0", 1L, "M2", 1L), Map.of("M0", 1L)))
                .pattern(recipe("M4", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M4", 2L), Map.of("M4", 1L)))
                .pattern(recipe("M1", 1, Map.of("M2", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M3", 2, Map.of("M0", 1L, "M2", 2L), Map.of("M0", 1L)))
                .pattern(recipe("M2", 2, Map.of("M0", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M3", 2, Map.of("M0", 1L, "M1", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M0", 1L, "M2", 1L), Map.of()))
                .build());
    }

    @Test
    void preservesConservativeLoop3261() {
        check(CraftGraph.<String>builder()
                .stock("M1", 2)
                .stock("M2", 3)
                .stock("M3", 1)
                .pattern(recipe("M0", 3, Map.of("M2", 1L, "M3", 2L), Map.of()))
                .pattern(recipe("M4", 2, Map.of("M3", 2L), Map.of()))
                .pattern(recipe("M1", 3, Map.of("M0", 2L, "M2", 1L), Map.of()))
                .pattern(recipe("M4", 3, Map.of("M0", 2L, "M1", 1L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M1", 3L), Map.of("M0", 1L)))
                .pattern(recipe("M3", 1, Map.of("M0", 1L, "M2", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M2", 2L), Map.of("M1", 1L)))
                .pattern(recipe("M5", 1, Map.of("M3", 1L, "M4", 1L), Map.of()))
                .build());
    }

    @Test
    void recoversMaterialDag4192() {
        check(CraftGraph.<String>builder()
                .stock("M0", 1)
                .stock("M2", 2)
                .stock("M3", 2)
                .stock("M4", 1)
                .pattern(recipe("M2", 2, Map.of("M0", 1L, "M1", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M0", 1L, "M1", 1L, "M2", 1L), Map.of("M2", 1L)))
                .pattern(recipe("M0", 2, Map.of("M1", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M0", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M1", 1L, "M2", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M0", 1L, "M2", 1L, "M3", 1L), Map.of("M1", 2L)))
                .pattern(recipe("M2", 2, Map.of("M1", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M1", 1L, "M4", 1L), Map.of()))
                .build());
    }

    @Test
    void recoversMaterialDag4554() {
        check(CraftGraph.<String>builder()
                .stock("M2", 2)
                .stock("M3", 3)
                .stock("M4", 1)
                .pattern(recipe("M3", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M2", 2, Map.of("M0", 2L), Map.of()))
                .pattern(recipe("M1", 3, Map.of("M2", 1L, "M3", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M4", 2, Map.of("M1", 2L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M3", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M1", 1L, "M3", 1L), Map.of("M2", 1L)))
                .pattern(recipe("M1", 3, Map.of("M3", 2L, "M4", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M0", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M0", 1L, "M1", 1L), Map.of()))
                .build());
    }

    @Test
    void recoversMaterialDag6014() {
        check(CraftGraph.<String>builder()
                .stock("M0", 1)
                .stock("M1", 1)
                .stock("M2", 2)
                .stock("M3", 2)
                .pattern(recipe("M0", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M0", 1L, "M1", 1L), Map.of("M4", 1L)))
                .pattern(recipe("M1", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M0", 1L), Map.of()))
                .pattern(recipe("M0", 2, Map.of("M1", 2L, "M4", 1L), Map.of("M1", 1L)))
                .pattern(recipe("M3", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M0", 1L, "M2", 1L), Map.of("M2", 1L)))
                .pattern(recipe("M0", 2, Map.of("M1", 1L, "M2", 1L, "M4", 1L), Map.of("M4", 1L)))
                .pattern(recipe("M2", 1, Map.of("M1", 1L, "M3", 2L), Map.of("M4", 2L)))
                .pattern(recipe("M5", 1, Map.of("M2", 1L, "M4", 1L), Map.of()))
                .build());
    }

    @Test
    void recoversMaterialDag7101() {
        check(CraftGraph.<String>builder()
                .stock("M0", 1)
                .stock("M2", 2)
                .stock("M3", 3)
                .pattern(recipe("M3", 3, Map.of("M0", 2L, "M4", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M3", 3, Map.of("M2", 2L, "M4", 1L), Map.of()))
                .pattern(recipe("M1", 3, Map.of("M0", 2L, "M3", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M0", 1L, "M2", 1L), Map.of("M1", 1L)))
                .pattern(recipe("M4", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M0", 2, Map.of("M1", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M1", 1L, "M4", 1L), Map.of()))
                .build());
    }

    @Test
    void recoversMaterialDag8089() {
        check(CraftGraph.<String>builder()
                .stock("M0", 3)
                .stock("M1", 2)
                .stock("M3", 1)
                .pattern(recipe("M2", 3, Map.of("M3", 2L, "M4", 1L), Map.of()))
                .pattern(recipe("M2", 2, Map.of("M0", 1L, "M1", 1L, "M4", 1L), Map.of("M4", 1L)))
                .pattern(recipe("M0", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M1", 3, Map.of("M2", 1L, "M3", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M0", 1L, "M1", 1L, "M3", 1L), Map.of("M2", 1L)))
                .pattern(recipe("M3", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M2", 2, Map.of("M0", 2L, "M4", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M0", 1L, "M4", 1L), Map.of("M1", 1L)))
                .pattern(recipe("M5", 1, Map.of("M2", 1L, "M3", 1L), Map.of()))
                .build());
    }

    @Test
    void recoversMaterialDag13543() {
        check(CraftGraph.<String>builder()
                .stock("M1", 1)
                .stock("M4", 5)
                .pattern(recipe("M1", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M0", 1L, "M3", 2L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M1", 2, Map.of("M2", 2L, "M4", 1L), Map.of("M4", 1L)))
                .pattern(recipe("M0", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M0", 2, Map.of("M1", 1L, "M2", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M1", 1L, "M4", 1L), Map.of("M3", 1L)))
                .pattern(recipe("M0", 3, Map.of("M1", 1L, "M3", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M0", 1L, "M2", 1L), Map.of()))
                .build());
    }

    @Test
    void recoversMaterialDag18660() {
        check(CraftGraph.<String>builder()
                .stock("M0", 3)
                .stock("M2", 2)
                .stock("M4", 1)
                .pattern(recipe("M1", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M4", 2, Map.of("M0", 1L, "M2", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M0", 1L, "M2", 2L), Map.of("M3", 2L)))
                .pattern(recipe("M3", 2, Map.of("M1", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M1", 2L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M1", 1L, "M4", 1L), Map.of()))
                .build());
    }

    @Test
    void recoversMaterialDag20285() {
        check(CraftGraph.<String>builder()
                .stock("M1", 1)
                .stock("M2", 1)
                .stock("M3", 3)
                .stock("M4", 1)
                .pattern(recipe("M2", 2, Map.of("M0", 1L, "M4", 2L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M1", 1L, "M2", 1L), Map.of("M0", 1L)))
                .pattern(recipe("M1", 2, Map.of("M3", 3L), Map.of("M3", 1L)))
                .pattern(recipe("M4", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M3", 2, Map.of("M4", 2L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M0", 2L), Map.of("M4", 1L)))
                .pattern(recipe("M5", 1, Map.of("M0", 1L, "M3", 1L), Map.of()))
                .build());
    }

    @Test
    void recoversMaterialDag22223() {
        check(CraftGraph.<String>builder()
                .stock("M0", 2)
                .stock("M1", 2)
                .stock("M4", 2)
                .pattern(recipe("M0", 1, Map.of("M2", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M0", 2L), Map.of("M1", 1L)))
                .pattern(recipe("M0", 2, Map.of("M1", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M2", 2, Map.of("M1", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M3", 3L), Map.of("M4", 2L)))
                .pattern(recipe("M4", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M2", 1L, "M3", 1L), Map.of()))
                .build());
    }

    @Test
    void preservesConservativeLoop22810() {
        check(CraftGraph.<String>builder()
                .stock("M0", 1)
                .stock("M3", 2)
                .stock("M4", 3)
                .pattern(recipe("M2", 2, Map.of("M0", 2L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M0", 1L, "M3", 2L), Map.of("M0", 2L)))
                .pattern(recipe("M4", 1, Map.of("M0", 1L, "M1", 1L, "M2", 1L), Map.of("M1", 1L)))
                .pattern(recipe("M3", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M0", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M2", 3, Map.of("M0", 1L, "M1", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M0", 1L, "M4", 1L), Map.of("M4", 1L)))
                .pattern(recipe("M4", 1, Map.of("M0", 1L, "M1", 1L, "M3", 1L), Map.of("M2", 2L)))
                .pattern(recipe("M4", 1, Map.of("M2", 1L, "M3", 1L), Map.of("M2", 1L)))
                .pattern(recipe("M5", 1, Map.of("M2", 2L), Map.of()))
                .build());
    }

    @Test
    void recoversTwoItemRequest2780() {
        check(CraftGraph.<String>builder()
                .stock("M0", 5)
                .stock("M1", 1)
                .stock("M3", 1)
                .stock("M4", 2)
                .stock("M5", 1)
                .pattern(recipe("M2", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M2", 2, Map.of("M1", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M2", 2, Map.of("M0", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M0", 2, Map.of("M3", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M4", 2, Map.of("M1", 1L, "M2", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M2", 1L, "M3", 1L), Map.of("M5", 1L)))
                .pattern(recipe("M0", 2, Map.of("M1", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M1", 3, Map.of("M2", 1L, "M4", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M0", 1L, "M1", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M0", 3, Map.of("M4", 3L), Map.of()))
                .pattern(recipe("M1", 2, Map.of("M4", 2L), Map.of()))
                .pattern(recipe("M3", 2, Map.of("M0", 1L, "M5", 2L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M0", 1L), Map.of()))
                .pattern(recipe("M6", 1, Map.of("M4", 1L, "M5", 1L), Map.of()))
                .build(), "M6", 2);
    }

    @Test
    void recoversTwoItemRequest3108() {
        check(CraftGraph.<String>builder()
                .stock("M1", 2)
                .stock("M2", 2)
                .stock("M3", 2)
                .stock("M4", 1)
                .stock("M5", 3)
                .pattern(recipe("M2", 1, Map.of("M0", 1L), Map.of()))
                .pattern(recipe("M1", 2, Map.of("M2", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M4", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M5", 2, Map.of("M1", 1L, "M2", 2L), Map.of("M4", 1L)))
                .pattern(recipe("M1", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M1", 1L, "M2", 1L, "M5", 1L), Map.of("M4", 2L)))
                .pattern(recipe("M3", 1, Map.of("M0", 1L, "M4", 1L, "M5", 1L), Map.of("M4", 1L)))
                .pattern(recipe("M5", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M5", 2, Map.of("M0", 1L, "M2", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M1", 2L, "M5", 1L), Map.of("M4", 2L)))
                .pattern(recipe("M6", 1, Map.of("M2", 1L, "M4", 1L), Map.of()))
                .build(), "M6", 2);
    }

    @Test
    void recoversTwoItemRequest3350() {
        check(CraftGraph.<String>builder()
                .stock("M0", 1)
                .stock("M1", 2)
                .stock("M2", 1)
                .stock("M3", 2)
                .stock("M4", 1)
                .stock("M5", 3)
                .pattern(recipe("M5", 1, Map.of("M1", 1L, "M2", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M5", 3, Map.of("M1", 2L, "M4", 1L), Map.of()))
                .pattern(recipe("M2", 2, Map.of("M3", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M1", 2L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M1", 1L, "M2", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M0", 1L, "M1", 1L), Map.of()))
                .pattern(recipe("M3", 3, Map.of("M0", 2L, "M4", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M1", 1L, "M5", 2L), Map.of("M0", 2L)))
                .pattern(recipe("M3", 3, Map.of("M0", 2L, "M5", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M0", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M6", 1, Map.of("M0", 2L), Map.of()))
                .build(), "M6", 2);
    }

    @Test
    void recoversTwoItemRequest4810() {
        check(CraftGraph.<String>builder()
                .stock("M0", 2)
                .stock("M1", 3)
                .stock("M2", 1)
                .stock("M4", 1)
                .stock("M5", 3)
                .pattern(recipe("M0", 1, Map.of("M1", 1L, "M3", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M0", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M3", 3, Map.of("M1", 1L, "M5", 2L), Map.of()))
                .pattern(recipe("M1", 2, Map.of("M0", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M4", 1L), Map.of()))
                .pattern(recipe("M2", 3, Map.of("M1", 1L, "M4", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M3", 1L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M0", 2, Map.of("M3", 1L, "M4", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M2", 2L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M2", 1L, "M3", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M6", 1, Map.of("M0", 2L), Map.of()))
                .build(), "M6", 2);
    }

    @Test
    void preservesMixedRoutesWhenFeedbackSideOutputIsUnneeded() {
        for (long scale : new long[] {1, 2, 1_000_000, 1_000_000_000_000L}) {
            check(CraftGraph.<String>builder()
                    .stock("M0", 2*scale)
                    .stock("M1", 2*scale)
                    .stock("M2", 1*scale)
                    .stock("M4", 1*scale)
                    .pattern(recipe("M4", 2, Map.of("M2", 1L, "M3", 1L), Map.of()))
                    .pattern(recipe("M3", 1, Map.of("M0", 1L, "M2", 1L), Map.of()))
                    .pattern(recipe("M3", 1, Map.of("M0", 2L, "M2", 1L), Map.of("M1", 1L)))
                    .pattern(recipe("M3", 1, Map.of("M4", 2L), Map.of("M0", 1L)))
                    .pattern(recipe("M3", 1, Map.of("M2", 1L), Map.of()))
                    .pattern(recipe("M4", 1, Map.of("M2", 1L), Map.of()))
                    .pattern(recipe("M4", 1, Map.of("M0", 1L, "M1", 1L), Map.of()))
                    .pattern(recipe("M0", 1, Map.of("M2", 1L, "M3", 1L, "M4", 1L), Map.of("M3", 2L)))
                    .pattern(recipe("M4", 2, Map.of("M0", 1L, "M3", 1L), Map.of()))
                    .pattern(recipe("M5", 1, Map.of("M3", 2L), Map.of()))
                    .build(), "M5", scale);
        }
    }

    // Exercise fresh recipe identities: bounded solving must not depend on identity-map row order.
    @org.junit.jupiter.api.RepeatedTest(12)
    void preservesConsumedSelfReturn10687() {
        check(CraftGraph.<String>builder()
                .stock("M0", 3)
                .stock("M1", 1)
                .stock("M3", 4)
                .stock("M4", 2)
                .stock("M5", 2)
                .pattern(recipe("M0", 1, Map.of("M1", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M0", 2L), Map.of("M0", 1L)))
                .pattern(recipe("M3", 1, Map.of("M0", 1L, "M1", 1L), Map.of("M4", 1L)))
                .pattern(recipe("M3", 1, Map.of("M1", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M1", 3, Map.of("M0", 1L, "M2", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M2", 1L), Map.of()))
                .pattern(recipe("M4", 2, Map.of("M1", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M2", 1L, "M5", 2L), Map.of("M5", 2L)))
                .pattern(recipe("M5", 1, Map.of("M1", 1L, "M3", 1L), Map.of()))
                .pattern(recipe("M0", 2, Map.of("M3", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M4", 1, Map.of("M5", 1L), Map.of()))
                .pattern(recipe("M2", 1, Map.of("M4", 2L), Map.of("M4", 1L)))
                .pattern(recipe("M4", 2, Map.of("M0", 1L, "M1", 1L, "M3", 1L), Map.of("M1", 1L)))
                .pattern(recipe("M6", 1, Map.of("M1", 1L, "M4", 1L), Map.of()))
                .build(), "M6", 3);
    }

    @org.junit.jupiter.api.RepeatedTest(12)
    void preservesConsumedSelfReturn14924() {
        check(CraftGraph.<String>builder()
                .stock("M0", 2)
                .stock("M1", 2)
                .stock("M2", 1)
                .stock("M3", 2)
                .stock("M4", 4)
                .stock("M5", 1)
                .pattern(recipe("M5", 1, Map.of("M0", 1L), Map.of()))
                .pattern(recipe("M2", 3, Map.of("M1", 1L, "M4", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M2", 2L), Map.of("M0", 1L)))
                .pattern(recipe("M1", 2, Map.of("M3", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M0", 1, Map.of("M2", 1L, "M4", 1L), Map.of("M4", 1L)))
                .pattern(recipe("M0", 2, Map.of("M4", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M3", 1, Map.of("M1", 2L), Map.of("M4", 1L)))
                .pattern(recipe("M3", 2, Map.of("M2", 1L, "M4", 1L), Map.of()))
                .pattern(recipe("M2", 2, Map.of("M0", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M0", 1L), Map.of()))
                .pattern(recipe("M1", 1, Map.of("M0", 1L), Map.of()))
                .pattern(recipe("M5", 1, Map.of("M0", 1L, "M4", 1L), Map.of("M0", 1L)))
                .pattern(recipe("M3", 1, Map.of("M0", 1L, "M5", 1L), Map.of()))
                .pattern(recipe("M6", 1, Map.of("M2", 1L, "M5", 1L), Map.of()))
                .build(), "M6", 3);
    }
}
