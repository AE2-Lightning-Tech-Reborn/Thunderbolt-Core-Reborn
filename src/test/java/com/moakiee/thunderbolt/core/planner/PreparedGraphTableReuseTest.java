package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Exercises the publication boundary and real amount probes over the shared prepared tables. */
class PreparedGraphTableReuseTest {
    @Test
    void linearSuccessDefersEquivalenceUntilALaterQuantityNeedsSearch() throws Exception {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .stock("raw", 4).build();
        var session = PreparedGraphTableReuseTest.<String>session();
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 2, session).plan().feasible());
        Object prepared = onlyPrepared(session);
        assertNull(field(prepared, "materialFootprintByPattern"));

        var searched = CraftPlannerV2.planDetailed(graph, "T", 5, session);
        assertFalse(searched.plan().feasible());
        assertEquals(CraftPlannerV2.planDetailed(graph, "T", 5, session()).plan(), searched.plan());
        assertSame(prepared, onlyPrepared(session));
        Map<?, ?> footprints = field(prepared, "materialFootprintByPattern");
        assertNotNull(footprints);
        assertEquals(2, footprints.size());
        assertThrows(UnsupportedOperationException.class, footprints::clear);
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 1, session).plan().feasible());
        assertSame(footprints, field(prepared, "materialFootprintByPattern"));
    }

    @Test
    void cancelledLazyEquivalenceBuildDoesNotPublishAPartialTable() throws Exception {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .stock("raw", 4).build();
        var session = PreparedGraphTableReuseTest.<String>session();
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 1, session).plan().feasible());
        Object prepared = onlyPrepared(session);
        var build = prepared.getClass().getDeclaredMethod("materialFootprints");
        build.setAccessible(true);
        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> build.invoke(prepared));
            assertTrue(failure.getCause() instanceof PlanningCancellation.OptionalWorkLimit);
        }
        assertNull(field(prepared, "materialFootprintByPattern"));
        Object complete = build.invoke(prepared);
        assertSame(complete, build.invoke(prepared));
        assertEquals(2, ((Map<?, ?>) complete).size());
    }

    @Test
    void lazyEquivalenceUsesItsOwnStockProjection() throws Exception {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("T", 1, List.of(CraftInput.of("B", 1)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("B", 1, List.of(CraftInput.of("raw", 1)))
                .stock("raw", 10).build();
        var stocked = graph.withAdditionalStock(Map.of("A", 1L));
        var originalSession = PreparedGraphTableReuseTest.<String>session();
        var stockedSession = PreparedGraphTableReuseTest.<String>session();
        CraftPlannerV2.planDetailed(graph, "T", 1, originalSession);
        CraftPlannerV2.planDetailed(stocked, "T", 1, stockedSession);
        Object original = onlyPrepared(originalSession), projection = onlyPrepared(stockedSession);
        var build = original.getClass().getDeclaredMethod("materialFootprints");
        build.setAccessible(true);
        var originalIndex = (Map<?, ?>) build.invoke(original);
        var projectedIndex = (Map<?, ?>) build.invoke(projection);
        var first = graph.patternsFor("T").get(0);
        var second = graph.patternsFor("T").get(1);
        assertEquals(originalIndex.get(first), originalIndex.get(second));
        assertFalse(projectedIndex.get(first).equals(projectedIndex.get(second)));
        assertSame(originalIndex, build.invoke(original));
    }

    @Test
    void snapshotAndEveryReplayShareReadOnlyTablesButOwnTheirCapacityMemo() throws Exception {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("T", 1, List.of(CraftInput.of("other", 1)))
                .stock("raw", 16).stock("other", 16).build();
        var session = PreparedGraphTableReuseTest.<String>session();
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 4, session).plan().feasible());
        Object prepared = onlyPrepared(session);
        Object firstReplay = replay(prepared);
        Object secondReplay = replay(prepared);

        for (String name : List.of("patternsByOutput", "capacity", "capacityOrderByOutput")) {
            assertSame(field(prepared, name), field(firstReplay, name), name);
            assertSame(field(prepared, name), field(secondReplay, name), name);
        }
        assertReadOnlyTables(prepared);
        assertReadOnlyTables(firstReplay);
        assertReadOnlyTables(secondReplay);

        Map<CraftPattern<String>, Long> sourceMemo = field(prepared, "capacityScoreByPattern");
        Map<CraftPattern<String>, Long> firstMemo = field(firstReplay, "capacityScoreByPattern");
        Map<CraftPattern<String>, Long> secondMemo = field(secondReplay, "capacityScoreByPattern");
        assertNotSame(sourceMemo, firstMemo);
        assertNotSame(sourceMemo, secondMemo);
        assertNotSame(firstMemo, secondMemo);
        assertEquals(sourceMemo, firstMemo);
        assertEquals(sourceMemo, secondMemo);
        var extraPattern = new CraftPattern<>("extra", 1, List.<CraftInput<String>>of(), "extra");
        firstMemo.put(extraPattern, 123L);
        assertFalse(sourceMemo.containsKey(extraPattern));
        assertFalse(secondMemo.containsKey(extraPattern));
    }

    @Test
    void collidingKeysKeepRecipePreferenceAndMatchFreshAmountProbes() throws Exception {
        // More than eight equal hashes exercise HashMap tree bins and resizing. Keys have a stable
        // comparison so this checks map ownership/reuse rather than identity-hash tie-breaking.
        var target = new CollidingKey(0);
        var builder = CraftGraph.<CollidingKey>builder();
        var inputs = new ArrayList<CraftInput<CollidingKey>>();
        for (int i = 1; i <= 24; i++) {
            var intermediate = new CollidingKey(i);
            var raw = new CollidingKey(24 + i);
            inputs.add(CraftInput.of(intermediate, 1));
            builder.pattern(intermediate, 1, List.of(CraftInput.of(raw, 1))).stock(raw, 64);
        }
        builder.pattern(new CollidingKey(1), 1, List.of(CraftInput.of(new CollidingKey(49), 2)))
                .stock(new CollidingKey(49), 128)
                .pattern(target, 1, inputs).stock(target, 3).stock(new CollidingKey(7), 2);
        var graph = builder.build();
        var session = PreparedGraphTableReuseTest.<CollidingKey>session();

        for (long amount : new long[] {1, 11, 4, 27, 1, 8}) {
            assertProbeMatchesFresh(graph, target, amount, session);
            Object prepared = onlyPrepared(session);
            Map<CollidingKey, List<CraftPattern<CollidingKey>>> patterns =
                    field(prepared, "patternsByOutput");
            assertEquals(graph.patternsFor(new CollidingKey(1)), patterns.get(new CollidingKey(1)));
            assertReadOnlyTables(prepared);
        }
    }

    @Test
    void stockedContainerCycleReusesTablesWithoutCarryingByproductOrStockDraws() throws Exception {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.consumedReturning("full", 1, "empty")),
                        List.of(CraftOutput.of("scrap", 1)))
                .pattern("full", 1, List.of(CraftInput.of("empty", 1), CraftInput.of("powder", 1)))
                .stock("empty", 1).stock("powder", 16).build();
        var session = PreparedGraphTableReuseTest.<String>session();

        for (long amount : new long[] {1, 8, 3, 12, 1}) {
            var result = assertProbeMatchesFresh(graph, "T", amount, session);
            assertTrue(result.plan().usedStock().getOrDefault("empty", 0L) <= 1);
            assertTrue(result.plan().usedStock().getOrDefault("powder", 0L) <= 16);
            for (Object prepared : preparedTables(session).values()) assertReadOnlyTables(prepared);
        }
    }

    @Test
    void independentlyCachedCycleDirectionsMatchFreshProbes() throws Exception {
        var builder = CraftGraph.<String>builder();
        var inputs = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < 4; i++) {
            String a = "A" + i, b = "B" + i;
            inputs.add(CraftInput.of(b, 1));
            inputs.add(CraftInput.of(a, 1));
            builder.pattern(a, 1, List.of(CraftInput.of(b, 3)))
                    .pattern(b, 1, List.of(CraftInput.of(a, 3))).stock(b, 16);
        }
        var graph = builder.pattern("T", 1, inputs).build();
        var session = PreparedGraphTableReuseTest.<String>session();
        for (long amount : new long[] {1, 4, 2, 3, 1}) {
            assertProbeMatchesFresh(graph, "T", amount, session);
        }

        var prepared = new ArrayList<>(preparedTables(session).values());
        assertTrue(prepared.size() > 1, "fixture must exercise more than one cut orientation");
        for (int i = 0; i < prepared.size(); i++) {
            assertReadOnlyTables(prepared.get(i));
            for (int j = 0; j < i; j++) {
                for (String name : List.of("patternsByOutput", "capacity", "capacityOrderByOutput")) {
                    assertNotSame(field(prepared.get(j), name), field(prepared.get(i), name), name);
                }
            }
        }
    }

    @Test
    void inventoryProjectionAndSeparateSessionCompileTheirOwnStockSensitiveTables() throws Exception {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("raw", 1))).stock("raw", 8).build();
        var limited = graph.withStockLimits(Map.of("raw", 2L));
        var originalSession = PreparedGraphTableReuseTest.<String>session();
        var limitedSession = PreparedGraphTableReuseTest.<String>session();
        var independentSession = PreparedGraphTableReuseTest.<String>session();
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 4, originalSession).plan().feasible());
        var limitedResult = CraftPlannerV2.planDetailed(limited, "T", 4, limitedSession);
        assertFalse(limitedResult.plan().feasible());
        assertEquals(2L, limitedResult.plan().usedStock().get("raw"));
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 4, independentSession).plan().feasible());

        Object original = onlyPrepared(originalSession);
        Object reduced = onlyPrepared(limitedSession);
        Object independent = onlyPrepared(independentSession);
        Map<String, Long> originalCapacity = field(original, "capacity");
        Map<String, Long> reducedCapacity = field(reduced, "capacity");
        assertEquals(8L, originalCapacity.get("T"));
        assertEquals(2L, reducedCapacity.get("T"));
        for (String name : List.of("patternsByOutput", "capacity", "capacityOrderByOutput")) {
            assertNotSame(field(original, name), field(reduced, name), name);
            assertNotSame(field(original, name), field(independent, name), name);
        }
        assertThrows(IllegalArgumentException.class,
                () -> CraftPlannerV2.planDetailed(limited, "T", 1, originalSession));
        assertThrows(IllegalArgumentException.class,
                () -> CraftPlannerV2.planDetailed(graph, "raw", 1, originalSession));
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 8, originalSession).plan().feasible());
        assertSame(original, onlyPrepared(originalSession));
        assertEquals(8L, originalCapacity.get("T"));
    }

    private static <K> PlanningResult<K> assertProbeMatchesFresh(
            CraftGraph<K> graph, K target, long amount, CraftPlannerV2.PlanningSession<K> session) {
        var fresh = CraftPlannerV2.planDetailed(graph, target, amount, session());
        var reused = CraftPlannerV2.planDetailed(graph, target, amount, session);
        assertTrue(fresh.plan().feasible(), () -> "fresh amount=" + amount + ": " + fresh.plan());
        assertFalse(fresh.plan().budgetExhausted(), "fresh amount=" + amount);
        assertFalse(reused.plan().budgetExhausted(), "reused amount=" + amount);
        assertEquals(fresh.plan(), reused.plan(), "amount=" + amount);
        return reused;
    }

    private static <K> CraftPlannerV2.PlanningSession<K> session() {
        var session = new CraftPlannerV2.PlanningSession<K>();
        // Optimization has its own shared probe/time allowance; isolate prepared-table reuse here.
        session.optimizeFeasible = false;
        return session;
    }

    private static Object onlyPrepared(CraftPlannerV2.PlanningSession<?> session) throws Exception {
        var prepared = preparedTables(session);
        assertEquals(1, prepared.size());
        return prepared.values().iterator().next();
    }

    private static Map<?, ?> preparedTables(CraftPlannerV2.PlanningSession<?> session) throws Exception {
        return field(session, "preparedByOrientation");
    }

    private static Object replay(Object prepared) throws Exception {
        Class<?> diagnosticsType = Class.forName(CraftPlannerV2.class.getName() + "$DiagnosticsCollector");
        Class<?> searchBudgetType = Class.forName(CraftPlannerV2.class.getName() + "$SearchBudget");
        var diagnosticsConstructor = diagnosticsType.getDeclaredConstructor(
                int.class, int.class, CraftPlannerV2.PlanningSession.class);
        diagnosticsConstructor.setAccessible(true);
        Object diagnostics = diagnosticsConstructor.newInstance(1, 1, session());
        var constructor = CraftPlannerV2.class.getDeclaredConstructor(prepared.getClass(), int.class,
                searchBudgetType, BoundedIntegerLinearSolver.WorkBudget.class, diagnosticsType);
        constructor.setAccessible(true);
        return constructor.newInstance(prepared, 1, null, null, diagnostics);
    }

    private static void assertReadOnlyTables(Object owner) throws Exception {
        for (String name : List.of("patternsByOutput", "capacity", "capacityOrderByOutput")) {
            Map<Object, Object> table = field(owner, name);
            assertFalse(table.isEmpty(), name);
            var entry = table.entrySet().iterator().next();
            assertThrows(UnsupportedOperationException.class,
                    () -> table.put(entry.getKey(), entry.getValue()), name);
            assertThrows(UnsupportedOperationException.class,
                    () -> entry.setValue(entry.getValue()), name);
            assertThrows(UnsupportedOperationException.class,
                    () -> table.keySet().remove(entry.getKey()), name);
            assertThrows(UnsupportedOperationException.class, table::clear, name);
            if (!name.equals("capacity")) {
                for (Object value : table.values()) {
                    @SuppressWarnings("unchecked")
                    var patterns = (List<Object>) value;
                    assertThrows(UnsupportedOperationException.class, () -> patterns.add(new Object()), name);
                    if (!patterns.isEmpty()) {
                        assertThrows(UnsupportedOperationException.class,
                                () -> patterns.set(0, patterns.get(0)), name);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    private record CollidingKey(int id) implements Comparable<CollidingKey> {
        @Override public int hashCode() { return 7; }
        @Override public int compareTo(CollidingKey other) { return Integer.compare(id, other.id); }
    }
}
