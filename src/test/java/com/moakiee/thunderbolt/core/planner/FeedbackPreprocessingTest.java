package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class FeedbackPreprocessingTest {
    @Test
    void orderedMaterialDagIncludesSideOutputsAndMayOmitNonConsumedInputs() {
        var pattern = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("R", 1), CraftInput.returned("catalyst", 1)),
                List.of(CraftOutput.of("S", 1)), null);
        var analysis = ConservativeFeedbackAnalysis.analyzeAll(
                List.of("T", "S", "R"), Map.of("T", List.of(pattern)));

        assertTrue(analysis.cyclicComponents().isEmpty());
        assertTrue(analysis.components().isEmpty());
        assertTrue(analysis.fallbacks().isEmpty());
    }

    @Test
    void ordinaryCycleWithoutByproductsStillNeedsFeedbackAnalysis() {
        var first = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)), null);
        var second = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)), null);
        var analysis = ConservativeFeedbackAnalysis.analyzeAll(List.of("A", "B"),
                Map.of("A", List.of(first), "B", List.of(second)));

        assertEquals(List.of(Set.of("A", "B")), analysis.cyclicComponents());
        assertEquals(1, analysis.components().size());
    }

    @Test
    void primarySelfLoopCannotPassStrictOrderProof() {
        var pattern = new CraftPattern<>("A", 1, List.of(CraftInput.of("A", 1)), null);
        var analysis = ConservativeFeedbackAnalysis.analyzeAll(
                List.of("A"), Map.of("A", List.of(pattern)));

        assertEquals(List.of(Set.of("A")), analysis.cyclicComponents());
        assertEquals(1, analysis.components().size());
        assertSame(pattern, analysis.components().get(0).patterns().get(0));
    }

    @Test
    void omittedOrBackwardSideOutputCannotHideFeedback() {
        var first = new CraftPattern<>("T", 1, List.of(CraftInput.of("B", 1)),
                List.of(CraftOutput.of("C", 1)), null);
        var second = new CraftPattern<>("B", 1, List.of(CraftInput.of("C", 1)), null);
        var selected = Map.of("T", List.of(first), "B", List.of(second));
        for (var order : List.of(List.of("T", "B", "C"), List.of("T", "B"),
                List.of("T", "C", "B"), List.of("T", "B", "C", "B"))) {
            var analysis = ConservativeFeedbackAnalysis.analyzeAll(order, selected);
            assertEquals(List.of(Set.of("B", "C")), analysis.cyclicComponents());
            assertEquals(1, analysis.components().size());
        }
    }

    @Test
    void implicitContainerRemainderIsAFeedbackOutput() {
        var drain = new CraftPattern<>("T", 1,
                List.of(CraftInput.consumedReturning("filled", 1, "empty")), null);
        var refill = new CraftPattern<>("filled", 1,
                List.of(CraftInput.of("empty", 1)), null);
        assertEquals(List.of(CraftOutput.of("empty", 1)), drain.byproducts());

        var analysis = ConservativeFeedbackAnalysis.analyzeAll(List.of("T", "filled"),
                Map.of("T", List.of(drain), "filled", List.of(refill)));
        assertEquals(List.of(Set.of("filled", "empty")), analysis.cyclicComponents());
        assertEquals(1, analysis.components().size());
    }

    @Test
    void missingPrimaryRankFallsBackForPatternsSelectedThroughAnotherOutput() {
        var first = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)),
                List.of(CraftOutput.of("T", 1)), null);
        var second = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)), null);
        var analysis = ConservativeFeedbackAnalysis.analyzeAll(List.of("T", "B"),
                Map.of("T", List.of(first), "B", List.of(second)));

        assertEquals(List.of(Set.of("A", "B")), analysis.cyclicComponents());
        assertEquals(1, analysis.components().size());
    }

    @Test
    void deduplicationPreservesOriginalPatternIdentityAndStableCycleOrder() {
        Object source = new Object();
        var first = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)), source);
        var second = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)), source);
        var analysis = ConservativeFeedbackAnalysis.analyzeAll(List.of("A", "B", "A"),
                Map.of("A", List.of(first, first), "B", List.of(second, first, second)));

        assertEquals(1, analysis.components().size());
        var component = analysis.components().get(0);
        assertEquals(List.of("A", "B"), component.stateOrder());
        assertEquals(2, component.patterns().size());
        assertSame(second, component.patterns().get(0));
        assertSame(first, component.patterns().get(1));
    }

    @Test
    void distinctPatternsWithSameSourceAndRecipeRemainSeparateTransitions() {
        Object source = new Object();
        var first = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)), source);
        var duplicateRecipe = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)), source);
        var second = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)), source);
        var analysis = ConservativeFeedbackAnalysis.analyzeAll(List.of("A", "B"),
                Map.of("A", List.of(first, duplicateRecipe), "B", List.of(second)));

        assertTrue(analysis.components().isEmpty());
        assertEquals(1, analysis.fallbacks().size());
        var patterns = analysis.fallbacks().get(0).patterns();
        assertEquals(3, patterns.size());
        assertSame(first, patterns.get(0));
        assertSame(duplicateRecipe, patterns.get(1));
        assertSame(second, patterns.get(2));
    }

    @Test
    void returnedFiniteAndHostOwnedInputsDoNotCreateMaterialFeedbackArcs() {
        var source = new ReusableStockSource(new Object(), new Object());
        for (CraftInput<String> input : List.of(CraftInput.returned("B", 1),
                CraftInput.finiteUse("B", 1, 2), CraftInput.returnedFrom("B", 1, source))) {
            var first = new CraftPattern<>("A", 1, List.of(input), null);
            var second = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)), null);
            var analysis = ConservativeFeedbackAnalysis.analyzeAll(List.of("B", "A"),
                    Map.of("A", List.of(first), "B", List.of(second)));
            assertTrue(analysis.cyclicComponents().isEmpty());
        }
    }

    @Test
    void preprocessingMatchesIndependentReachabilityAcrossMixedArcsAndOrders() {
        var random = new Random(2026100307L);
        var reusable = new ReusableStockSource(new Object(), new Object());
        for (int sample = 0; sample < 250; sample++) {
            int size = 3 + random.nextInt(5);
            var order = new ArrayList<Integer>();
            var patterns = new ArrayList<CraftPattern<Integer>>();
            var selected = new HashMap<Integer, List<CraftPattern<Integer>>>();
            for (int output = 0; output < size; output++) {
                order.add(output);
                var local = new ArrayList<CraftPattern<Integer>>();
                for (int route = random.nextInt(3); route > 0; route--) {
                    int key = random.nextInt(size);
                    CraftInput<Integer> input = switch (random.nextInt(6)) {
                        case 0 -> CraftInput.returned(key, 1);
                        case 1 -> CraftInput.finiteUse(key, 1, 2);
                        case 2 -> CraftInput.returnedFrom(key, 1, reusable);
                        case 3 -> CraftInput.consumedReturning(key, 1, random.nextInt(size));
                        default -> CraftInput.of(key, 1);
                    };
                    List<CraftOutput<Integer>> side = random.nextBoolean() ? List.of()
                            : List.of(CraftOutput.of(random.nextInt(size), 1));
                    var pattern = new CraftPattern<>(output, 1, List.of(input), side, null);
                    patterns.add(pattern);
                    local.add(pattern);
                    if (random.nextBoolean()) local.add(pattern);
                }
                selected.put(output, local);
            }
            Collections.shuffle(order, random);
            var analysis = ConservativeFeedbackAnalysis.analyzeAll(order, selected);
            assertEquals(reachableCycles(size, patterns), new HashSet<>(analysis.cyclicComponents()),
                    "sample=" + sample);
        }
    }

    @Test
    void acyclicPreprocessingPropagatesThreadCancellation() {
        var pattern = new CraftPattern<>("T", 1, List.of(CraftInput.of("R", 1)), null);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> ConservativeFeedbackAnalysis.analyzeAll(
                    List.of("T", "R"), Map.of("T", List.of(pattern))));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void acyclicPreprocessingPropagatesBoundAttemptExit() {
        var pattern = new CraftPattern<>("T", 1, List.of(CraftInput.of("R", 1)), null);
        var exit = new PlanningExitException("attempt exhausted");
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void checkpoint() { throw exit; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) { }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> ConservativeFeedbackAnalysis.analyzeAll(
                            List.of("T", "R"), Map.of("T", List.of(pattern)))));
        }
    }

    /** Tiny-graph transitive closure is independent of the production DFS/SCC and rank proof. */
    private static Set<Set<Integer>> reachableCycles(
            int size, List<CraftPattern<Integer>> patterns) {
        boolean[][] reachable = new boolean[size][size];
        for (var pattern : patterns) {
            for (var input : pattern.inputs()) {
                if (input.returned() || input.reusableStockSource() != null) continue;
                reachable[input.key()][pattern.output()] = true;
                for (var output : pattern.byproducts()) reachable[input.key()][output.key()] = true;
            }
        }
        for (int via = 0; via < size; via++) {
            for (int from = 0; from < size; from++) {
                for (int to = 0; to < size; to++) {
                    reachable[from][to] |= reachable[from][via] && reachable[via][to];
                }
            }
        }
        Set<Set<Integer>> result = new HashSet<>();
        for (int from = 0; from < size; from++) {
            if (!reachable[from][from]) continue;
            Set<Integer> component = new HashSet<>();
            for (int to = 0; to < size; to++) {
                if (reachable[from][to] && reachable[to][from]) component.add(to);
            }
            result.add(component);
        }
        return result;
    }
}
