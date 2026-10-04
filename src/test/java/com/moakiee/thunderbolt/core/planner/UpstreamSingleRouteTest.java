package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class UpstreamSingleRouteTest {
    // Charges after startSearch: attempt, key visits, route retention, pair work,
    // propagation, and (only for a complete distinct proposal) signature/replay.
    private static final List<Integer> GROW_FEES = List.of(1, 1, 3, 2, 2, 1, 1);
    private static final List<Integer> TRIM_FEES = List.of(1, 1, 1, 3, 1, 3, 2, 2, 2, 1, 1, 4);
    private static final List<Integer> FIT_FEES = List.of(1, 1, 1, 3, 1, 4, 3, 3, 2, 1, 1, 4, 1, 1, 5, 15);

    @Test
    void growingBeyondTheRemainingExecutionsRejectsBeforePropagationIsCharged() {
        var harness = new Harness(grow());
        harness.fees.arm();

        assertNull(attempt(harness, 0));

        // T-big leaves U demand 13, output 3, incumbent count 2 and only four
        // remaining executions. Growing to five fails after key+retain, before prop=4.
        assertEquals(GROW_FEES, harness.fees.calls);
        assertEquals(11, harness.fees.total());
        assertEquals(0, harness.probes.get());
        assertFalse(harness.search.exhausted());
        harness.assertIncumbentUnchanged();
    }

    @Test
    void trimmingAnOverlargeIncumbentStillChargesPropagationBeforeRejecting() {
        var harness = new Harness(trim(false));
        harness.fees.arm();

        assertNull(attempt(harness, 1));

        // X + three Q-small firings leave U demand 13 and four executions.
        // The old six U batches trim to five; the old path pays prop=1+3 first.
        assertEquals(TRIM_FEES, harness.fees.calls);
        assertEquals(22, harness.fees.total());
        assertEquals(4, harness.fees.calls.getLast().intValue());
        assertEquals(0, harness.probes.get());
        assertFalse(harness.search.exhausted());
        harness.assertIncumbentUnchanged();
    }

    @Test
    void fiveRemainingExecutionsAcceptFiveBatchesAndSkipTheUnusedSupplier() {
        var fixture = trim(true);
        var harness = new Harness(fixture);
        harness.fees.arm();

        var result = attempt(harness, 1);

        assertNotNull(result);
        assertEquals(FIT_FEES, harness.fees.calls);
        assertEquals(47, harness.fees.total());
        assertEquals(counts(fixture.patterns(), 1, 0, 3, 5, 0), result.firings());
        assertEquals(9, executions(result));
        assertEquals(9, executions(fixture.incumbent()));
        assertEquals(Map.of("raw", 15L), result.usedStock());
        assertEquals(Map.of("raw", 18L), fixture.incumbent().usedStock());
        assertFalse(result.grossDemand().containsKey("W"));
        assertEquals(1, harness.probes.get());
        assertCertified(fixture, result);
        harness.assertIncumbentUnchanged();
    }

    @Test
    void denyingEachChargeStopsTheProposalAndCannotSpendAgainOrReplay() {
        var fixtures = List.of(grow(), trim(false), trim(true));
        var vectors = List.of(GROW_FEES, TRIM_FEES, FIT_FEES);
        for (int variant = 0; variant < fixtures.size(); variant++) {
            var expected = vectors.get(variant);
            int exchange = variant == 0 ? 0 : 1;
            for (int denied = 0; denied < expected.size(); denied++) {
                var harness = new Harness(fixtures.get(variant));
                harness.fees.arm(denied, -1);

                assertNull(attempt(harness, exchange));

                assertEquals(expected.subList(0, denied + 1), harness.fees.calls,
                        "variant=" + variant + ", denied charge=" + denied);
                assertEquals(1, harness.fees.denials);
                assertEquals(0, harness.probes.get());
                assertTrue(harness.search.exhausted());
                assertEquals(1, harness.search.candidates);
                var stopped = List.copyOf(harness.fees.calls);
                assertNull(attempt(harness, exchange));
                assertNull(harness.search.tryEstablished());
                assertNull(harness.search.tryAdditional());
                assertEquals(stopped, harness.fees.calls);
                assertEquals(1, harness.search.candidates);
                assertEquals(1, harness.fees.denials);
                assertEquals(0, harness.probes.get());
                harness.assertIncumbentUnchanged();
            }
        }
    }

    @Test
    void cancellationDuringTheRetainChargePropagatesBeforeTheNextCallback() {
        var harness = new Harness(trim(false));
        // The penultimate callback retains U. The following propagation charge
        // must check cancellation before invoking the work callback with four.
        harness.fees.arm(-1, TRIM_FEES.size() - 2);
        try {
            assertThrows(CancellationException.class, () -> attempt(harness, 1));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(TRIM_FEES.subList(0, TRIM_FEES.size() - 1), harness.fees.calls);
            assertEquals(0, harness.fees.denials);
            assertEquals(0, harness.probes.get());
            harness.assertIncumbentUnchanged();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anotherProposalInTheSameSearchRecomputesTheSingleRouteDemand() {
        var small = new CraftPattern<>("T", 1, List.of(CraftInput.of("U", 1)), null);
        var big = new CraftPattern<>("T", 3, List.of(CraftInput.of("U", 4)), null);
        var upstream = new CraftPattern<>("U", 1, List.of(CraftInput.of("raw", 1)), null);
        var fixture = fixture("T", 5, List.of(small, big, upstream), Map.of("raw", 10L),
                new long[] {5, 0, 5}, List.of("T", "U", "raw"), "T", 0, 1);
        var harness = new Harness(fixture);
        harness.fees.arm();

        assertNull(attempt(harness, 0));

        // Two big target batches demand U=8. Their ten total executions draw more
        // raw than the incumbent and are certified but rejected as an improvement.
        assertEquals(List.of(1, 1, 3, 2, 2, 1, 1, 2, 1, 3, 6), harness.fees.calls);
        assertEquals(1, harness.probes.get());
        harness.fees.arm();
        var result = attempt(harness, 2);

        // The completed base pair solve is reused, but removing one big target
        // batch changes the upstream demand to six. No earlier U count may survive.
        assertNotNull(result);
        assertEquals(List.of(1, 1, 3, 2, 2, 2, 1, 1, 2, 1, 3, 9), harness.fees.calls);
        assertEquals(counts(fixture.patterns(), 2, 1, 6), result.firings());
        assertEquals(9, executions(result));
        assertEquals(Map.of("raw", 6L), result.usedStock());
        assertEquals(2, harness.probes.get());
        assertEquals(2, harness.search.candidates);
        assertCertified(fixture, result);
        harness.assertIncumbentUnchanged();
    }

    @Test
    void theNetDemandCapIsCheckedAfterStockAndBeforeSingleRouteRetention() {
        long maximum = Sat.SAT - 1;
        var patterns = List.of(
                new CraftPattern<>("X", 1, List.of(CraftInput.of("Q", 3), CraftInput.of("U", 1)), null),
                new CraftPattern<>("Q", 3, List.of(CraftInput.of("U", maximum)), null),
                new CraftPattern<>("Q", 1, List.of(CraftInput.of("U", 2)), null),
                new CraftPattern<>("U", maximum, repeatedRawSlots(), null));
        for (long stock : new long[] {1, 0}) {
            var fixture = fixture("X", 1, patterns, Map.of("U", stock, "raw", 6L),
                    new long[] {1, 0, 3, 1}, List.of("X", "Q", "U", "raw"), "Q", 1, 2);
            var harness = new Harness(fixture);
            harness.fees.arm();

            var result = attempt(harness, 0);

            if (stock == 1) {
                assertNotNull(result);
                assertEquals(List.of(1, 1, 1, 3, 1, 3, 2, 2, 1, 1, 4, 1, 4, 15), harness.fees.calls);
                assertEquals(counts(patterns, 1, 1, 0, 1), result.firings());
                assertEquals(Map.of("U", 1L, "raw", 3L), result.usedStock());
                assertEquals(3, executions(result));
                assertEquals(1, harness.probes.get());
                assertCertified(fixture, result);
            } else {
                assertNull(result);
                assertEquals(List.of(1, 1, 1, 3, 1, 3, 2, 2, 1), harness.fees.calls);
                assertEquals(0, harness.probes.get());
            }
            harness.assertIncumbentUnchanged();
        }
    }

    private static Fixture grow() {
        var patterns = List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.of("U", 2)), null),
                new CraftPattern<>("T", 3, List.of(CraftInput.of("U", 13)), null),
                new CraftPattern<>("U", 3, repeatedRawSlots(), null));
        // Enough relaxed raw capacity is essential: otherwise the pair solver
        // rejects the big target route before reaching the single-route grow check.
        return fixture("T", 3, patterns, Map.of("raw", 18L), new long[] {3, 0, 2},
                List.of("T", "U", "raw"), "T", 0, 1);
    }

    private static Fixture trim(boolean spareExecution) {
        var bigInputs = new ArrayList<>(List.of(CraftInput.of("U", 17)));
        if (spareExecution) bigInputs.add(CraftInput.of("W", 1));
        var patterns = new ArrayList<>(List.of(
                new CraftPattern<>("X", 1, List.of(CraftInput.of("Q", 3), CraftInput.of("U", 1)), null),
                new CraftPattern<>("Q", 3, bigInputs, null),
                new CraftPattern<>("Q", 1, List.of(CraftInput.of("U", 4)), null),
                new CraftPattern<>("U", 3, repeatedRawSlots(), null)));
        if (spareExecution) patterns.add(new CraftPattern<>("W", 1, List.of(), null));
        // W must be consumed by the incumbent's Q-big route. A gratuitous free W
        // firing would fail MaterialDagReplay's minimum-primary-demand certificate.
        return fixture("X", 1, patterns, Map.of("raw", 18L),
                spareExecution ? new long[] {1, 1, 0, 6, 1} : new long[] {1, 1, 0, 6},
                List.of("X", "Q", "U", "W", "raw"), "Q", 1, 2);
    }

    private static List<CraftInput<String>> repeatedRawSlots() {
        return List.of(CraftInput.of("raw", 1), CraftInput.of("raw", 1), CraftInput.of("raw", 1));
    }

    private static Fixture fixture(String target, long amount, List<CraftPattern<String>> patterns,
            Map<String, Long> stock, long[] firings, List<String> order, String mixed, int first, int second) {
        var builder = CraftGraph.<String>builder();
        stock.forEach(builder::stock);
        patterns.forEach(builder::pattern);
        var graph = builder.build();
        var incumbent = MaterialDagReplay.tryPlan(graph, counts(patterns, firings), target, amount);
        assertNotNull(incumbent, "the starting execution allowance must have a real certificate");
        return new Fixture(graph, List.copyOf(patterns), order, target, amount, mixed,
                patterns.get(first), patterns.get(second), incumbent);
    }

    private static Map<CraftPattern<String>, Long> counts(List<CraftPattern<String>> patterns, long... values) {
        assertEquals(patterns.size(), values.length);
        var result = new IdentityHashMap<CraftPattern<String>, Long>();
        for (int index = 0; index < values.length; index++) if (values[index] > 0) result.put(patterns.get(index), values[index]);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static CraftPlan<String> attempt(Harness harness, int exchange) {
        try {
            var method = UpstreamBatchOptimizer.Search.class.getDeclaredMethod("attempt", Object.class,
                    CraftPattern.class, CraftPattern.class, CraftPattern.class, int.class, int.class);
            method.setAccessible(true);
            var fixture = harness.fixture;
            return (CraftPlan<String>) method.invoke(harness.search, fixture.mixed(), fixture.first(), fixture.second(),
                    null, 0, exchange);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot invoke a directed established proposal", failure);
        }
    }

    private static long executions(CraftPlan<?> plan) {
        return plan.firings().values().stream().mapToLong(Long::longValue).sum();
    }

    private static void assertCertified(Fixture fixture, CraftPlan<String> plan) {
        assertTrue(plan.feasible());
        assertTrue(plan.missing().isEmpty());
        assertTrue(plan.firings().keySet().stream().allMatch(p -> fixture.patterns().stream().anyMatch(q -> p == q)));
        assertEquals(plan, MaterialDagReplay.tryPlan(fixture.graph(), plan.firings(), fixture.target(), fixture.amount()));
        assertTrue(FeasibleConsumptionOptimizer.improves(fixture.incumbent(), plan));
    }

    private record Fixture(CraftGraph<String> graph, List<CraftPattern<String>> patterns, List<String> order,
            String target, long amount, String mixed, CraftPattern<String> first, CraftPattern<String> second,
            CraftPlan<String> incumbent) {}

    private static final class Harness {
        final Fixture fixture;
        final Fees fees = new Fees();
        final AtomicInteger probes = new AtomicInteger();
        final UpstreamBatchOptimizer.Search<String> search;
        final Map<CraftPattern<String>, Long> originalFirings;
        final Map<String, Long> originalStock;

        Harness(Fixture fixture) {
            this.fixture = fixture;
            originalFirings = Map.copyOf(fixture.incumbent().firings());
            originalStock = Map.copyOf(fixture.incumbent().usedStock());
            search = UpstreamBatchOptimizer.startSearch(fixture.graph(), fixture.target(), fixture.amount(),
                    fixture.incumbent(), fixture.order(), fees, () -> { probes.incrementAndGet(); return true; });
            assertNotNull(search);
            assertEquals(0, probes.get());
        }

        void assertIncumbentUnchanged() {
            assertEquals(originalFirings, fixture.incumbent().firings());
            assertEquals(originalStock, fixture.incumbent().usedStock());
        }
    }

    private static final class Fees implements IntPredicate {
        final List<Integer> calls = new ArrayList<>();
        boolean recording;
        int rejectAt = -1;
        int interruptAt = -1;
        int denials;

        void arm() { arm(-1, -1); }

        void arm(int rejectAt, int interruptAt) {
            calls.clear();
            denials = 0;
            this.rejectAt = rejectAt;
            this.interruptAt = interruptAt;
            recording = true;
        }

        int total() { return calls.stream().mapToInt(Integer::intValue).sum(); }

        @Override public boolean test(int work) {
            assertTrue(work > 0);
            if (!recording) return true;
            int position = calls.size();
            calls.add(work);
            if (position == interruptAt) Thread.currentThread().interrupt();
            if (position == rejectAt) { denials++; return false; }
            return true;
        }
    }
}
