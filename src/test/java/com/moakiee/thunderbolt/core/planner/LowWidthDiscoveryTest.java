package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LowWidthDiscoveryTest {
    @Test
    void independentComponentsBeyondTheBoxedIntegerCacheKeepTheirOwnBoundaryDemand() throws Exception {
        var builder = CraftGraph.<String>builder().stock("T", 1);
        var roots = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < 160; i++) {
            builder.pattern("P" + i, 1, List.of(CraftInput.of("raw" + i, 1)))
                    .pattern("P" + i, 1, List.of(CraftInput.of("dead" + i, 1)));
            roots.add(CraftInput.of("P" + i, i + 1));
        }
        var root = new CraftPattern<>("T", 1, roots, "root");
        var graph = builder.pattern(root).build();
        var fixture = fixture(graph);
        var components = components(fixture.analyze(Map.of(root, 1L), false));
        assertEquals(160, components.size());
        var seen = new HashSet<String>();
        for (Object model : components) {
            List<CraftPattern<String>> patterns = value(model, "patterns");
            String output = patterns.get(0).output();
            int i = Integer.parseInt(output.substring(1));
            assertTrue(seen.add(output));
            assertEquals(graph.patternsFor(output), patterns, "caller recipe preference is retained");
            assertEquals(Map.of(output, i + 1L), value(model, "externalDemand"));
            assertEquals(Map.of(), value(model, "externalSupply"));
            assertEquals(2, (int) value(model, "separatorWidth"));
            assertTrue((boolean) value(model, "exactSolverEligible"));
            assertTrue((boolean) value(model, "infeasibilityProof"));
            assertFalse((boolean) value(model, "requiresOrderedValidation"));
            List<String> items = value(model, "items");
            assertEquals(3, items.size());
            assertTrue(items.containsAll(List.of(output, "raw" + i, "dead" + i)));
            assertThrows(UnsupportedOperationException.class, items::clear);
            assertThrows(UnsupportedOperationException.class, patterns::clear);
            Map<String, Long> demand = value(model, "externalDemand");
            assertThrows(UnsupportedOperationException.class, demand::clear);
        }
        assertEquals(components, components(fixture.analyze(Map.of(root, 1L), false)),
                "a completed discovery must not retain mutable request state");
    }

    @Test
    void relaxedSharedRawRowsStayLocalButStrictAnalysisStillMergesTheirStock() throws Exception {
        var builder = CraftGraph.<String>builder().stock("T", 1);
        var roots = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < 160; i++) {
            builder.pattern("P" + i, 1, List.of(CraftInput.of("raw", 1)))
                    .pattern("P" + i, 1, List.of(CraftInput.of("dead" + i, 1)));
            roots.add(CraftInput.of("P" + i, 1));
        }
        var root = new CraftPattern<>("T", 1, roots, "root");
        var fixture = fixture(builder.pattern(root).build());
        assertEquals(1, components(fixture.analyze(Map.of(root, 1L), false)).size());
        var relaxed = components(fixture.analyze(Map.of(root, 1L), true));
        assertEquals(160, relaxed.size());
        for (Object model : relaxed) {
            List<String> items = value(model, "items");
            assertEquals(1, items.stream().filter("raw"::equals).count());
            assertEquals(3, items.size());
            List<CraftPattern<String>> patterns = value(model, "patterns");
            assertEquals(Map.of(patterns.get(0).output(), 1L), value(model, "externalDemand"));
        }
        assertEquals(1, components(fixture.analyze(Map.of(root, 1L), false)).size(),
                "relaxed discovery must not change a subsequent strict stock partition");
    }

    @Test
    void statefulEligibilityAndProofFlagsRemainLocal() throws Exception {
        var builder = CraftGraph.<String>builder().stock("T", 1);
        var roots = new ArrayList<CraftInput<String>>();
        var states = List.of(CraftInput.of("seed0", 1), CraftInput.returned("seed1", 1),
                CraftInput.finiteUse("seed2", 1, 2), CraftInput.consumedReturning("seed3", 1, "empty3"));
        for (int i = 0; i < states.size(); i++) {
            builder.pattern("P" + i, 1, List.of(CraftInput.of("raw" + i, 1), states.get(i)))
                    .pattern("P" + i, 1, List.of(CraftInput.of("dead" + i, 1)));
            roots.add(CraftInput.of("P" + i, 1));
        }
        var root = new CraftPattern<>("T", 1, roots, "root");
        var models = components(fixture(builder.pattern(root).build()).analyze(Map.of(root, 1L), false));
        assertEquals(4, models.size());
        for (Object model : models) {
            List<CraftPattern<String>> patterns = value(model, "patterns");
            int i = Integer.parseInt(patterns.get(0).output().substring(1));
            assertEquals(i != 2, (boolean) value(model, "exactSolverEligible"));
            assertEquals(i != 0, (boolean) value(model, "requiresOrderedValidation"));
            assertEquals(i == 0, (boolean) value(model, "infeasibilityProof"));
        }
    }

    @Test
    void repeatedBoundariesFollowTheNewQuantityAndFiringVector() throws Exception {
        var builder = CraftGraph.<String>builder().stock("T", 1);
        var inputs = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < 160; i++) {
            builder.pattern("P" + i, 1, List.of(CraftInput.of("raw" + i, 1)))
                    .pattern("P" + i, 1, List.of(CraftInput.of("dead" + i, 1)));
            inputs.add(CraftInput.of("P" + i, i + 1));
        }
        var root = new CraftPattern<>("T", 1, inputs, "root");
        var fixture = fixture(builder.pattern(root).build());
        var first = components(fixture.analyze(Map.of(root, 1L), false));
        for (long copies : new long[] {3, 1_000_000_000_000L, 0, 2}) {
            var current = components(fixture.analyze(Map.of(root, copies), false));
            assertEquals(160, current.size());
            for (int i = 0; i < current.size(); i++) {
                List<CraftPattern<String>> patterns = value(current.get(i), "patterns");
                String key = patterns.get(0).output();
                long units = Integer.parseInt(key.substring(1)) + 1L;
                assertEquals(copies == 0 ? Map.of() : Map.of(key, units * copies),
                        value(current.get(i), "externalDemand"));
                assertEquals(Map.of(key, units), value(first.get(i), "externalDemand"),
                        "earlier quantity results must remain immutable");
            }
        }
        var target = fixture(CraftGraph.<String>builder().stock("T", 1)
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("dead", 1))).build());
        for (long amount : new long[] {1, 9, 2}) {
            var model = components(target.analyze(amount, Map.of(), false)).get(0);
            assertEquals(Map.of("T", amount), value(model, "externalDemand"));
        }
    }

    @Test
    void cancellationCannotPublishPartialTopologyOrMixBoundaryResults() throws Exception {
        var builder = CraftGraph.<String>builder().stock("T", 1);
        var inputs = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < 160; i++) {
            builder.pattern("P" + i, 1, List.of(CraftInput.of("raw" + i, 1)))
                    .pattern("P" + i, 1, List.of(CraftInput.of("dead" + i, 1)));
            inputs.add(CraftInput.of("P" + i, 1));
        }
        var root = new CraftPattern<>("T", 1, inputs, "root");
        var fixture = fixture(builder.pattern(root).build());
        Object prepared = field(fixture.planner(), "preparedGraph");
        assertNull(field(prepared, "strictLowWidthTopology"));
        try (var ignored = PlanningCancellation.bind(cancelAfter(64))) {
            assertThrows(com.moakiee.thunderbolt.api.crafting.PlanningExitException.class,
                    () -> fixture.analyze(Map.of(root, 1L), false));
        }
        assertNull(field(prepared, "strictLowWidthTopology"));
        var first = components(fixture.analyze(Map.of(root, 1L), false));
        Object topology = field(prepared, "strictLowWidthTopology");
        try (var ignored = PlanningCancellation.bind(cancelAfter(64))) {
            assertThrows(com.moakiee.thunderbolt.api.crafting.PlanningExitException.class,
                    () -> fixture.analyze(Map.of(root, 3L), false));
        }
        assertSame(topology, field(prepared, "strictLowWidthTopology"));
        var recovered = components(fixture.analyze(Map.of(root, 2L), false));
        for (int i = 0; i < recovered.size(); i++) {
            List<CraftPattern<String>> patterns = value(recovered.get(i), "patterns");
            String key = patterns.get(0).output();
            assertEquals(Map.of(key, 1L), value(first.get(i), "externalDemand"));
            assertEquals(Map.of(key, 2L), value(recovered.get(i), "externalDemand"));
        }
        Map<?, ?> owners = value(topology, "componentByItem");
        assertThrows(UnsupportedOperationException.class, owners::clear);
        assertDoesNotThrow(PlanningCancellation::check);
    }

    private static com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext cancelAfter(int limit) {
        return new com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext() {
            private int calls;
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void report(com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot snapshot) { }
            public void checkpoint() {
                if (++calls >= limit) throw new com.moakiee.thunderbolt.api.crafting.PlanningExitException("cancel discovery");
            }
        };
    }

    @Test
    void projectionChangesCannotReuseTheWrongPartition() throws Exception {
        var root = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("P", 1), CraftInput.of("Q", 2)), "root");
        var fixture = fixture(CraftGraph.<String>builder().stock("T", 1).pattern(root)
                .pattern("P", 1, List.of(CraftInput.of("rawP", 1)))
                .pattern("P", 1, List.of(CraftInput.of("deadP", 1)))
                .pattern("Q", 1, List.of(CraftInput.of("rawQ", 1)))
                .pattern("Q", 1, List.of(CraftInput.of("deadQ", 1))).build());
        var first = components(fixture.analyze(Map.of(root, 1L), false));
        assertEquals(2, first.size());
        var projection = CraftPlannerV2.class.getDeclaredField("materialDagProjection");
        projection.setAccessible(true);
        projection.setBoolean(fixture.planner(), true);
        var projected = components(fixture.analyze(5L, Map.of(root, 1L), false));
        assertEquals(1, projected.size());
        assertEquals(Map.of("T", 5L), value(projected.get(0), "externalDemand"));
        projection.setBoolean(fixture.planner(), false);
        assertEquals(first, components(fixture.analyze(Map.of(root, 1L), false)));
    }

    private static Fixture fixture(CraftGraph<String> graph) throws Exception {
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.optimizeFeasible = false;
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 1, session).plan().feasible());
        Map<?, ?> preparedTables = field(session, "preparedByOrientation");
        Object prepared = preparedTables.values().iterator().next();
        var diagnosticsType = Class.forName(CraftPlannerV2.class.getName() + "$DiagnosticsCollector");
        var searchType = Class.forName(CraftPlannerV2.class.getName() + "$SearchBudget");
        var dc = diagnosticsType.getDeclaredConstructor(int.class, int.class, CraftPlannerV2.PlanningSession.class);
        dc.setAccessible(true);
        var constructor = CraftPlannerV2.class.getDeclaredConstructor(prepared.getClass(), int.class,
                searchType, BoundedIntegerLinearSolver.WorkBudget.class, diagnosticsType);
        constructor.setAccessible(true);
        Object planner = constructor.newInstance(prepared, 1, null, null, dc.newInstance(1, 1, session));
        return new Fixture(planner, field(prepared, "byproductSchedule"));
    }

    private record Fixture(Object planner, Object schedule) {
        Object analyze(Map<CraftPattern<String>, Long> baseline, boolean relaxed) throws Exception {
            return analyze(1L, baseline, relaxed);
        }
        Object analyze(long amount, Map<CraftPattern<String>, Long> baseline, boolean relaxed) throws Exception {
            var method = CraftPlannerV2.class.getDeclaredMethod("analyzeLowWidthComponents", schedule.getClass(),
                    Object.class, long.class, Map.class, boolean.class);
            method.setAccessible(true);
            try {
                return method.invoke(planner, schedule, "T", amount, baseline, relaxed);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }
    }

    private static List<?> components(Object analysis) throws Exception {
        assertNotNull(analysis);
        return value(analysis, "components");
    }

    @SuppressWarnings("unchecked")
    private static <T> T value(Object record, String name) throws Exception {
        var method = record.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (T) method.invoke(record);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
}
