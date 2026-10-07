package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplenishmentTemplateFirstTest {
    @Test void completedTemplatesAreRefreshedBeforeUnprojectedPlanning() throws Exception {
        var original = graph();
        var templates = MaterialDagOrders.compile(original, "T");
        assertFalse(templates.isEmpty());
        assertTrue(templates.stream().noneMatch(t -> t.maySupply(1)));
        var supplied = original.withAdditionalStock(Map.of("raw", 3L));
        var session = probeSession(templates, Map.of("raw", 3L));
        boolean[] refreshed = {false};
        try (var ignored = PlanningCancellation.bind(context(() -> {
            var names = StackWalker.getInstance().walk(s -> s.map(f -> f.getClassName() + "." + f.getMethodName()).toList());
            if (names.contains(MaterialDagOrders.class.getName() + ".withAdditionalStock")) refreshed[0] = true;
            if (names.contains(CraftPlannerV2.class.getName() + ".run"))
                assertTrue(refreshed[0], "must refresh existing support before rebuilding original graph");
        }))) {
            var result = CraftPlannerV2.planDetailed(supplied, "T", 1, session);
            assertTrue(result.plan().feasible());
            assertEquals(Map.of("raw", 3L), result.plan().usedStock());
        }
        assertTrue(refreshed[0]);
        assertEquals(0L, original.stock("raw"));
        assertTrue(templates.stream().allMatch(t -> t.graph().stock("raw") == 0));
    }

    @Test void emptyTemplatesRemainAHintAndCannotHideTheOrdinaryPlan() throws Exception {
        var supplied = graph().withAdditionalStock(Map.of("raw", 3L));
        var session = probeSession(List.of(), Map.of("raw", 3L));
        var result = CraftPlannerV2.planDetailed(supplied, "T", 1, session);
        assertTrue(result.plan().feasible());
        assertEquals(Map.of("raw", 3L), result.plan().usedStock());
    }

    @Test void outerCancellationDuringEarlyRefreshPropagatesWithoutPublishingStock() throws Exception {
        var original = graph();
        var templates = MaterialDagOrders.compile(original, "T");
        var session = probeSession(templates, Map.of("raw", 3L));
        var exit = new PlanningExitException("cancel template refresh");
        try (var ignored = PlanningCancellation.bind(context(() -> {
            if (StackWalker.getInstance().walk(s -> s.anyMatch(f ->
                    f.getClassName().equals(MaterialDagOrders.class.getName())
                            && f.getMethodName().equals("withAdditionalStock")))) throw exit;
        }))) {
            assertSame(exit, assertThrows(PlanningExitException.class, () ->
                    CraftPlannerV2.planDetailed(original.withAdditionalStock(Map.of("raw", 3L)), "T", 1, session)));
        }
        assertDoesNotThrow(PlanningCancellation::check);
        assertTrue(templates.stream().allMatch(t -> t.graph().stock("raw") == 0));
        var result = CraftPlannerV2.planDetailed(original.withAdditionalStock(Map.of("raw", 3L)), "T", 1,
                probeSession(templates, Map.of("raw", 3L)));
        assertTrue(result.plan().feasible());
    }

    private static CraftGraph<String> graph() {
        return CraftGraph.<String>builder().pattern("T", 1, List.of(CraftInput.of("raw", 3)),
                List.of(CraftOutput.of("side", 1))).build();
    }

    private static CraftPlannerV2.PlanningSession<String> probeSession(List<MaterialDagOrders.Candidate<String>> templates,
            Map<String, Long> supplied) throws Exception {
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.refineMissing = false;
        session.optimizeFeasible = false;
        var field = session.getClass().getDeclaredField("materialDagOrderTemplates");
        field.setAccessible(true); field.set(session, templates);
        field = session.getClass().getDeclaredField("materialDagAdditionalStock");
        field.setAccessible(true); field.set(session, supplied);
        return session;
    }

    private static PlanningAttemptContext context(Runnable checkpoint) {
        return new PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void report(PlanningDiagnosticSnapshot snapshot) {}
            public void checkpoint() { checkpoint.run(); }
        };
    }
}
