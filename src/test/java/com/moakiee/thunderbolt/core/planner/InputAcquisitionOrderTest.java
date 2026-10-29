package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class InputAcquisitionOrderTest {
    @Test
    void unrelatedByproductsDoNotScanSiblingProducerRoutes() throws Exception {
        var pattern = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), null);
        var fixture = new Fixture(pattern);
        fixture.<Set<String>>field("reachableByproductKeys").add("unrelated");
        // This projection must not even be traversed when none of the input keys can receive a side output.
        fixture.<Map<String, List<CraftPattern<String>>>>field("patternsByOutput").put("A",
                new AbstractList<>() {
                    public CraftPattern<String> get(int index) { throw new AssertionError("irrelevant route read"); }
                    public int size() { return 1; }
                });
        assertSame(pattern.inputs(), fixture.order(pattern));
    }

    @Test
    void relevantSiblingByproductsStillOrderProducerBeforeConsumer() throws Exception {
        var pattern = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("B", 1), CraftInput.of("A", 1)), null);
        var supplier = new CraftPattern<>("A", 1, List.of(CraftInput.of("R", 1)),
                List.of(CraftOutput.of("B", 1)), null);
        var fixture = new Fixture(pattern, supplier);
        assertEquals(List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), fixture.order(pattern));
    }

    @Test
    void compiledReplayRanksRemainRelevantWithoutADirectSiblingSideOutput() throws Exception {
        var pattern = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("B", 1), CraftInput.of("A", 1)), null);
        var fixture = new Fixture(pattern);
        fixture.<Set<String>>field("reachableByproductKeys").add("deep-side-output");
        fixture.<Set<String>>field("fixedByproductReplayItems").add("A");
        var ranks = CraftPlannerV2.class.getDeclaredField("activeReplayRanks");
        ranks.setAccessible(true);
        ranks.set(fixture.planner, Map.of("A", 0, "B", 1));
        assertEquals(List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), fixture.order(pattern));
    }

    private static final class Fixture {
        private final CraftPlannerV2<String> planner;

        @SafeVarargs
        @SuppressWarnings("unchecked")
        Fixture(CraftPattern<String>... patterns) throws Exception {
            var builder = CraftGraph.<String>builder();
            for (var pattern : patterns) builder.pattern(pattern);
            var graph = builder.build();
            Class<?> diagnosticsType = Class.forName(CraftPlannerV2.class.getName() + "$DiagnosticsCollector");
            Class<?> budgetType = Class.forName(CraftPlannerV2.class.getName() + "$SearchBudget");
            var dc = diagnosticsType.getDeclaredConstructor(int.class, int.class, CraftPlannerV2.PlanningSession.class);
            dc.setAccessible(true);
            var constructor = CraftPlannerV2.class.getDeclaredConstructor(CraftGraph.class, int.class,
                    budgetType, BoundedIntegerLinearSolver.WorkBudget.class, diagnosticsType);
            constructor.setAccessible(true);
            planner = constructor.newInstance(graph, 1, null, null,
                    dc.newInstance(1, 1, new CraftPlannerV2.PlanningSession<String>()));
            var fallbackItems = CraftPlannerV2.class.getDeclaredField("fixedFallbackItems");
            fallbackItems.setAccessible(true);
            fallbackItems.set(planner, Set.of());
            for (var pattern : patterns) {
                this.<Map<String, List<CraftPattern<String>>>>field("patternsByOutput")
                        .put(pattern.output(), graph.patternsFor(pattern.output()));
                for (var output : pattern.byproducts())
                    this.<Set<String>>field("reachableByproductKeys").add(output.key());
            }
        }

        @SuppressWarnings("unchecked")
        <T> T field(String name) throws Exception {
            var field = CraftPlannerV2.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(planner);
        }

        @SuppressWarnings("unchecked")
        List<CraftInput<String>> order(CraftPattern<String> pattern) throws Exception {
            var method = CraftPlannerV2.class.getDeclaredMethod("inputAcquisitionOrder", CraftPattern.class);
            method.setAccessible(true);
            return (List<CraftInput<String>>) method.invoke(planner, pattern);
        }
    }
}
