package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Tests the schedule boundary directly, independently of solver and replay fallback choices. */
class ByproductScheduleMetadataTest {
    private static final List<String> ORIGINAL_ORDER = List.of("empty", "P", "full");

    @Test
    void ordinaryGraphKeepsItsOrderAndReturnsAnImmutableEmptySchedule() throws Exception {
        var graph = CraftGraph.<String>builder().pattern(ordinary()).build();
        var order = new ArrayList<>(ORIGINAL_ORDER);
        assertFalse(graph.hasByproducts());

        var schedule = schedule(graph, order);
        order.clear();
        assertEmptySchedule(schedule);
        assertThrows(UnsupportedOperationException.class, () -> schedule.order().add("other"));
    }

    @Test
    void normalizedContainerRemainderStillGetsItsProducerPrecedence() throws Exception {
        var returning = container();
        assertEquals(List.of(CraftOutput.of("empty", 1)), returning.byproducts());
        var graph = CraftGraph.<String>builder().pattern(returning).build();

        assertContainerSchedule(graph, returning);
    }

    @Test
    void recipeProjectionCanIntroduceAndRemoveByproductsWithoutChangingEarlierSnapshots()
            throws Exception {
        var ordinary = ordinary();
        var original = CraftGraph.<String>builder().pattern(ordinary).build();
        var returning = container();
        var projected = original.withPatterns(Map.of("P", List.of(returning)));
        assertContainerSchedule(projected, returning);

        var removed = projected.withPatterns(Map.of("P", List.of(ordinary)));
        assertFalse(removed.hasByproducts());
        assertEmptySchedule(schedule(removed, ORIGINAL_ORDER));
        assertFalse(original.hasByproducts());
        assertEmptySchedule(schedule(original, ORIGINAL_ORDER));
        assertContainerSchedule(projected, returning);
    }

    @Test
    void stockProjectionsRetainContainerByproductMetadata() throws Exception {
        var returning = container();
        var graph = CraftGraph.<String>builder().pattern(returning).stock("full", 2).build();
        assertContainerSchedule(graph.withAdditionalStock(Map.of("full", 1L)), returning);
        assertContainerSchedule(graph.withStockLimits(Map.of("full", 1L)), returning);
        assertContainerSchedule(graph.withoutStock(Map.of("full", 1L)), returning);
    }

