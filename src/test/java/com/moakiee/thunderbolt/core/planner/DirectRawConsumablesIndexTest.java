package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class DirectRawConsumablesIndexTest {
    @Test
    void zeroOneAndManyRawInputsMatchIndependentGroupedArithmetic() throws Exception {
        var random = new Random(2026100337L);
        var source = new ReusableStockSource("host", "pool");
        long[] amounts = {1, 2, 17, Sat.SAT - 1, Sat.SAT, Long.MAX_VALUE};
        for (int sample = 0; sample < 512; sample++) {
            Map<Key, List<CraftPattern<Key>>> selected = new LinkedHashMap<>();
            for (int key = 0; key < 8; key++) {
                if (random.nextInt(4) == 0) {
                    var producer = new CraftPattern<>(new Key(key), 1,
                            List.of(CraftInput.of(new Key(99), 1)), null);
                    selected.put(new Key(key), List.of(producer));
                } else if (random.nextBoolean()) {
                    selected.put(new Key(key), List.of());
                }
            }
            var inputs = new ArrayList<CraftInput<Key>>();
            for (int slot = 0; slot < sample % 13; slot++) {
                var key = new Key(random.nextInt(8));
                long amount = amounts[random.nextInt(amounts.length)];
                inputs.add(switch (random.nextInt(7)) {
                    case 0 -> CraftInput.returned(key, amount);
                    case 1 -> CraftInput.finiteUse(key, amount, 3);
                    case 2 -> CraftInput.returnedFrom(key, amount, source);
                    case 3 -> CraftInput.consumedReturning(key, amount, new Key(90));
                    default -> CraftInput.of(key, amount);
                });
            }
            var pattern = new CraftPattern<>(new Key(100), 1, inputs, null);
            selected.put(pattern.output(), sample % 2 == 0 ? List.of(pattern) : List.of(pattern, pattern));
            var builder = CraftGraph.<Key>builder();
            selected.values().forEach(patterns -> patterns.forEach(builder::pattern));
            assertMatchesOracle(selected, index(builder.build(), selected), "sample=" + sample);
        }
    }

    @Test
    void equalKeysAccumulateBeforeAndAfterPromotionAndKeepTheFirstKeyObject() throws Exception {
        var firstA = new Key(1);
        var equalA = new Key(1);
        var firstB = new Key(2);
        assertNotSame(firstA, equalA);
        var single = new CraftPattern<>(new Key(10), 1,
                List.of(CraftInput.of(firstA, 2), CraftInput.of(equalA, 3)), null);
        var multi = new CraftPattern<>(new Key(11), 1, List.of(
                CraftInput.of(firstA, 2), CraftInput.of(equalA, 3), CraftInput.of(firstB, 7),
                CraftInput.of(new Key(1), 5), CraftInput.of(new Key(2), 11)), null);
        var selected = Map.of(single.output(), List.of(single), multi.output(), List.of(multi));
        var graph = CraftGraph.<Key>builder().pattern(single).pattern(multi).build();
        var actual = index(graph, selected);

        assertMatchesOracle(selected, actual, "equal keys");
        assertEquals(Map.of(firstA, 5L), actual.get(single));
        assertEquals(Map.of(firstA, 10L, firstB, 18L), actual.get(multi));
        assertSame(firstA, actual.get(single).keySet().iterator().next());
        assertSame(firstA, actual.get(multi).keySet().stream().filter(firstA::equals).findFirst().orElseThrow());
        assertSame(firstB, actual.get(multi).keySet().stream().filter(firstB::equals).findFirst().orElseThrow());
    }

    @Test
    void duplicateSlotsSaturateButSingleStoredSlotKeepsItsOriginalLongAmount() throws Exception {
        var single = new CraftPattern<>("one", 1,
                List.of(CraftInput.of("raw", Long.MAX_VALUE)), null);
        var repeated = new CraftPattern<>("two", 1,
                List.of(CraftInput.of("raw", Sat.SAT - 1), CraftInput.of("raw", 7)), null);
        var promoted = new CraftPattern<>("many", 1, List.of(
                CraftInput.of("raw", Long.MAX_VALUE), CraftInput.of("other", 1),
                CraftInput.of("raw", Long.MAX_VALUE)), null);
        var selected = Map.of("one", List.of(single), "two", List.of(repeated), "many", List.of(promoted));
        var graph = CraftGraph.<String>builder().pattern(single).pattern(repeated).pattern(promoted).build();
        var actual = index(graph, selected);

        assertMatchesOracle(selected, actual, "saturation");
        assertEquals(Long.MAX_VALUE, actual.get(single).get("raw"));
        assertEquals(Sat.SAT, actual.get(repeated).get("raw"));
        assertEquals(Map.of("raw", Sat.SAT, "other", 1L), actual.get(promoted));
    }

    @Test
    void rawMembershipComesFromTheSelectedProducerListsAfterCuts() throws Exception {
        var target = new CraftPattern<>("T", 1, List.of(CraftInput.of("I", 2)), null);
        var intermediate = new CraftPattern<>("I", 1, List.of(CraftInput.of("raw", 3)), null);
        var graph = CraftGraph.<String>builder().pattern(target).pattern(intermediate).build();
        var retained = Map.of("T", List.of(target), "I", List.of(intermediate));
        var cut = Map.of("T", List.of(target), "I", List.<CraftPattern<String>>of());
        var omitted = Map.of("T", List.of(target));

        assertFalse(index(graph, retained).containsKey(target));
        for (var selected : List.of(cut, omitted)) {
            var actual = index(graph, selected);
            assertMatchesOracle(selected, actual, "cut producer");
            assertEquals(Map.of("I", 2L), actual.get(target));
        }
        assertFalse(graph.patternsFor("I").isEmpty(), "the uncut source graph still has a producer");
    }

    @Test
    void returnedAndHostInputsAreExcludedWhileConsumedContainersRemainRaw() throws Exception {
        var source = new ReusableStockSource("host", "pool");
        var pattern = new CraftPattern<>("T", 1, List.of(
                CraftInput.of("raw", 2), CraftInput.returned("returned", 3),
                CraftInput.finiteUse("finite", 4, 5), CraftInput.returnedFrom("private", 6, source),
                CraftInput.consumedReturning("full", 7, "empty")), null);
        var selected = Map.of("T", List.of(pattern));
        var graph = CraftGraph.<String>builder().pattern(pattern).build();
        var actual = index(graph, selected);

        assertTrue(graph.hasByproducts());
        assertMatchesOracle(selected, actual, "special inputs");
        assertEquals(Map.of("raw", 2L, "full", 7L), actual.get(pattern));
    }

    @Test
    void immutableIndexRetainsDistinctRecipeIdentitiesEvenWhenTheirSourceIsShared() throws Exception {
        Object source = new Object();
        var first = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 2)), source);
        var second = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 2)), source);
        var selected = Map.of("T", List.of(first, first, second));
        var graph = CraftGraph.<String>builder().pattern(first).pattern(second).build();
        var actual = index(graph, selected);

        assertEquals(2, actual.size());
        assertEquals(Map.of("raw", 2L), actual.get(first));
        assertEquals(Map.of("raw", 2L), actual.get(second));
        assertThrows(UnsupportedOperationException.class, () -> actual.remove(first));
        assertThrows(UnsupportedOperationException.class, () -> actual.get(first).put("raw", 3L));
        assertFalse(actual.containsKey(new CraftPattern<>("T", 1, first.inputs(), source)));
    }

    @Test
    void existingTargetAndRawStockDoNotChangeStructuralRawRequirements() throws Exception {
        var pattern = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 2)), null);
        var selected = Map.of("T", List.of(pattern));
        var graph = CraftGraph.<String>builder().pattern(pattern).stock("T", 1206).stock("raw", 4).build();
        for (var projected : List.of(graph, graph.withAdditionalStock(Map.of("T", 2L, "raw", 1L)),
                graph.withStockLimits(Map.of("T", 1L, "raw", 2L)),
                graph.withoutStock(Map.of("T", 1206L, "raw", 4L)))) {
            var actual = index(projected, selected);
            assertMatchesOracle(selected, actual, "stock projection");
            assertEquals(Map.of("raw", 2L), actual.get(pattern));
        }
    }

    @Test
    void existingCancellationCheckpointPropagatesThreadAndBoundAttemptExits() {
        var pattern = new CraftPattern<>("T", 1, List.of(CraftInput.of("raw", 2)), null);
        var selected = Map.of("T", List.of(pattern));
        var graph = CraftGraph.<String>builder().pattern(pattern).build();
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> index(graph, selected));
        } finally {
            Thread.interrupted();
        }
        var exit = new PlanningExitException("attempt exhausted");
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void checkpoint() { throw exit; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) { }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class, () -> index(graph, selected)));
        }
    }

    /** Group the original slots, then sum exactly; do not use the implementation's Sat.add. */
    private static <K> void assertMatchesOracle(Map<K, List<CraftPattern<K>>> selected,
            Map<CraftPattern<K>, Map<K, Long>> actual, String label) {
        var expected = new IdentityHashMap<CraftPattern<K>, Map<K, Long>>();
        for (var patterns : selected.values()) {
            for (var pattern : patterns) {
                var slots = new LinkedHashMap<K, List<Long>>();
                for (var input : pattern.inputs()) {
                    var producers = selected.get(input.key());
                    if (!input.returned() && input.reusableStockSource() == null
                            && (producers == null || producers.isEmpty())) {
                        slots.computeIfAbsent(input.key(), ignored -> new ArrayList<>()).add(input.amount());
                    }
                }
                if (slots.isEmpty()) continue;
                var sums = new HashMap<K, Long>();
                slots.forEach((key, amounts) -> {
                    var sum = amounts.stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
                    // HashMap.merge stores the first slot unchanged; saturation begins on duplicates.
                    sums.put(key, amounts.size() == 1 ? amounts.getFirst()
                            : sum.min(BigInteger.valueOf(Sat.SAT)).longValueExact());
                });
                expected.put(pattern, sums);
            }
        }
        assertEquals(expected.size(), actual.size(), label);
        expected.forEach((pattern, values) -> assertEquals(values, actual.get(pattern), label));
    }

    @SuppressWarnings("unchecked")
    private static <K> Map<CraftPattern<K>, Map<K, Long>> index(
            CraftGraph<K> graph, Map<K, List<CraftPattern<K>>> selected) throws Exception {
        Class<?> diagnosticsType = Class.forName(CraftPlannerV2.class.getName() + "$DiagnosticsCollector");
        Class<?> searchBudgetType = Class.forName(CraftPlannerV2.class.getName() + "$SearchBudget");
        var diagnosticsConstructor = diagnosticsType.getDeclaredConstructor(
                int.class, int.class, CraftPlannerV2.PlanningSession.class);
        diagnosticsConstructor.setAccessible(true);
        var diagnostics = diagnosticsConstructor.newInstance(1, 1, new CraftPlannerV2.PlanningSession<K>());
        var constructor = CraftPlannerV2.class.getDeclaredConstructor(CraftGraph.class, int.class,
                searchBudgetType, BoundedIntegerLinearSolver.WorkBudget.class, diagnosticsType);
        constructor.setAccessible(true);
        var planner = constructor.newInstance(graph, 1, null, null, diagnostics);
        var patternsField = CraftPlannerV2.class.getDeclaredField("patternsByOutput");
        patternsField.setAccessible(true);
        ((Map<K, List<CraftPattern<K>>>) patternsField.get(planner)).putAll(selected);
        var method = CraftPlannerV2.class.getDeclaredMethod("indexDirectRawConsumables");
        method.setAccessible(true);
        try {
            method.invoke(planner);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw exception;
        }
        var indexField = CraftPlannerV2.class.getDeclaredField("directRawConsumablesByPattern");
        indexField.setAccessible(true);
        return (Map<CraftPattern<K>, Map<K, Long>>) indexField.get(planner);
    }

    /** Equal-but-distinct and colliding keys exercise equality without depending on hash order. */
    private record Key(int value) {
        @Override public int hashCode() { return 0; }
    }
}
