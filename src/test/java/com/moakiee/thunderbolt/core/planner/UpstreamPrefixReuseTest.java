package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class UpstreamPrefixReuseTest {
    @Test
    void rejectedSuffixesReuseOneReadOnlyPrefixWithOriginalPatternsAndStock() {
        var fixture = branches(2, 3);
        var reference = new AtomicReference<UpstreamBatchOptimizer.Search<String>>();
        var observed = new IdentityHashMap<Object, Boolean>();
        var search = search(fixture, fixture.incumbent(), work -> {
            assertTrue(work > 0);
            if (reference.get() != null) {
                Object prefix = prefix(reference.get());
                if (prefix != null) observed.put(prefix, true);
            }
            return true;
        }, () -> true);
        reference.set(search);

        var result = search.tryEstablished();

        assertNotNull(result);
        assertEquals(9, executions(result));
        assertEquals(counts(fixture, 2, 1, 1, 0, 1, 0, 1, 0, 1, 0, 2), result.firings());
        assertEquals(Map.of("P0", 1L, "P1", 1L, "S", 2L), result.usedStock());
        assertTrue(search.candidates > 1, "individually worse branch replacements precede the joint improvement");
        assertEquals(1, observed.size(), "each proposal must reuse the same complete prefix");
        Object saved = prefix(search);
        assertTrue(observed.containsKey(saved));
        assertEquals(counts(fixture, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0), prefixCounts(saved));
        assertEquals(Map.of("A", BigInteger.ONE, "B", BigInteger.ONE), prefixNeed(saved));
        assertEquals(4L, field(saved, "executions"));
        assertEquals(6, field(saved, "work"));
        assertThrows(UnsupportedOperationException.class, () -> prefixCounts(saved).clear());
        assertThrows(UnsupportedOperationException.class, () -> prefixNeed(saved).clear());
        assertEquals(10, executions(fixture.incumbent()));
        assertEquals(Map.of("P0", 1L, "P1", 1L, "R", 2L), fixture.incumbent().usedStock());
        assertCertified(fixture, result);
    }

    @Test
    void acceptedIncumbentAndChangedStockEachBuildTheirOwnSearchPrefix() {
        var fixture = branches(2, 1);
        var first = search(fixture, fixture.incumbent());
        var accepted = first.tryEstablished();
        assertNotNull(accepted);
        Object firstPrefix = prefix(first);

        var next = search(fixture, accepted);
        var nextResult = bothStages(next);
        assertNull(nextResult, "the joint branch witness already uses the minimum nine executions");
        assertNotNull(prefix(next));
        assertNotSame(firstPrefix, prefix(next));
        assertEquals(prefixCounts(firstPrefix), prefixCounts(prefix(next)));

        var stocked = new Fixture<>(fixture.graph().withAdditionalStock(Map.of("P1", 1L)),
                fixture.patterns(), fixture.order(), fixture.target(), fixture.amount(), accepted);
        var third = search(stocked, accepted);
        var result = third.tryEstablished();
        assertNotNull(result);
        assertEquals(2, executions(result));
        assertEquals(counts(fixture, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), result.firings());
        assertEquals(Map.of("P0", 1L, "P1", 2L), result.usedStock());
        assertNotSame(firstPrefix, prefix(third));
        assertNotSame(prefix(next), prefix(third));
        assertEquals(result.firings(), prefixCounts(prefix(third)));
        assertTrue(prefixNeed(prefix(third)).isEmpty(), "stock-satisfied keys cannot retain stale frontier demand");
        assertEquals(Map.of("A", BigInteger.ONE, "B", BigInteger.ONE), prefixNeed(firstPrefix));
        assertEquals(9, executions(accepted));
        assertCertified(stocked, result);
    }

    @Test
    void emptyPrefixAndNoBranchKeepTheUncachedPath() {
        var empty = chain(Function.identity(), 0);
        var first = search(empty, empty.incumbent());
        var improved = first.tryEstablished();
        assertNotNull(improved);
        assertEquals(5, executions(improved));
        assertNull(prefix(first));
        assertCertified(empty, improved);

        var upper = new CraftPattern<>("P0", 1, List.of(CraftInput.of("P1", 1)), null);
        var lower = new CraftPattern<>("P1", 1, List.of(CraftInput.of("raw", 1)), null);
        var graph = CraftGraph.<String>builder().stock("raw", 1).pattern(upper).pattern(lower).build();
        var plan = MaterialDagReplay.tryPlan(graph, Map.of(upper, 1L, lower, 1L), "P0", 1);
        assertNotNull(plan);
        var plain = new Fixture<>(graph, List.of(upper, lower), List.of("P0", "P1", "raw"), "P0", 1, plan);
        var second = search(plain, plan);
        assertNull(bothStages(second));
        assertEquals(0, second.candidates);
        assertEquals(0, second.replayProbes);
        assertNull(prefix(second));
    }

    @Test
    void zeroInputPrefixRecipeNeverChargesZeroWork() {
        var target = new CraftPattern<>("P0", 1,
                List.of(CraftInput.of("free", 1), CraftInput.of("T", 4)), null);
        var free = new CraftPattern<String>("free", 1, List.of(), null);
        var small = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1)), null);
        var large = new CraftPattern<>("T", 3, List.of(CraftInput.of("A", 4)), null);
        var upstream = new CraftPattern<>("A", 2, List.of(CraftInput.of("raw", 1)), null);
        var patterns = List.of(target, free, small, large, upstream);
        var builder = CraftGraph.<String>builder().stock("raw", 10);
        patterns.forEach(builder::pattern);
        var graph = builder.build();
        var incumbent = MaterialDagReplay.tryPlan(graph,
                Map.of(target, 1L, free, 1L, small, 4L, upstream, 2L), "P0", 1);
        assertNotNull(incumbent);
        var fixture = new Fixture<>(graph, patterns, List.of("P0", "free", "T", "A", "raw"), "P0", 1, incumbent);

        var search = search(fixture, incumbent);
        var result = search.tryEstablished();

        assertNotNull(result);
        assertEquals(7, executions(result));
        assertNotNull(prefix(search));
        assertEquals(Map.of(target, 1L, free, 1L), prefixCounts(prefix(search)));
        assertEquals(Map.of("T", BigInteger.valueOf(4)), prefixNeed(prefix(search)));
        assertEquals(4, field(prefix(search), "work"));
        assertCertified(fixture, result);
    }

    @Test
    void wideLiveFrontierDeclinesCaptureWithoutLosingTheImprovement() {
        var target = new CraftPattern<>("P0", 1, List.of(CraftInput.of("P1", 1)), null);
        var bridge = new CraftPattern<>("P1", 1, List.of(CraftInput.of("P2", 1)), null);
        var lower = new CraftPattern<>("P2", 1,
                List.of(CraftInput.of("T", 4), CraftInput.of("U", 1), CraftInput.of("V", 1)), null);
        var small = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1)), null);
        var large = new CraftPattern<>("T", 3, List.of(CraftInput.of("A", 4)), null);
        var upstream = new CraftPattern<>("A", 2, List.of(CraftInput.of("raw", 1)), null);
        var patterns = List.of(target, bridge, lower, small, large, upstream);
        var builder = CraftGraph.<String>builder().stock("raw", 10).stock("U", 1).stock("V", 1);
        patterns.forEach(builder::pattern);
        var graph = builder.build();
        var incumbent = MaterialDagReplay.tryPlan(graph,
                Map.of(target, 1L, bridge, 1L, lower, 1L, small, 4L, upstream, 2L), "P0", 1);
        assertNotNull(incumbent);
        var fixture = new Fixture<>(graph, patterns, List.of("P0", "P1", "P2", "T", "U", "V", "A", "raw"),
                "P0", 1, incumbent);

        var search = search(fixture, incumbent);
        var result = search.tryEstablished();

        assertNotNull(result);
        assertEquals(8, executions(result));
        assertNull(prefix(search), "three counts and three demands cost seven units, one above the six saved units");
        assertCertified(fixture, result);
    }

    @Test
    void diamondMergesDemandBeforeStockAndKeepsRawSharedWithTheSuffix() {
        var patterns = List.of(
                new CraftPattern<>("T", 1,
                        List.of(CraftInput.of("L", 1), CraftInput.of("R", 1), CraftInput.of("raw", 1)), null),
                new CraftPattern<>("L", 1, List.of(CraftInput.of("J", 3)), null),
                new CraftPattern<>("R", 1, List.of(CraftInput.of("J", 5)), null),
                new CraftPattern<>("J", 3, List.of(CraftInput.of("Q", 2)), null),
                new CraftPattern<>("Q", 1, List.of(CraftInput.of("raw", 1)), null),
                new CraftPattern<>("Q", 3, List.of(CraftInput.of("raw", 4)), null));
        for (long raw : new long[] {6, 5}) {
            var builder = CraftGraph.<String>builder().stock("J", 3).stock("raw", raw);
            patterns.forEach(builder::pattern);
            var graph = builder.build();
            var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(patterns.get(0), 1L, patterns.get(1), 1L,
                    patterns.get(2), 1L, patterns.get(3), 2L, patterns.get(4), 4L), "T", 1);
            assertNotNull(incumbent);
            var fixture = new Fixture<>(graph, patterns, List.of("T", "L", "R", "J", "Q", "raw"), "T", 1, incumbent);
            var search = search(fixture, incumbent);

            var result = bothStages(search);

            Object saved = prefix(search);
            assertNotNull(saved);
            assertEquals(counts(fixture, 1, 1, 1, 2, 0, 0), prefixCounts(saved));
            assertEquals(Map.of("Q", BigInteger.valueOf(4), "raw", BigInteger.ONE), prefixNeed(saved));
            assertEquals(5L, field(saved, "executions"));
            assertEquals(7, field(saved, "work"), "capture costs one less than the eight saved units");
            var improvedCounts = counts(fixture, 1, 1, 1, 2, 1, 1);
            if (raw == 6) {
                assertNotNull(result);
                assertEquals(improvedCounts, result.firings());
                assertEquals(7, executions(result));
                // J demand is 3 + 5 = 8; stock 3 leaves 5, requiring two batches of three.
                // Their surplus means certification actually consumes only two units of J stock.
                assertEquals(Map.of("J", 2L, "raw", 6L), result.usedStock());
                assertCertified(fixture, result);
            } else {
                assertNull(result, "Q's relaxed raw capacity cannot spend the prefix's raw unit again");
                assertNull(MaterialDagReplay.tryPlan(graph, improvedCounts, "T", 1));
            }
            assertEquals(9, executions(incumbent));
            assertEquals(Map.of("J", 2L, "raw", 5L), incumbent.usedStock());
        }
    }

    @Test
    void frontierAboveTheAmountCapRemainsExactUntilItsStockIsSubtracted() {
        long maximum = Sat.SAT - 1;
        assertEquals(0, maximum % 2);
        var patterns = List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.of("L", 1)), null),
                new CraftPattern<>("L", 1, List.of(CraftInput.of("Q", maximum)), null),
                new CraftPattern<>("Q", maximum / 2, List.of(CraftInput.of("raw", 1)), null),
                new CraftPattern<>("Q", maximum, List.of(CraftInput.of("raw", 1)), null));
        var exactFrontier = BigInteger.valueOf(maximum).multiply(BigInteger.TWO);
        for (long stock : new long[] {maximum, maximum - 1}) {
            var builder = CraftGraph.<String>builder().stock("Q", stock).stock("raw", 3);
            patterns.forEach(builder::pattern);
            var graph = builder.build();
            long smallBatches = stock == maximum ? 2 : 3;
            var incumbent = MaterialDagReplay.tryPlan(graph,
                    Map.of(patterns.get(0), 2L, patterns.get(1), 2L, patterns.get(2), smallBatches), "T", 2);
            assertNotNull(incumbent);
            var fixture = new Fixture<>(graph, patterns, List.of("T", "L", "Q", "raw"), "T", 2, incumbent);
            var search = search(fixture, incumbent);

            var result = bothStages(search);

            Object saved = prefix(search);
            assertNotNull(saved);
            assertEquals(counts(fixture, 2, 2, 0, 0), prefixCounts(saved));
            assertEquals(Map.of("Q", exactFrontier), prefixNeed(saved));
            assertEquals(4, field(saved, "work"), "capture exactly repays the four saved units");
            if (stock == maximum) {
                assertNotNull(result);
                assertEquals(counts(fixture, 2, 2, 0, 1), result.firings());
                assertEquals(5, executions(result));
                assertEquals(Map.of("Q", maximum, "raw", 1L), result.usedStock());
                assertEquals(Sat.SAT, result.grossDemand().get("Q").longValue());
                assertCertified(fixture, result);
            } else {
                assertNull(result, "the net requirement MAX + 1 must still fail the suffix amount check");
                assertEquals(0, search.replayProbes);
            }
            assertEquals(4 + smallBatches, executions(incumbent));
        }
    }

    @Test
    void deniedPrefixOrCaptureWorkCannotPublishOrBorrowAnotherAllowance() {
        for (boolean denyCapture : new boolean[] {false, true}) {
            var fixture = branches(2, 1);
            var proposalCalls = new AtomicInteger();
            var denials = new AtomicInteger();
            var search = search(fixture, fixture.incumbent(), work -> {
                assertTrue(work > 0);
                if (inPrefixPreparation()) {
                    int call = proposalCalls.incrementAndGet();
                    if (denyCapture ? work == 6 : call == 2) {
                        denials.incrementAndGet();
                        return false;
                    }
                }
                return true;
            }, () -> { throw new AssertionError("incomplete prefix entered replay"); });

            assertNull(search.tryEstablished());
            assertEquals(1, denials.get());
            assertNull(prefix(search));
            int afterDenial = proposalCalls.get();
            assertNull(search.tryAdditional());
            assertNull(search.tryEstablished());
            assertEquals(afterDenial, proposalCalls.get());
            assertEquals(1, denials.get());
            assertEquals(0, search.replayProbes);
            assertEquals(10, executions(fixture.incumbent()));
        }
    }

    @Test
    void interruptedOrTimedOutPrefixNeverPublishesPartialState() {
        for (boolean optional : new boolean[] {false, true}) {
            var fixture = branches(2, 1);
            var proposalCalls = new AtomicInteger();
            var search = search(fixture, fixture.incumbent(), work -> {
                assertTrue(work > 0);
                if (inPrefixPreparation() && proposalCalls.incrementAndGet() == 2) {
                    if (optional) {
                        try (var ignored = PlanningCancellation.limitOptionalWork(0)) {
                            PlanningCancellation.check();
                        }
                    } else Thread.currentThread().interrupt();
                }
                return true;
            }, () -> { throw new AssertionError("cancelled prefix entered replay"); });
            try {
                if (optional) assertThrows(PlanningCancellation.OptionalWorkLimit.class, search::tryEstablished);
                else assertThrows(CancellationException.class, search::tryEstablished);
                assertNull(prefix(search));
                assertEquals(0, search.replayProbes);
                assertEquals(10, executions(fixture.incumbent()));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void deniedRestoreRetainsOnlyTheCompleteReadOnlySnapshot() {
        var fixture = branches(2, 1);
        var reference = new AtomicReference<UpstreamBatchOptimizer.Search<String>>();
        var denials = new AtomicInteger();
        var search = search(fixture, fixture.incumbent(), work -> {
            assertTrue(work > 0);
            if (reference.get() != null && prefix(reference.get()) != null && inPrefixPreparation() && work == 6) {
                denials.incrementAndGet();
                return false;
            }
            return true;
        }, () -> { throw new AssertionError("the first unchanged proposal and denied restore need no replay"); });
        reference.set(search);

        assertNull(search.tryEstablished());
        assertEquals(1, denials.get());
        Object saved = prefix(search);
        assertNotNull(saved);
        assertEquals(counts(fixture, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0), prefixCounts(saved));
        assertEquals(Map.of("A", BigInteger.ONE, "B", BigInteger.ONE), prefixNeed(saved));
        assertNull(search.tryAdditional());
        assertEquals(1, denials.get());
        assertSame(saved, prefix(search));
        assertEquals(0, search.replayProbes);
    }

    @Test
    void cancellationOrExceptionAtTheLastCaptureCopyCannotPublish() {
        for (boolean interrupt : new boolean[] {true, false}) {
            var probe = new CopyProbe(interrupt);
            var fixture = chain(name -> new CopyKey(name, probe), 2);
            var search = search(fixture, fixture.incumbent(), work -> {
                assertTrue(work > 0);
                // Two active chain keys leave only T live: the separately charged copy costs four.
                if (inPrefixPreparation() && work == 4 && !probe.triggered) probe.armed = true;
                return true;
            }, () -> { throw new AssertionError("failed capture entered replay"); });
            try {
                if (interrupt) assertThrows(CancellationException.class, search::tryEstablished);
                else assertThrows(CopyFailure.class, search::tryEstablished);
                assertTrue(probe.triggered, "the fault must occur while copying the sole live demand");
                assertNull(prefix(search));
                assertEquals(0, search.replayProbes);
            } finally {
                probe.armed = false;
                Thread.interrupted();
            }
        }
    }

    @Test
    void suffixCancellationMayKeepACompletePrefixWithoutSharingItsMutableMaps() {
        var fixture = branches(2, 1);
        var cancel = new AtomicBoolean(true);
        var search = search(fixture, fixture.incumbent(), work -> { assertTrue(work > 0); return true; }, () -> {
            if (cancel.getAndSet(false)) throw new CancellationException("after complete proposal, before replay");
            return true;
        });

        assertThrows(CancellationException.class, search::tryEstablished);
        Object saved = prefix(search);
        assertNotNull(saved);
        var savedCounts = Map.copyOf(prefixCounts(saved));
        var savedNeed = Map.copyOf(prefixNeed(saved));
        var result = search.tryAdditional();
        if (result != null) assertCertified(fixture, result);
        assertSame(saved, prefix(search));
        assertEquals(savedCounts, prefixCounts(saved));
        assertEquals(savedNeed, prefixNeed(saved));
        assertEquals(Map.of("A", BigInteger.ONE, "B", BigInteger.ONE), savedNeed);
        assertTrue(search.candidates <= 64);
        assertEquals(10, executions(fixture.incumbent()));
    }

    private static Fixture<String> branches(long secondStock, int registrations) {
        Object source = new Object();
        var patterns = List.of(
                new CraftPattern<>("P0", 1, List.of(CraftInput.of("P1", 1)), source),
                new CraftPattern<>("P1", 1, List.of(CraftInput.of("T", 1)), source),
                new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), source),
                new CraftPattern<>("A", 1, List.of(CraftInput.of("I", 2)), source),
                new CraftPattern<>("A", 1, List.of(CraftInput.of("J", 2)), source),
                new CraftPattern<>("B", 1, List.of(CraftInput.of("I", 2)), source),
                new CraftPattern<>("B", 1, List.of(CraftInput.of("J", 2)), source),
                new CraftPattern<>("I", 3, List.of(CraftInput.of("X", 1)), source),
                new CraftPattern<>("J", 4, List.of(CraftInput.of("Y", 2)), source),
                new CraftPattern<>("X", 1, List.of(CraftInput.of("R", 1)), source),
                new CraftPattern<>("Y", 1, List.of(CraftInput.of("S", 1)), source));
        var builder = CraftGraph.<String>builder().stock("P0", 1).stock("P1", 1).stock("R", 2).stock("S", secondStock);
        for (var pattern : patterns) for (int copy = 0; copy < registrations; copy++) builder.pattern(pattern);
        var graph = builder.build();
        var order = List.of("P0", "P1", "T", "A", "B", "I", "J", "X", "Y", "R", "S");
        var firings = new IdentityHashMap<CraftPattern<String>, Long>();
        long[] amounts = {2, 1, 1, 1, 0, 1, 0, 2, 0, 2, 0};
        for (int index = 0; index < amounts.length; index++) if (amounts[index] > 0) firings.put(patterns.get(index), amounts[index]);
        var incumbent = MaterialDagReplay.tryPlan(graph, firings, "P0", 3);
        assertNotNull(incumbent);
        return new Fixture<>(graph, patterns, order, "P0", 3, incumbent);
    }

    private static <K> Fixture<K> chain(Function<String, K> key, int prefixLength) {
        var patterns = new ArrayList<CraftPattern<K>>();
        var order = new ArrayList<K>();
        var firings = new IdentityHashMap<CraftPattern<K>, Long>();
        for (int index = 0; index < prefixLength; index++) {
            K output = key.apply("P" + index);
            order.add(output);
            var pattern = new CraftPattern<>(output, 1,
                    List.of(CraftInput.of(key.apply(index + 1 == prefixLength ? "T" : "P" + (index + 1)), 1)), null);
            patterns.add(pattern);
            firings.put(pattern, 4L);
        }
        K target = key.apply(prefixLength == 0 ? "T" : "P0");
        K t = key.apply("T"), a = key.apply("A"), raw = key.apply("raw");
        order.addAll(List.of(t, a, raw));
        var small = new CraftPattern<>(t, 1, List.of(CraftInput.of(a, 1)), null);
        var large = new CraftPattern<>(t, 3, List.of(CraftInput.of(a, 4)), null);
        var upstream = new CraftPattern<>(a, 2, List.of(CraftInput.of(raw, 1)), null);
        patterns.addAll(List.of(small, large, upstream));
        firings.put(small, 4L);
        firings.put(upstream, 2L);
        var builder = CraftGraph.<K>builder().stock(raw, 10);
        patterns.forEach(builder::pattern);
        var graph = builder.build();
        var incumbent = MaterialDagReplay.tryPlan(graph, firings, target, 4);
        assertNotNull(incumbent);
        return new Fixture<>(graph, patterns, order, target, 4, incumbent);
    }

    private static <K> UpstreamBatchOptimizer.Search<K> search(Fixture<K> fixture, CraftPlan<K> incumbent) {
        return search(fixture, incumbent, work -> { assertTrue(work > 0); return true; }, () -> true);
    }

    private static <K> UpstreamBatchOptimizer.Search<K> search(Fixture<K> fixture, CraftPlan<K> incumbent,
            IntPredicate work, BooleanSupplier probe) {
        var search = UpstreamBatchOptimizer.startSearch(fixture.graph(), fixture.target(), fixture.amount(), incumbent,
                fixture.order(), work, probe);
        assertNotNull(search);
        return search;
    }

    private static <K> CraftPlan<K> bothStages(UpstreamBatchOptimizer.Search<K> search) {
        var result = search.tryEstablished();
        return result == null ? search.tryAdditional() : result;
    }

    private static <K> Map<CraftPattern<K>, Long> counts(Fixture<K> fixture, long... amounts) {
        assertEquals(fixture.patterns().size(), amounts.length);
        var counts = new IdentityHashMap<CraftPattern<K>, Long>();
        for (int index = 0; index < amounts.length; index++) if (amounts[index] > 0) counts.put(fixture.patterns().get(index), amounts[index]);
        return counts;
    }

    private static long executions(CraftPlan<?> plan) {
        return plan.firings().values().stream().mapToLong(Long::longValue).sum();
    }

    private static <K> void assertCertified(Fixture<K> fixture, CraftPlan<K> plan) {
        assertTrue(plan.feasible());
        assertTrue(plan.missing().isEmpty());
        assertTrue(plan.firings().keySet().stream().allMatch(p -> fixture.patterns().stream().anyMatch(q -> p == q)));
        assertEquals(plan, MaterialDagReplay.tryPlan(fixture.graph(), plan.firings(), fixture.target(), fixture.amount()));
    }

    private static Object prefix(Object search) { return field(search, "fixedPrefix"); }

    @SuppressWarnings("unchecked")
    private static Map<CraftPattern<?>, Long> prefixCounts(Object prefix) {
        assertNotNull(prefix);
        return (Map<CraftPattern<?>, Long>) field(prefix, "counts");
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, BigInteger> prefixNeed(Object prefix) {
        assertNotNull(prefix);
        return (Map<Object, BigInteger>) field(prefix, "need");
    }

    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("missing snapshot field " + name, exception);
        }
    }

    // Fault injection only: setup and stage enumeration must finish before a prefix is interrupted.
    private static boolean inPrefixPreparation() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getClassName().equals(UpstreamBatchOptimizer.Search.class.getName())
                        && frame.getMethodName().equals("prepareProposal")));
    }

    private record Fixture<K>(CraftGraph<K> graph, List<CraftPattern<K>> patterns, List<K> order,
            K target, long amount, CraftPlan<K> incumbent) {}

    private static final class CopyProbe {
        final boolean interrupt;
        boolean armed;
        boolean triggered;
        CopyProbe(boolean interrupt) { this.interrupt = interrupt; }
    }

    private static final class CopyKey {
        final String name;
        final CopyProbe probe;
        CopyKey(String name, CopyProbe probe) { this.name = name; this.probe = probe; }
        @Override public int hashCode() {
            if (name.equals("T") && probe.armed && !probe.triggered) {
                probe.triggered = true;
                if (probe.interrupt) Thread.currentThread().interrupt();
                else throw new CopyFailure();
            }
            return name.hashCode();
        }
        @Override public boolean equals(Object other) { return other instanceof CopyKey key && name.equals(key.name); }
    }

    private static final class CopyFailure extends RuntimeException {}
}
