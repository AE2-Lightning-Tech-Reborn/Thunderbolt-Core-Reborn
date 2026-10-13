package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class UpstreamBatchOptimizerBoundaryTest {
    @Test
    void repeatedUpstreamPatternIdentityStillPermitsGrowingItsFiringCount() {
        assertImprovesToFive(fixture(2, 1, 1, null));
    }

    @Test
    void repeatedTargetPatternIdentityIsNotCountedAsAnotherProducer() {
        assertImprovesToFive(fixture(1, 2, 1, null));
        assertImprovesToFive(fixture(1, 1, 2, null));
    }

    @Test
    void moreThanSixteenRegistrationsOfTheSameObjectsAreOnlyThreeDistinctPatterns() {
        var fixture = fixture(32, 32, 32, null);
        assertEquals(32, fixture.graph().patternsFor("A").size());
        assertEquals(64, fixture.graph().patternsFor("T").size());
        assertImprovesToFive(fixture);
    }

    @Test
    void differentPatternObjectsSharingOneSourceRemainDifferentRoutes() {
        var source = new Object();
        var fixture = fixture(1, 1, 1, source);
        assertNotSame(fixture.small(), fixture.large());
        assertSame(source, fixture.small().source());
        assertSame(source, fixture.large().source());
        assertImprovesToFive(fixture);
    }

    @Test
    void wideInputNormalizationChecksCancellationBeforeTraversingAllSlots() {
        int width = 4096;
        var probe = new CancellationProbe(width);
        var target = new ProbeKey(-2, null);
        var intermediate = new ProbeKey(-1, null);
        var inputs = new ArrayList<CraftInput<ProbeKey>>(width);
        var builder = CraftGraph.<ProbeKey>builder();
        for (int index = 0; index < width; index++) {
            var raw = new ProbeKey(index, probe);
            inputs.add(CraftInput.of(raw, 1));
            builder.stock(raw, 10);
        }
        var upstream = new CraftPattern<>(intermediate, 2, inputs, null);
        var small = new CraftPattern<>(target, 1, List.of(CraftInput.of(intermediate, 1)), null);
        var large = new CraftPattern<>(target, 3, List.of(CraftInput.of(intermediate, 4)), null);
        var graph = builder.pattern(upstream).pattern(small).pattern(large).build();
        var incumbent = MaterialDagReplay.tryPlan(graph, Map.of(upstream, 2L, small, 4L), target, 4);
        assertNotNull(incumbent);

        // Only raw keys trigger the probe. Arming after graph/replay setup makes the first
        // observed raw key belong to Model.build's input normalization, after its outer check.
        probe.armed = true;
        try {
            assertThrows(CancellationException.class, () -> UpstreamBatchOptimizer.tryImprove(
                    graph, target, 4, incumbent, List.of(target, intermediate), work -> true,
                    () -> { throw new AssertionError("interrupted normalization entered certification"); }));
            assertTrue(probe.interrupted, "a raw key must issue the interrupt from inside the wide input loop");
            assertTrue(probe.distinctVisited > 0);
            assertTrue(probe.distinctVisited < width,
                    () -> "cancellation was delayed until all " + probe.distinctVisited + " input slots were visited");
        } finally {
            probe.armed = false;
            Thread.interrupted();
        }
    }

    private static Fixture fixture(int upstreamCopies, int smallCopies, int largeCopies, Object source) {
        var upstream = new CraftPattern<>("A", 2, List.of(CraftInput.of("raw", 1)), null);
        var small = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1)), source);
        var large = new CraftPattern<>("T", 3, List.of(CraftInput.of("A", 4)), source);
        var builder = CraftGraph.<String>builder().stock("raw", 10);
        for (int index = 0; index < upstreamCopies; index++) builder.pattern(upstream);
        for (int index = 0; index < smallCopies; index++) builder.pattern(small);
        for (int index = 0; index < largeCopies; index++) builder.pattern(large);
        return new Fixture(builder.build(), upstream, small, large);
    }

    private static void assertImprovesToFive(Fixture fixture) {
        var incumbent = MaterialDagReplay.tryPlan(fixture.graph(),
                Map.of(fixture.upstream(), 2L, fixture.small(), 4L), "T", 4);
        assertNotNull(incumbent);
        assertEquals(6, incumbent.firings().values().stream().mapToLong(Long::longValue).sum());

        var result = UpstreamBatchOptimizer.tryImprove(fixture.graph(), "T", 4, incumbent,
                List.of("T", "A", "raw"), work -> true, () -> true);

        assertNotNull(result);
        assertEquals(Map.of(fixture.upstream(), 3L, fixture.small(), 1L, fixture.large(), 1L), result.firings());
        assertEquals(5, result.firings().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of("raw", 3L), result.usedStock());
        assertTrue(result.firings().keySet().stream().allMatch(pattern -> pattern == fixture.upstream()
                || pattern == fixture.small() || pattern == fixture.large()), "preserve the original pattern identities");
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
        var certified = MaterialDagReplay.tryPlan(fixture.graph(), result.firings(), "T", 4);
        assertNotNull(certified);
        assertEquals(result.usedStock(), certified.usedStock());
        assertEquals(Map.of(fixture.upstream(), 2L, fixture.small(), 4L), incumbent.firings());
    }

    private record Fixture(CraftGraph<String> graph, CraftPattern<String> upstream,
            CraftPattern<String> small, CraftPattern<String> large) {}

    private static final class CancellationProbe {
        final boolean[] visited;
        boolean armed;
        boolean interrupted;
        int distinctVisited;

        CancellationProbe(int width) { visited = new boolean[width]; }

        void observe(int index) {
            if (!armed) return;
            if (!interrupted) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
            if (!visited[index]) {
                visited[index] = true;
                distinctVisited++;
            }
        }
    }

    private static final class ProbeKey {
        final int id;
        final CancellationProbe probe;

        ProbeKey(int id, CancellationProbe probe) { this.id = id; this.probe = probe; }

        @Override
        public int hashCode() {
            if (probe != null) probe.observe(id);
            return id;
        }

        @Override
        public boolean equals(Object other) {
            if (probe != null) probe.observe(id);
            return other instanceof ProbeKey key && id == key.id;
        }
    }
}
