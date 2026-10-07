package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge.SparseLongMatrix;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PetriBlockCatalogPrefixTest {
    @Test void everyCatalogWireAgreesWithIndependentUnitFiringPrefixes() {
        var random = new Random(0x50424350L);
        for (int sample = 0; sample < 80; sample++) {
            int recipes = 2 + random.nextInt(6), items = recipes + 2;
            long[][] pre = new long[recipes][items], post = new long[recipes][items];
            int[] groups = new int[items], outputs = new int[recipes];
            Arrays.fill(groups, 2);
            for (int r = 0; r < recipes; r++) {
                groups[r] = r % 2;
                outputs[r] = r;
                for (int i = 0; i < items; i++) {
                    pre[r][i] = random.nextInt(4);
                    post[r][i] = random.nextInt(4);
                }
            }
            var suggested = List.<PetriExecutionTrace.Node>of(
                    new PetriExecutionTrace.Repeat(new PetriExecutionTrace.Sequence(List.of(
                            new PetriExecutionTrace.Fire(0, 1), new PetriExecutionTrace.Fire(0, 1))), 3));
            var catalog = PetriBlockCatalog.build(pre, post, groups, outputs, Set.of(0, 1), suggested);
            assertFalse(catalog.isEmpty());
            assertTrue(catalog.size() <= 96);
            var seen = new HashSet<String>();
            for (var block : catalog) {
                var required = new BigInteger[items];
                var delta = new BigInteger[items];
                Arrays.fill(required, BigInteger.ZERO);
                Arrays.fill(delta, BigInteger.ZERO);
                long[] counts = new long[recipes];
                for (int r : unitFirings(block.trace())) {
                    assertEquals(block.group(), groups[outputs[r]]);
                    counts[r]++;
                    for (int i = 0; i < items; i++) {
                        required[i] = required[i].max(BigInteger.valueOf(pre[r][i]).subtract(delta[i]));
                        delta[i] = delta[i].add(BigInteger.valueOf(post[r][i]))
                                .subtract(BigInteger.valueOf(pre[r][i]));
                    }
                }
                assertArrayEquals(counts, Arrays.copyOfRange(block.wire(), 1, 1 + recipes));
                for (int i = 0; i < items; i++) {
                    assertEquals(required[i].longValueExact(), block.wire()[1 + recipes + i]);
                    assertEquals(delta[i].longValueExact(), block.wire()[1 + recipes + items + i]);
                }
                assertTrue(seen.add(block.group() + ":" + Arrays.toString(counts) + Arrays.toString(required)));
                var certificate = PetriExecutionTrace.certificate(block.trace(), pre, post, counts, 0, 0);
                assertNotNull(certificate);
                assertArrayEquals(delta, certificate.delta());
                // Certificate construction can mutate its own required marking. It must never
                // mutate the catalog's saved prefix or the independently rebuilt next certificate.
                certificate.required()[0] = BigInteger.valueOf(Long.MAX_VALUE);
                var rebuilt = PetriExecutionTrace.certificate(block.trace(), pre, post, counts, 0, 0);
                assertArrayEquals(required, rebuilt.required());
                counts[0]++;
                assertNull(PetriExecutionTrace.certificate(block.trace(), pre, post, counts, 0, 0));
            }
            assertThrows(UnsupportedOperationException.class, () -> catalog.clear());
        }
    }

    @Test void cancellationDuringDiscoveryRestoresCallerAndDoesNotPoisonNextCatalog() {
        long[][] pre = {{2, 0, 1}, {0, 2, 1}}, post = {{0, 1, 0}, {1, 0, 0}};
        int[] groups = {0, 0, 1}, outputs = {1, 0};
        var expected = signatures(PetriBlockCatalog.build(pre, post, groups, outputs, Set.of(0), List.of()));
        var outerChecks = new AtomicInteger();
        var exit = new PlanningExitException("cancel discovery");
        PlanningAttemptContext outer = context(outerChecks::incrementAndGet);
        PlanningAttemptContext inner = context(() -> {
            if (StackWalker.getInstance().walk(frames -> frames.filter(f ->
                    f.getClassName().endsWith("PetriBlockCatalog") && f.getMethodName().equals("discover")).count()) >= 3)
                throw exit;
        });
        try (var outerScope = PlanningCancellation.bind(outer)) {
            try (var innerScope = PlanningCancellation.bind(inner)) {
                assertSame(exit, assertThrows(PlanningExitException.class, () ->
                        PetriBlockCatalog.build(pre, post, groups, outputs, Set.of(0), List.of())));
            }
            PlanningCancellation.check();
            assertEquals(1, outerChecks.get());
            assertEquals(expected, signatures(PetriBlockCatalog.build(
                    pre, post, groups, outputs, Set.of(0), List.of())));
        }
    }

    @Test void cacheCapacityBoundaryPreservesTheSameProbeLimitedLossyPrefix() {
        // For two recipes, these shapes put cached summary arrays at exactly 524,288
        // cells and then four cells above it. Both have only three discovery probes.
        for (int items : new int[]{131_071, 131_072}) {
            var pre = new SparseLongMatrix(2, items);
            var post = new SparseLongMatrix(2, items);
            pre.set(0, 0, 2); post.set(0, 0, 1);
            pre.set(1, 1, 2); post.set(1, 1, 1);
            int[] groups = new int[items];
            Arrays.fill(groups, 1); groups[0] = groups[1] = 0;
            var catalog = PetriBlockCatalog.build(pre, post, groups, new int[]{0, 1}, Set.of(0), List.of());
            assertEquals(4, catalog.size());
            assertEquals(List.of(new PetriExecutionTrace.Fire(0, 1), new PetriExecutionTrace.Fire(1, 1),
                    new PetriExecutionTrace.Sequence(List.of(new PetriExecutionTrace.Fire(0, 1),
                            new PetriExecutionTrace.Fire(1, 1))),
                    new PetriExecutionTrace.Sequence(List.of(new PetriExecutionTrace.Fire(0, 1),
                            new PetriExecutionTrace.Fire(0, 1)))),
                    catalog.stream().map(PetriBlockCatalog.Block::trace).toList());
            var twice = catalog.get(3).wire();
            assertEquals(2, twice[1]); assertEquals(0, twice[2]);
            assertEquals(3, twice[3]); assertEquals(0, twice[4]);
            assertEquals(-2, twice[3 + items]);
            for (int i = 1; i < items; i++) assertEquals(0, twice[3 + items + i]);
            for (int i = 2; i < items; i++) assertEquals(0, twice[3 + i]);
        }
    }

    private static PlanningAttemptContext context(Runnable checkpoint) {
        return new PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void checkpoint() { checkpoint.run(); }
            public void report(PlanningDiagnosticSnapshot snapshot) { }
        };
    }

    /** Small fixtures only: execute original recipe identities one firing at a time. */
    private static List<Integer> unitFirings(PetriExecutionTrace.Node node) {
        var result = new ArrayList<Integer>();
        if (node instanceof PetriExecutionTrace.Fire fire) {
            assertTrue(fire.copies() <= 6);
            for (long i = 0; i < fire.copies(); i++) result.add(fire.recipe());
        } else if (node instanceof PetriExecutionTrace.Sequence sequence) {
            for (var step : sequence.steps()) result.addAll(unitFirings(step));
        } else if (node instanceof PetriExecutionTrace.Repeat repeat) {
            assertTrue(repeat.copies() <= 3);
            for (long i = 0; i < repeat.copies(); i++) result.addAll(unitFirings(repeat.body()));
        }
        return result;
    }

    private static List<String> signatures(List<PetriBlockCatalog.Block> blocks) {
        return blocks.stream().map(b -> b.trace() + ":" + Arrays.toString(b.wire())).toList();
    }
}
