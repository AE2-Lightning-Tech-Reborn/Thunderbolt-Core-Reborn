package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class UpstreamPairGeometryReuseTest {
    // Captured from the uncached implementation, after startSearch completes.
    private static final List<Integer> COLD_FEES = List.of(1, 1, 3, 2, 2, 1, 1, 2, 1, 3, 6);
    private static final List<Integer> HOT_FEES = List.of(1, 1, 3, 2, 2, 2, 1, 1, 2, 1, 3, 9);

    @Test
    void repeatedCandidatesReuseTheGeometryWhileKeepingFeesAndPropagationDynamic() {
        var harness = new Harness(upstream(10));
        assertTrue(geometries(harness).isEmpty(), "geometry must remain lazy during model construction");
        harness.fees.arm();

        assertNull(attempt(harness, 0, 1, 0));

        assertEquals(COLD_FEES, harness.fees.calls);
        assertEquals(1, harness.probes.get());
        Object geometry = cached(harness, 0, 1);
        assertNotNull(geometry);
        assertArrayEquals(new long[] {1}, array(geometry, "useA"));
        assertArrayEquals(new long[] {4}, array(geometry, "useB"));
        assertArrayEquals(new long[] {10}, array(geometry, "capacity"));
        harness.fees.arm();

        var result = attempt(harness, 0, 1, 2);

        assertNotNull(result);
        assertSame(geometry, cached(harness, 0, 1));
        assertEquals(1, geometryCount(harness));
        assertEquals(HOT_FEES, harness.fees.calls);
        assertEquals(counts(harness.fixture.patterns, 2, 1, 6), result.firings());
        assertEquals(Map.of("raw", 6L), result.usedStock());
        assertEquals(2, harness.probes.get());
        assertCertified(harness.fixture, result);
        // Neither the solve nor its exchange neighbor may use these as scratch arrays.
        assertArrayEquals(new long[] {1}, array(geometry, "useA"));
        assertArrayEquals(new long[] {4}, array(geometry, "useB"));
        assertArrayEquals(new long[] {10}, array(geometry, "capacity"));
        harness.assertIncumbentUnchanged();
    }

    @Test
    void orderedPatternIdentityKeepsReversePairsAndSharedSourcesSeparate() {
        Object sharedSource = new Object();
        var patterns = List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), sharedSource),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("raw", 4)), sharedSource),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("raw", 7)), sharedSource));
        var harness = new Harness(fixture(patterns, Map.of("raw", 100L), 5, 0, 0));

        attempt(harness, 0, 1, 0);
        attempt(harness, 0, 2, 0);
        attempt(harness, 1, 0, 0);

        assertEquals(3, geometryCount(harness));
        Object ab = cached(harness, 0, 1), ac = cached(harness, 0, 2), ba = cached(harness, 1, 0);
        assertNotSame(ab, ac);
        assertNotSame(ab, ba);
        assertArrayEquals(new long[] {1}, array(ab, "useA"));
        assertArrayEquals(new long[] {4}, array(ab, "useB"));
        assertArrayEquals(new long[] {7}, array(ac, "useB"));
        assertArrayEquals(new long[] {4}, array(ba, "useA"));
        assertArrayEquals(new long[] {1}, array(ba, "useB"));
    }

    @Test
    void newSearchesRebuildCapacitiesForTheirInventoryAndIncumbentExecutionLimit() {
        var fixture = upstream(10);
        var original = new Harness(fixture);
        var repeated = new Harness(fixture);
        var lessStock = new Harness(fixture(fixture.patterns, Map.of("raw", 5L), 5, 0, 5));
        var lessExecutions = new Harness(fixture(fixture.patterns, Map.of("raw", 10L), 2, 1, 6));

        for (var harness : List.of(original, repeated, lessStock, lessExecutions)) attempt(harness, 0, 1, 0);

        Object a = cached(original, 0, 1), b = cached(repeated, 0, 1);
        assertNotSame(a, b);
        assertNotSame(array(a, "capacity"), array(b, "capacity"));
        assertArrayEquals(new long[] {10}, array(a, "capacity"));
        assertArrayEquals(new long[] {5}, array(cached(lessStock, 0, 1), "capacity"));
        assertArrayEquals(new long[] {9}, array(cached(lessExecutions, 0, 1), "capacity"));
    }

    @Test
    void duplicateInputSlotsAggregateTheirUseButKeepTheOriginalChargeOnHits() {
        var patterns = List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1), CraftInput.of("raw", 2)), null),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("raw", 1), CraftInput.of("raw", 1)), null));
        var harness = new Harness(fixture(patterns, Map.of("raw", 100L), 5, 0));
        harness.fees.arm();
        var first = attempt(harness, 0, 1, 0);
        assertNotNull(first);
        assertCertified(harness.fixture, first);
        Object geometry = cached(harness, 0, 1);
        assertArrayEquals(new long[] {3}, array(geometry, "useA"));
        assertArrayEquals(new long[] {2}, array(geometry, "useB"));
        assertEquals(List.of(1, 1, 5), harness.fees.calls.subList(0, 3));
        harness.fees.arm();

        var neighbor = attempt(harness, 0, 1, 2);

        assertNotNull(neighbor);
        assertCertified(harness.fixture, neighbor);
        assertSame(geometry, cached(harness, 0, 1));
        assertEquals(List.of(1, 1, 5), harness.fees.calls.subList(0, 3));
        assertArrayEquals(new long[] {100}, array(geometry, "capacity"));
    }

    @Test
    void emptyInputPairsCanReuseAnEmptyGeometry() {
        var patterns = List.of(new CraftPattern<String>("T", 1, List.of(), null),
                new CraftPattern<String>("T", 3, List.of(), null));
        var harness = new Harness(fixture(patterns, Map.of(), 5, 0));
        assertNotNull(attempt(harness, 0, 1, 0));
        Object geometry = cached(harness, 0, 1);
        assertArrayEquals(new long[0], array(geometry, "capacity"));
        assertArrayEquals(new long[0], array(geometry, "useA"));
        assertArrayEquals(new long[0], array(geometry, "useB"));
        harness.fees.arm();

        attempt(harness, 0, 1, 1);

        assertSame(geometry, cached(harness, 0, 1));
        assertEquals(List.of(1, 1, 1), harness.fees.calls.subList(0, 3));
    }

    @Test
    void geometryDoesNotCaptureTheDemandOrTheRemainingExecutionAllowance() {
        var patterns = List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), null),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("raw", 4)), null));
        var harness = new Harness(fixture(patterns, Map.of("raw", 100L), 5, 0));
        // Component-level calls vary the net demand and allowance passed to the same
        // search-owned pair cache. Public Search keeps its model/capacities fixed.
        assertEquals(counts(patterns, 0, 2), proposal(harness, 5, 5));
        Object geometry = cached(harness, 0, 1);
        assertEquals(counts(patterns, 1, 1), proposal(harness, 4, 5));
        assertNull(proposal(harness, 5, 1));
        assertEquals(counts(patterns, 0, 2), proposal(harness, 5, 2));
        assertSame(geometry, cached(harness, 0, 1));
        assertEquals(1, geometryCount(harness));
    }

    @Test
    void eachDeniedChargeStopsColdAndHotCandidatesWithoutAnotherProbeOrCallback() {
        for (boolean warm : List.of(false, true)) {
            var expected = warm ? HOT_FEES : COLD_FEES;
            for (int denied = 0; denied < expected.size(); denied++) {
                var harness = new Harness(upstream(10));
                if (warm) assertNull(attempt(harness, 0, 1, 0));
                Object previous = cached(harness, 0, 1);
                int probes = harness.probes.get();
                harness.fees.arm(denied, -1);

                assertNull(attempt(harness, 0, 1, warm ? 2 : 0));

                assertEquals(expected.subList(0, denied + 1), harness.fees.calls);
                assertTrue(harness.search.exhausted());
                assertEquals(probes, harness.probes.get());
                if (warm) assertSame(previous, cached(harness, 0, 1));
                else if (denied <= 2) assertTrue(geometries(harness).isEmpty());
                var stopped = List.copyOf(harness.fees.calls);
                int candidates = harness.search.candidates;
                assertNull(attempt(harness, 0, 1, 2));
                assertEquals(stopped, harness.fees.calls);
                assertEquals(candidates, harness.search.candidates);
                harness.assertIncumbentUnchanged();
            }
        }
    }

    @Test
    void cancellationRaisedByTheGeometryChargeStopsBeforeEitherBuildingOrReusingIt() {
        for (boolean warm : List.of(false, true)) {
            var harness = new Harness(upstream(10));
            if (warm) assertNull(attempt(harness, 0, 1, 0));
            Object previous = cached(harness, 0, 1);
            int probes = harness.probes.get();
            harness.fees.arm(-1, 2);
            try {
                assertThrows(CancellationException.class, () -> attempt(harness, 0, 1, warm ? 2 : 0));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(List.of(1, 1, 3), harness.fees.calls);
                assertEquals(probes, harness.probes.get());
                if (warm) assertSame(previous, cached(harness, 0, 1));
                else assertTrue(geometries(harness).isEmpty());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void cancellationAtEveryWideConstructionCheckpointPublishesNoPartialGeometry() {
        var inputs = new ArrayList<CraftInput<String>>();
        var stock = new java.util.HashMap<String, Long>();
        for (int i = 0; i < 512; i++) {
            inputs.add(CraftInput.of("raw" + i, 1));
            stock.put("raw" + i, 5L);
        }
        stock.put("last", 5L);
        var patterns = List.of(new CraftPattern<>("T", 1, inputs, null),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("last", 1)), null));
        var fixture = fixture(patterns, stock, 5, 0);
        var observed = new AtomicInteger();
        var reference = new Harness(fixture);
        try (var ignored = PlanningCancellation.bind(context(observed, Integer.MAX_VALUE))) {
            directGeometry(reference, 0, 1);
        }
        int constructionChecks = observed.get();
        assertTrue(constructionChecks >= 7, "wide union and array construction must remain cancellable");
        for (int rejected = 1; rejected <= constructionChecks; rejected++) {
            var harness = new Harness(fixture);
            try (var ignored = PlanningCancellation.bind(context(new AtomicInteger(), rejected))) {
                assertThrows(CancellationException.class, () -> directGeometry(harness, 0, 1));
            }
            assertTrue(geometries(harness).isEmpty(), "checkpoint " + rejected + " must not publish partial arrays");
        }
    }

    private static PlanningAttemptContext context(AtomicInteger checks, int rejectAt) {
        return new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void checkpoint() {
                if (checks.incrementAndGet() == rejectAt) throw new CancellationException("geometry audit");
            }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
        };
    }

    private static Fixture upstream(long rawStock) {
        var patterns = List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.of("U", 1)), null),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("U", 4)), null),
                new CraftPattern<>("U", 1, List.of(CraftInput.of("raw", 1)), null));
        return fixture(patterns, Map.of("raw", rawStock), 5, 0, 5);
    }

    private static Fixture fixture(List<CraftPattern<String>> patterns, Map<String, Long> stock, long... firings) {
        var builder = CraftGraph.<String>builder();
        stock.forEach(builder::stock);
        patterns.forEach(builder::pattern);
        var graph = builder.build();
        var incumbent = MaterialDagReplay.tryPlan(graph, counts(patterns, firings), "T", 5);
        assertNotNull(incumbent, "the incumbent must have a real replay certificate");
        return new Fixture(graph, List.copyOf(patterns), incumbent);
    }

    private static Map<CraftPattern<String>, Long> counts(List<CraftPattern<String>> patterns, long... counts) {
        assertEquals(patterns.size(), counts.length);
        var result = new IdentityHashMap<CraftPattern<String>, Long>();
        for (int i = 0; i < counts.length; i++) if (counts[i] > 0) result.put(patterns.get(i), counts[i]);
        return result;
    }

    private static void assertCertified(Fixture fixture, CraftPlan<String> plan) {
        assertEquals(plan, MaterialDagReplay.tryPlan(fixture.graph, plan.firings(), "T", 5));
        assertTrue(FeasibleConsumptionOptimizer.improves(fixture.incumbent, plan));
    }

    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) {
        try {
            var method = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(owner instanceof Class<?> ? null : owner, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @SuppressWarnings("unchecked")
    private static CraftPlan<String> attempt(Harness h, int first, int second, int exchange) {
        return (CraftPlan<String>) invoke(h.search, "attempt", new Class<?>[] {Object.class, CraftPattern.class,
                CraftPattern.class, CraftPattern.class, int.class, int.class}, "T", h.fixture.patterns.get(first),
                h.fixture.patterns.get(second), null, 0, exchange);
    }

    @SuppressWarnings("unchecked")
    private static Map<CraftPattern<String>, Long> proposal(Harness h, long amount, long executionLimit) {
        try {
            Object pairs = field(h.search, "pairs"), model = field(h.search, "model"), budget = field(h.search, "budget");
            Class<?> state = Class.forName(UpstreamBatchOptimizer.class.getName() + "$ProposalState");
            return (Map<CraftPattern<String>, Long>) invoke(UpstreamBatchOptimizer.class, "propose", new Class<?>[] {
                    CraftGraph.class, Object.class, long.class, CraftPlan.class, model.getClass(), Object.class,
                    CraftPattern.class, CraftPattern.class, CraftPattern.class, int.class, int.class, long.class,
                    pairs.getClass(), budget.getClass(), state}, h.fixture.graph, "T", amount, h.fixture.incumbent,
                    model, "T", h.fixture.patterns.get(0), h.fixture.patterns.get(1), null, 0, 0,
                    executionLimit, pairs, budget, null);
        } catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
    }

    @SuppressWarnings("unchecked")
    private static Map<CraftPattern<String>, Map<CraftPattern<String>, Object>> geometries(Harness h) {
        return (Map<CraftPattern<String>, Map<CraftPattern<String>, Object>>) field(field(h.search, "pairs"), "geometries");
    }

    private static Object cached(Harness h, int a, int b) {
        var first = geometries(h).get(h.fixture.patterns.get(a));
        return first == null ? null : first.get(h.fixture.patterns.get(b));
    }

    private static int geometryCount(Harness h) { return geometries(h).values().stream().mapToInt(Map::size).sum(); }
    private static long[] array(Object geometry, String name) { return (long[]) field(geometry, name); }
    private static Object directGeometry(Harness h, int a, int b) {
        Object model = field(h.search, "model");
        return invoke(field(h.search, "pairs"), "geometry", new Class<?>[] {CraftPattern.class, CraftPattern.class, model.getClass()},
                h.fixture.patterns.get(a), h.fixture.patterns.get(b), model);
    }

    private record Fixture(CraftGraph<String> graph, List<CraftPattern<String>> patterns, CraftPlan<String> incumbent) {}
    private static final class Harness {
        final Fixture fixture;
        final Fees fees = new Fees();
        final AtomicInteger probes = new AtomicInteger();
        final UpstreamBatchOptimizer.Search<String> search;
        final Map<CraftPattern<String>, Long> originalFirings;
        final Map<String, Long> originalStock;
        Harness(Fixture fixture) {
            this.fixture = fixture;
            originalFirings = Map.copyOf(fixture.incumbent.firings());
            originalStock = Map.copyOf(fixture.incumbent.usedStock());
            search = UpstreamBatchOptimizer.startSearch(fixture.graph, "T", 5, fixture.incumbent,
                    List.of("T", "U", "raw"), fees, () -> { probes.incrementAndGet(); return true; });
            assertNotNull(search);
        }
        void assertIncumbentUnchanged() {
            assertEquals(originalFirings, fixture.incumbent.firings());
            assertEquals(originalStock, fixture.incumbent.usedStock());
        }
    }

    private static final class Fees implements IntPredicate {
        final List<Integer> calls = new ArrayList<>();
        boolean recording;
        int rejectAt = -1, interruptAt = -1;
        void arm() { arm(-1, -1); }
        void arm(int rejectAt, int interruptAt) {
            calls.clear(); recording = true; this.rejectAt = rejectAt; this.interruptAt = interruptAt;
        }
        @Override public boolean test(int work) {
            if (!recording) return true;
            int index = calls.size(); calls.add(work);
            if (index == interruptAt) Thread.currentThread().interrupt();
            return index != rejectAt;
        }
    }
}
