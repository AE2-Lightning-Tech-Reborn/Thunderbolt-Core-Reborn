package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import java.util.*;
import org.junit.jupiter.api.Test;

class MaterialDagIndexedReplayTest {
    @Test void indexedAndGenericCertificatesAgreeOnRandomDagVectorsAndShortages() {
        var random = new Random(20261006L);
        for (int trial = 0; trial < 32; trial++) {
            var builder = CraftGraph.<String>builder();
            var counts = new IdentityHashMap<CraftPattern<String>, Long>();
            String previous = "raw";
            long amount = trial % 2 == 0 ? 1L : 1_000_000_000_000L;
            for (int i = 0; i < 48; i++) {
                var inputs = new ArrayList<CraftInput<String>>();
                inputs.add(CraftInput.of(previous, 1));
                if (random.nextBoolean()) inputs.add(CraftInput.of("fuel", 1));
                var pattern = new CraftPattern<>("item" + i, 1, inputs,
                        List.of(CraftOutput.of("unused" + random.nextInt(8), 1)), null);
                builder.pattern(pattern);
                counts.put(pattern, amount);
                previous = pattern.output();
            }
            builder.stock("fuel", 48 * amount).stock("raw", amount - Math.min(amount, trial % 3));
            var graph = builder.build();
            var padded = forceIndexed(counts);
            assertEquivalent(MaterialDagReplay.tryPlan(graph, counts, previous, amount),
                    MaterialDagReplay.tryPlan(graph, padded, previous, amount));
            var generic = MaterialDagReplay.tryLeafMissingPlan(graph, counts, previous, amount);
            var indexed = MaterialDagReplay.tryLeafMissingPlan(graph, padded, previous, amount);
            assertEquivalent(generic, indexed);
            assertNotNull(indexed);
            assertEquals(new HashMap<>(counts), new HashMap<>(indexed.firings()));
            var supplied = graph.withAdditionalStock(indexed.missing());
            assertNotNull(MaterialDagReplay.tryPlan(supplied, padded, previous, amount));
            assertThrows(UnsupportedOperationException.class, () -> indexed.grossDemand().put("x", 1L));
        }
    }

