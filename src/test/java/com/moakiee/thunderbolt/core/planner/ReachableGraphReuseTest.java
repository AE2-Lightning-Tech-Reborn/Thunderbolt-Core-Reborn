package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class ReachableGraphReuseTest {
    @Test
    void admissionCountsEverySlotButDistancesFollowOnlyPrimaryInputs() throws Exception {
        var route = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)),
                List.of(CraftOutput.of("side", 1)), null);
        var source = new ReusableStockSource("host", "pool");
        var graph = CraftGraph.<String>builder().pattern(route).pattern(route)
                .pattern("A", 1, List.of(CraftInput.of("C", 1), CraftInput.returned("tool", 1),
                        CraftInput.returnedFrom("private", 1, source)))
                .pattern("B", 1, List.of(CraftInput.of("C", 1)))
                .pattern("C", 1, List.of(CraftInput.of("T", 1)))
                .pattern("side", 1, List.of(CraftInput.of("unreachable", 1))).build();
        var session = new CraftPlannerV2.PlanningSession<String>();

        assertEquals(22, session.reachableWork(graph, "T"));
        assertEquals(22, CraftPlannerV2.reachableWorkEstimate(graph, "T"));
        Map<?, ?> distances = field(session, "inputDistances");
        assertEquals(Map.of("T", 0, "A", 1, "B", 1, "C", 2, "tool", 2, "private", 2), distances);
        assertThrows(UnsupportedOperationException.class, distances::clear);
        Object sizing = session.consumptionIndex;
        assertEquals(6, (Integer) field(sizing, "expectedKeys"));
        assertEquals(5, (Integer) field(sizing, "expectedPatterns"));
        assertEquals(9, (Integer) field(sizing, "expectedInputs"));
        assertEquals(2, (Integer) field(sizing, "expectedSides"));
    }

    @Test
    void quantityProbesReuseDistancesAndRejectOtherGraphsOrTargets() throws Exception {
        var graph = CraftGraph.<String>builder().stock("raw", 10)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1))).build();
        var session = new CraftPlannerV2.PlanningSession<String>();
        Object distances = null;
        for (long amount : new long[] {1, 7, 2, 0}) {
            var result = CraftPlannerV2.planDetailed(graph, "T", amount, session);
            assertEquals(CraftPlannerV2.planDetailed(graph, "T", amount).plan(), result.plan());
            Object current = field(session, "inputDistances");
            if (distances != null) assertSame(distances, current);
            distances = current;
        }
        assertThrows(IllegalArgumentException.class, () -> session.reachableWork(graph, "A"));
        var projected = graph.withAdditionalStock(Map.of("A", 1L));
        assertThrows(IllegalArgumentException.class, () -> session.reachableWork(projected, "T"));
        var fresh = new CraftPlannerV2.PlanningSession<String>();
        assertEquals(CraftPlannerV2.planDetailed(projected, "T", 1).plan(),
                CraftPlannerV2.planDetailed(projected, "T", 1, fresh).plan());
        assertNotSame(distances, field(fresh, "inputDistances"));
    }

    @Test
    void cancelledAdmissionDoesNotPublishPartialDistancesOrWork() throws Exception {
        var target = new InterruptKey();
        var input = new InterruptKey();
        var graph = CraftGraph.<InterruptKey>builder()
                .pattern(target, 1, List.of(CraftInput.of(input, 1))).build();
        var session = new CraftPlannerV2.PlanningSession<InterruptKey>();
        input.armed = true;
        try {
            assertThrows(CancellationException.class, () -> session.reachableWork(graph, target));
        } finally {
            input.armed = false;
            Thread.interrupted();
        }
        assertNull(field(session, "inputDistances"));
        assertNull(field(session.consumptionIndex, "sizedGraph"));
        assertEquals(0, (Integer) field(session, "reachableWorkEstimate"));
        assertEquals(4, session.reachableWork(graph, target));
        assertEquals(Map.of(target, 0, input, 1), field(session, "inputDistances"));
    }

    @Test
    void admissionBoundaryCountsByproductsWithoutPublishingATruncatedGraph() throws Exception {
        var limitField = CraftPlannerV2.class.getDeclaredField("MAX_REACHABLE_PLANNING_WORK");
        limitField.setAccessible(true);
        int limit = limitField.getInt(null);
        for (int excess : new int[] {0, 1}) {
            var sides = Collections.nCopies(limit - 2 + excess, CraftOutput.of("side", 1));
            var graph = CraftGraph.<String>builder().pattern("T", 1, List.of(), sides).build();
            var session = new CraftPlannerV2.PlanningSession<String>();
            assertEquals(limit + excess, session.reachableWork(graph, "T"));
            assertEquals(limit + excess, CraftPlannerV2.reachableWorkEstimate(graph, "T"));
            if (excess == 0) assertEquals(Map.of("T", 0), field(session, "inputDistances"));
            else {
                assertNull(field(session, "inputDistances"));
                assertNull(field(session.consumptionIndex, "sizedGraph"));
                var plan = CraftPlannerV2.planDetailed(graph, "T", 1, session).plan();
                assertTrue(plan.budgetExhausted());
                assertFalse(plan.feasible());
                assertEquals(Map.of("T", 1L), plan.missing());
                assertNull(field(session, "inputDistances"));
            }
        }
    }

    private static final class InterruptKey {
        boolean armed;

        @Override
        public int hashCode() {
            if (armed) Thread.currentThread().interrupt();
            return System.identityHashCode(this);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
}