    @Test
    void cyclicSideOutputsMarkEveryMaterialMemberAndKeepSeparateSafeLinks() throws Exception {
        var first = new CraftPattern<>("A", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("S", 1), CraftOutput.of("S2", 1)), "first");
        var second = new CraftPattern<>("E", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("Q", 1)), "second");
        var safe = new CraftPattern<>("D", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("B", 1)), "safe");
        var order = List.of("T", "C", "X", "A", "F", "E", "S", "S2", "Q", "B", "D", "raw");
        for (boolean includeSafe : List.of(false, true)) {
            var builder = CraftGraph.<String>builder().pattern(first).pattern(second)
                    .pattern("X", 1, List.of(CraftInput.of("A", 1)))
                    .pattern("C", 1, List.of(CraftInput.of("X", 1),
                            CraftInput.of("S", 1), CraftInput.of("S2", 1)))
                    .pattern("F", 1, List.of(CraftInput.of("E", 1), CraftInput.of("Q", 1)))
                    .pattern("T", 1, List.of(CraftInput.of("C", 1),
                            CraftInput.of("F", 1), CraftInput.of("B", 1)));
            if (includeSafe) builder.pattern(safe);
            var schedule = schedule(builder.build(), order);
            // X has no side output itself, but its dependency path belongs to the first cycle.
            // The emitted rows S/S2/Q are outside those SCCs, as are the safe branch and root.
            assertEquals(Set.of("A", "C", "X", "E", "F"), schedule.unsafe());
            assertEquals(Map.of(first, Set.of("S", "S2"), second, Set.of("Q")),
                    schedule.speculative());
            assertEquals(includeSafe ? Map.of(safe, Set.of("B")) : Map.of(), schedule.reusable());
            if (includeSafe) {
                assertTrue(schedule.order().indexOf("D") < schedule.order().indexOf("T"));
                assertTrue(schedule.order().indexOf("D") < schedule.order().indexOf("B"));
            } else {
                assertEquals(order, schedule.order());
            }
        }
    }

    @Test
    void emptyOrdinaryScheduleStillPropagatesThreadCancellation() {
        var graph = CraftGraph.<String>builder().build();
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> schedule(graph, List.of()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void boundAttemptExitPropagatesBeforeEitherMetadataBranch() {
        var exit = new PlanningExitException("attempt exhausted");
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void checkpoint() { throw exit; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) { }
        };
        for (var pattern : List.of(ordinary(), container())) {
            var graph = CraftGraph.<String>builder().pattern(pattern).build();
            try (var ignored = PlanningCancellation.bind(context)) {
                assertSame(exit, assertThrows(PlanningExitException.class,
                        () -> schedule(graph, ORIGINAL_ORDER)));
            }
        }
    }

    private static CraftPattern<String> ordinary() {
        return new CraftPattern<>("P", 1, List.of(CraftInput.of("full", 1)), "ordinary");
    }

    private static CraftPattern<String> container() {
        return new CraftPattern<>("P", 1,
                List.of(CraftInput.consumedReturning("full", 1, "empty")), "drain");
    }

    private static void assertEmptySchedule(ScheduleView schedule) {
        assertEquals(ORIGINAL_ORDER, schedule.order());
        assertTrue(schedule.reusable().isEmpty());
        assertTrue(schedule.speculative().isEmpty());
        assertTrue(schedule.unsafe().isEmpty());
    }

    private static void assertContainerSchedule(
            CraftGraph<String> graph, CraftPattern<String> returning) throws Exception {
        assertTrue(graph.hasByproducts());
        var schedule = schedule(graph, ORIGINAL_ORDER);
        assertEquals(List.of("P", "empty", "full"), schedule.order());
        assertEquals(1, schedule.reusable().size());
        assertSame(returning, schedule.reusable().keySet().iterator().next());
        assertEquals(Set.of("empty"), schedule.reusable().get(returning));
        assertTrue(schedule.speculative().isEmpty());
        assertTrue(schedule.unsafe().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static ScheduleView schedule(CraftGraph<String> graph, List<String> order)
            throws Exception {
        Class<?> diagnosticsType = Class.forName(CraftPlannerV2.class.getName() + "$DiagnosticsCollector");
        Class<?> searchBudgetType = Class.forName(CraftPlannerV2.class.getName() + "$SearchBudget");
        var diagnosticsConstructor = diagnosticsType.getDeclaredConstructor(
                int.class, int.class, CraftPlannerV2.PlanningSession.class);
        diagnosticsConstructor.setAccessible(true);
        var diagnostics = diagnosticsConstructor.newInstance(
                1, 1, new CraftPlannerV2.PlanningSession<String>());
        var constructor = CraftPlannerV2.class.getDeclaredConstructor(CraftGraph.class, int.class,
                searchBudgetType, BoundedIntegerLinearSolver.WorkBudget.class, diagnosticsType);
        constructor.setAccessible(true);
        var planner = constructor.newInstance(graph, 1, null, null, diagnostics);
        var patternsField = CraftPlannerV2.class.getDeclaredField("patternsByOutput");
        patternsField.setAccessible(true);
        var patterns = (Map<String, List<CraftPattern<String>>>) patternsField.get(planner);
        for (String key : order) patterns.put(key, graph.patternsFor(key));

        var method = CraftPlannerV2.class.getDeclaredMethod("lowWidthByproductSchedule", List.class);
        method.setAccessible(true);
        Object result;
        try {
            result = method.invoke(planner, order);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw exception;
        }
        return new ScheduleView((List<String>) component(result, "order"),
                (Map<CraftPattern<String>, Set<String>>) component(result, "reusableByproducts"),
                (Map<CraftPattern<String>, Set<String>>) component(result, "speculativeByproducts"),
                (Set<String>) component(result, "unsafeItems"));
    }

    private static Object component(Object record, String name) throws Exception {
        var method = record.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private record ScheduleView(List<String> order,
                                Map<CraftPattern<String>, Set<String>> reusable,
                                Map<CraftPattern<String>, Set<String>> speculative,
                                Set<String> unsafe) { }
}