    @Test void duplicateArcsAndIdenticalRecipeRegistrationsPreserveMaterialAccounting() {
        var producer = new CraftPattern<>("A", 2, List.of(CraftInput.of("raw", 1), CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("side", 1), CraftOutput.of("side", 1)), null);
        var duplicate = new CraftPattern<>("A", 2, producer.inputs(), producer.byproducts(), null);
        var root = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 3), CraftInput.of("side", 3)), null);
        var graph = CraftGraph.<String>builder().stock("raw", 4).pattern(producer).pattern(duplicate).pattern(root).build();
        var counts = new IdentityHashMap<CraftPattern<String>, Long>();
        counts.put(producer, 1L); counts.put(duplicate, 1L); counts.put(root, 1L);
        var result = MaterialDagReplay.tryPlan(graph, forceIndexed(counts), "T", 1);
        assertEquals(MaterialDagReplay.tryPlan(graph, counts, "T", 1), result);
        assertNotNull(result);
        assertEquals(3, result.firings().size());
        assertEquals(Map.of("raw", 4L), result.usedStock());
    }

    @Test void feedbackExtraPrimaryBatchesAndStatefulInputsStillFailClosed() {
        for (var pattern : List.of(
                new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("raw", 1)), null),
                new CraftPattern<>("T", 1, List.of(CraftInput.returned("raw", 1)), null))) {
            var graph = CraftGraph.<String>builder().stock("raw", 3).pattern(pattern).build();
            assertNull(MaterialDagReplay.tryPlan(graph, forceIndexed(Map.of(pattern, 1L)), "T", 1));
        }
        var pattern = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 1)), null);
        var graph = CraftGraph.<String>builder().stock("raw", 3).pattern(pattern).build();
        assertNull(MaterialDagReplay.tryPlan(graph, forceIndexed(Map.of(pattern, 2L)), "T", 1));
    }

    @Test void arbitraryCollidingItemKeysStayDisjointFromPatternIds() {
        record Key(int id) { @Override public int hashCode() { return 0; } }
        var builder = CraftGraph.<Key>builder().stock(new Key(0), 1);
        var counts = new IdentityHashMap<CraftPattern<Key>, Long>();
        for (int i = 1; i <= 256; i++) {
            var pattern = new CraftPattern<>(new Key(i), 1, List.of(CraftInput.of(new Key(i - 1), 1)), null);
            builder.pattern(pattern); counts.put(pattern, 1L);
        }
        var result = MaterialDagReplay.tryPlan(builder.build(), counts, new Key(256), 1);
        assertNotNull(result);
        assertTrue(result.feasible());
        assertEquals(Map.of(new Key(0), 1L), result.usedStock());
        assertEquals(257, result.itemsProcessed());
    }

    @Test void cancellationAtConstructionTraversalAndBalanceRestoresCallerScope() {
        var builder = CraftGraph.<String>builder().stock("raw", 1);
        var counts = new IdentityHashMap<CraftPattern<String>, Long>();
        String previous = "raw";
        for (int i = 0; i < 256; i++) {
            var pattern = new CraftPattern<>("item" + i, 1, List.of(CraftInput.of(previous, 1)), null);
            builder.pattern(pattern); counts.put(pattern, 1L); previous = pattern.output();
        }
        var graph = builder.build();
        int[] checks = {0};
        try (var ignored = PlanningCancellation.bind(context(() -> checks[0]++))) {
            assertNotNull(MaterialDagReplay.tryPlan(graph, counts, "item255", 1));
        }
        int total = checks[0];
        for (int point : new int[] {1, total / 4, total / 2, 3 * total / 4, total}) {
            var exit = new PlanningExitException("stop indexed certificate");
            checks[0] = 0;
            try (var ignored = PlanningCancellation.bind(context(() -> {
                if (++checks[0] == point) throw exit;
            }))) {
                assertSame(exit, assertThrows(PlanningExitException.class,
                        () -> MaterialDagReplay.tryPlan(graph, counts, "item255", 1)));
            }
            assertDoesNotThrow(PlanningCancellation::check);
            assertNotNull(MaterialDagReplay.tryPlan(graph, counts, "item255", 1));
        }
    }

    private static PlanningAttemptContext context(Runnable checkpoint) {
        return new PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void report(PlanningDiagnosticSnapshot snapshot) {}
            public void checkpoint() { checkpoint.run(); }
        };
    }

    private static <K> void assertEquivalent(CraftPlan<K> expected, CraftPlan<K> actual) {
        if (expected == null) { assertNull(actual); return; }
        assertNotNull(actual);
        // IdentityHashMap compares boxed values by reference; compare exact numeric counts instead.
        assertEquals(new HashMap<>(expected.firings()), new HashMap<>(actual.firings()));
        assertEquals(expected.usedStock(), actual.usedStock());
        assertEquals(expected.usedReusableStock(), actual.usedReusableStock());
        assertEquals(expected.missing(), actual.missing());
        assertEquals(expected.grossDemand(), actual.grossDemand());
        assertEquals(expected.itemsProcessed(), actual.itemsProcessed());
        assertEquals(expected.supported(), actual.supported());
        assertEquals(expected.feasible(), actual.feasible());
        assertEquals(expected.budgetExhausted(), actual.budgetExhausted());
    }

    private static Map<CraftPattern<String>, Long> forceIndexed(Map<CraftPattern<String>, Long> counts) {
        var padded = new IdentityHashMap<CraftPattern<String>, Long>(counts);
        for (int i = 0; padded.size() < 128; i++)
            padded.put(new CraftPattern<>("inactive" + i, 1, List.of(CraftInput.of("inactive-raw", 1)), null), 0L);
        return padded;
    }
}
