package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpstreamDefaultExpansionPruningTest {
    @Test
    void redundantGrowthLeavesRoomForTheProtectedNewBatchWithinSixtyFourCandidates() {
        var fixture = protectedBatchWithOneDuplicateTarget();
        var incumbent = plan(fixture, 3, 0, 2, 0, 0, 2, 0);
        var original = Map.copyOf(incumbent.firings());
        var originalStock = Map.copyOf(incumbent.usedStock());
        assertEquals(7, executions(incumbent));
        var search = search(fixture, incumbent);

        assertNull(search.tryEstablished());
        // One additional equivalent target recipe used to spend all 64 candidates before
        // the deferred neighborhood. The useful one-batch witness must now remain reachable.
        assertFalse(search.exhausted());
        var result = search.tryAdditional();

        assertCertified(fixture, incumbent, result, 6,
                Map.of("A", 2L, "B", 1L, "R", 1L, "S", 6L), 1, 1, 1, 1, 1, 1, 0);
        assertTrue(search.candidates <= 64);
        assertEquals(original, incumbent.firings());
        assertEquals(originalStock, incumbent.usedStock());
    }

    @Test
    void anInactiveFirstSupplierStillGetsItsOwnGrowthProposal() {
        var patterns = List.of(
                pattern("T", 1, CraftInput.of("A", 1)),
                pattern("T", 3, CraftInput.of("A", 5)),
                pattern("A", 2, CraftInput.of("raw", 1)),
                pattern("A", 1, CraftInput.of("raw", 1)));
        var builder = CraftGraph.<String>builder().stock("raw", 5);
        patterns.forEach(builder::pattern);
        var fixture = new Fixture(builder.build(), patterns, List.of("T", "A", "raw"), 4);
        var incumbent = plan(fixture, 4, 0, 0, 4);
        var original = Map.copyOf(incumbent.firings());
        var search = search(fixture, incumbent);

        var result = search.tryEstablished();

        // Growing the old supplier needs six raw. Keeping its four batches and adding one
        // batch from the unused first supplier yields the first valid mixed-target proposal.
        // A later independent supplier replacement can return a different improvement, so
        // asserting only that some improvement exists would miss an over-broad first-route skip.
        assertCertified(fixture, incumbent, result, 7, Map.of("raw", 5L), 1, 1, 1, 4);
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("raw", 4L), incumbent.usedStock());
    }

    @Test
    void jointReplacementCanCollapseMixedSupplyOntoItsAlreadyActiveFirstRoute() {
        var patterns = new ArrayList<>(List.of(
                pattern("T", 1, CraftInput.of("A", 1), CraftInput.of("B", 2)),
                pattern("A", 1, CraftInput.of("I", 2)),
                pattern("A", 1, CraftInput.of("J", 2)),
                pattern("B", 1, CraftInput.of("J", 2)),
                pattern("B", 1, CraftInput.of("I", 2)),
                pattern("I", 4, CraftInput.of("X", 2)),
                pattern("J", 3, CraftInput.of("Y", 1)),
                pattern("X", 1, CraftInput.of("R", 1)),
                pattern("Y", 1, CraftInput.of("S", 1))));
        // These distinct equivalent A recipes put the reversed joint replacement beyond
        // the 64-candidate frontier if the necessary B replacement is incorrectly skipped.
        patterns.add(pattern("A", 1, CraftInput.of("I", 2)));
        patterns.add(pattern("A", 1, CraftInput.of("I", 2)));
        var builder = CraftGraph.<String>builder().stock("R", 2).stock("S", 2);
        patterns.forEach(builder::pattern);
        var fixture = new Fixture(builder.build(), List.copyOf(patterns),
                List.of("T", "A", "B", "I", "J", "X", "Y", "R", "S"), 1);
        var incumbent = plan(fixture, 1, 1, 0, 1, 1, 1, 1, 2, 1, 0, 0);
        var original = Map.copyOf(incumbent.firings());
        assertEquals(9, executions(incumbent));
        var search = search(fixture, incumbent);

        var result = search.tryEstablished();

        // The first B route is already active, but replacing the whole B supply by it
        // differs from merely growing it. Together with switching A, it saves one firing.
        assertCertified(fixture, incumbent, result, 8, Map.of("S", 2L),
                1, 0, 1, 2, 0, 0, 2, 0, 2, 0, 0);
        assertTrue(search.candidates <= 64);
        assertEquals(original, incumbent.firings());
        assertEquals(Map.of("R", 2L, "S", 1L), incumbent.usedStock());
    }

    private static Fixture protectedBatchWithOneDuplicateTarget() {
        var patterns = List.of(
                pattern("T", 2, CraftInput.of("A", 2)),
                pattern("T", 3, CraftInput.of("A", 3), CraftInput.of("B", 4)),
                pattern("A", 2, CraftInput.of("I", 2)),
                pattern("A", 1, CraftInput.of("S", 3)),
                pattern("B", 3, CraftInput.of("S", 3)),
                pattern("I", 2, CraftInput.of("R", 1)),
                pattern("T", 2, CraftInput.of("A", 2)));
        var builder = CraftGraph.<String>builder().stock("A", 2).stock("B", 2).stock("I", 1)
                .stock("R", 13).stock("S", 8);
        patterns.forEach(builder::pattern);
        return new Fixture(builder.build(), patterns, List.of("T", "A", "B", "I", "R", "S"), 5);
    }

    @SafeVarargs
    private static CraftPattern<String> pattern(String output, long amount, CraftInput<String>... inputs) {
        return new CraftPattern<>(output, amount, List.of(inputs), null);
    }

    private static UpstreamBatchOptimizer.Search<String> search(Fixture fixture, CraftPlan<String> incumbent) {
        var search = UpstreamBatchOptimizer.startSearch(fixture.graph(), "T", fixture.amount(), incumbent,
                fixture.order(), work -> true, () -> true);
        assertNotNull(search);
        return search;
    }

    private static CraftPlan<String> plan(Fixture fixture, long... values) {
        var result = MaterialDagReplay.tryPlan(fixture.graph(), counts(fixture, values), "T", fixture.amount());
        assertNotNull(result);
        return result;
    }

    private static Map<CraftPattern<String>, Long> counts(Fixture fixture, long... values) {
        assertEquals(fixture.patterns().size(), values.length);
        var result = new IdentityHashMap<CraftPattern<String>, Long>();
        for (int i = 0; i < values.length; i++) if (values[i] > 0) result.put(fixture.patterns().get(i), values[i]);
        return result;
    }

    private static void assertCertified(Fixture fixture, CraftPlan<String> incumbent, CraftPlan<String> result,
            long expectedExecutions, Map<String, Long> expectedStock, long... expectedCounts) {
        assertNotNull(result);
        assertTrue(result.feasible());
        assertTrue(result.missing().isEmpty());
        assertEquals(expectedExecutions, executions(result));
        assertEquals(counts(fixture, expectedCounts), result.firings());
        assertEquals(expectedStock, result.usedStock());
        assertTrue(FeasibleConsumptionOptimizer.improves(incumbent, result));
        var replay = MaterialDagReplay.tryPlan(fixture.graph(), result.firings(), "T", fixture.amount());
        assertNotNull(replay);
        assertEquals(expectedStock, replay.usedStock());
    }

    private static long executions(CraftPlan<?> plan) {
        return plan.firings().values().stream().mapToLong(Long::longValue).sum();
    }

    private record Fixture(CraftGraph<String> graph, List<CraftPattern<String>> patterns,
            List<String> order, long amount) {}
}
