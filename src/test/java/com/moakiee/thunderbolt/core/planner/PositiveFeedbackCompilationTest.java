package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PositiveFeedbackCompilationTest {
    @Test
    void originalGraphFactsAreSharedAcrossDirectionsAndQuantityProbes() throws Exception {
        var graph = ringsWithFeedback(true);
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        session.refineMissing = false;
        var first = CraftPlannerV2.planDetailed(graph, "T", 3, session);
        assertFalse(first.plan().feasible());
        Map<?, ?> prepared = field(session, "preparedByOrientation");
        assertTrue(prepared.size() >= 2, "exercise independent cycle orientations");
        Map<CraftPattern<String>, Set<String>> facts = null;
        for (Object value : prepared.values()) {
            Map<CraftPattern<String>, Set<String>> current = field(value, "uncutPositiveFeedbackOutputs");
            if (facts == null) facts = current;
            else assertSame(facts, current);
        }
        assertNotNull(facts);
        assertEquals(Set.of("Y"), facts.get(graph.patternsFor("P").get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> fieldFacts(prepared.values().iterator().next()).get(graph.patternsFor("P").get(0)).add("other"));

        var second = CraftPlannerV2.planDetailed(graph, "T", 2, session);
        assertTrue(second.plan().feasible(), () -> second.plan().missing().toString());
        assertTrue(second.diagnostics().reusedCompilations() > 0);
        for (Object value : prepared.values()) assertSame(facts, field(value, "uncutPositiveFeedbackOutputs"));
        assertEquals(2L, second.plan().usedStock().get("X"));
        assertEquals(8L, second.plan().usedStock().get("B0"));
        assertEquals(8L, second.plan().usedStock().get("B1"));
    }

    @Test
    void differentGraphsCannotInheritAnEarlierFeedbackFact() throws Exception {
        var original = ringsWithFeedback(true);
        var changed = ringsWithFeedback(false);
        var session = new CraftPlannerV2.PlanningSession<String>();
        CraftPlannerV2.planDetailed(original, "T", 1, session);
        assertThrows(IllegalArgumentException.class,
                () -> CraftPlannerV2.planDetailed(changed, "T", 1, session));
        var fresh = new CraftPlannerV2.PlanningSession<String>();
        var result = CraftPlannerV2.planDetailed(changed, "T", 1, fresh);
        assertTrue(result.plan().feasible());
        Map<?, ?> prepared = field(fresh, "preparedByOrientation");
        for (Object value : prepared.values()) assertTrue(fieldFacts(value).isEmpty());
    }

    private static CraftGraph<String> ringsWithFeedback(boolean includeRefill) {
        var builder = CraftGraph.<String>builder();
        var demand = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < 2; i++) {
            String a = "A" + i, b = "B" + i;
            builder.pattern(a, 1, List.of(CraftInput.of(b, 3)));
            builder.pattern(b, 1, List.of(CraftInput.of(a, 3))).stock(b, 8);
            demand.add(CraftInput.of(b, 1)); demand.add(CraftInput.of(a, 1));
        }
        builder.pattern("P", 1, List.of(CraftInput.of("X", 1)), List.of(CraftOutput.of("Y", 1)));
        if (includeRefill) builder.pattern("X", 2, List.of(CraftInput.of("Y", 1)));
        builder.stock("X", 2); demand.add(CraftInput.of("P", 1));
        return builder.pattern("T", 1, demand).build();
    }

    private static Map<CraftPattern<String>, Set<String>> fieldFacts(Object prepared) throws Exception {
        return field(prepared, "uncutPositiveFeedbackOutputs");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true);
        return (T) field.get(owner);
    }
}
