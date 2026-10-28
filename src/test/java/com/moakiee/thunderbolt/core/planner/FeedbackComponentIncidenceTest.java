package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;

class FeedbackComponentIncidenceTest {
    @Test
    void indexedComponentsMatchFullTableClassificationIncludingNonFlowContacts() throws Exception {
        var random = new Random(2026101517L);
        var source = new ReusableStockSource(new Object(), new Object());
        Method stable = method("stablePatterns", List.class, Map.class);
        Method classify = method("classify", Set.class, List.class, Map.class);
        Method fallback = method("classifyFallback", Set.class, List.class, Map.class);
        int multipleComponents = 0;
        for (int sample = 0; sample < 384; sample++) {
            var order = new ArrayList<Integer>();
            var selected = new LinkedHashMap<Integer, List<CraftPattern<Integer>>>();
            for (int key = 0; key < 16; key++) order.add(key);
            // Four independent cycles stay separate unless a generated contact joins them.
            for (int key = 0; key < 8; key++) add(selected, new CraftPattern<>(key, 1,
                    List.of(CraftInput.of(key ^ 1, 1)), "ring"));
            for (int route = 0; route < 8; route++) {
                int input = random.nextInt(16), output = random.nextInt(16);
                var in = switch (random.nextInt(7)) {
                    case 0 -> CraftInput.returned(input, 1);
                    case 1 -> CraftInput.finiteUse(input, 1, 3);
                    case 2 -> CraftInput.returnedFrom(input, 1, source);
                    case 3 -> CraftInput.consumedReturning(input, 1, random.nextInt(16));
                    default -> CraftInput.of(input, 1);
                };
                var sides = random.nextBoolean() ? List.<CraftOutput<Integer>>of()
                        : List.of(CraftOutput.of(random.nextInt(16), 1));
                var pattern = new CraftPattern<>(output, 1,
                        random.nextBoolean() ? List.of(in) : List.of(in, in), sides, "same-source");
                add(selected, pattern);
                if (random.nextBoolean()) add(selected, pattern);
                if (random.nextBoolean()) add(selected, new CraftPattern<>(output, 1,
                        pattern.inputs(), sides, "same-source"));
            }
            Collections.shuffle(order, random);
            var actual = ConservativeFeedbackAnalysis.analyzeAll(order, selected);
            if (actual.cyclicComponents().size() > 1) multipleComponents++;
            var ranks = new HashMap<Integer, Integer>();
            for (int i = 0; i < order.size(); i++) ranks.put(order.get(i), i);
            Object allPatterns = stable.invoke(null, order, selected);
            var expectedCycles = new ArrayList<>();
            var expectedFallbacks = new ArrayList<>();
            for (var states : actual.cyclicComponents()) {
                Object cycle = classify.invoke(null, states, allPatterns, ranks);
                if (cycle != null) expectedCycles.add(cycle);
                else {
                    Object other = fallback.invoke(null, states, allPatterns, ranks);
                    if (other != null) expectedFallbacks.add(other);
                }
            }
            assertEquals(expectedCycles, actual.components(), "cycles sample=" + sample);
            assertEquals(expectedFallbacks, actual.fallbacks(), "fallbacks sample=" + sample);
        }
        assertTrue(multipleComponents > 300, "exercise incidence indexing, not only its single-SCC bypass");
    }

    @Test
    void independentRingsScaleWithIncidenceInsteadOfRingsTimesRecipes() {
        long small = ringHashWork(128), large = ringHashWork(512);
        assertTrue(large <= small * 5, () -> "key lookups grew from " + small + " to " + large);
    }

    @Test
    void incidenceConstructionPropagatesExternalCancellation() {
        var exit = new PlanningExitException("cancel component incidence");
        var context = new PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void report(PlanningDiagnosticSnapshot snapshot) {}
            public void checkpoint() {
                if (StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        frame.getClassName().equals(ConservativeFeedbackAnalysis.class.getName())
                                && frame.getMethodName().equals("patternsByComponent")))) throw exit;
            }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class, () -> ringHashWork(2)));
        }
    }

    private static long ringHashWork(int count) {
        var order = new ArrayList<CountingKey>();
        var selected = new LinkedHashMap<CountingKey, List<CraftPattern<CountingKey>>>();
        for (int i = 0; i < count; i++) {
            var a = new CountingKey(i * 2); var b = new CountingKey(i * 2 + 1);
            order.add(a); order.add(b);
            add(selected, new CraftPattern<>(a, 1, List.of(CraftInput.of(b, 1)), null));
            add(selected, new CraftPattern<>(b, 1, List.of(CraftInput.of(a, 1)), null));
        }
        CountingKey.lookups = 0;
        var result = ConservativeFeedbackAnalysis.analyzeAll(order, selected);
        long work = CountingKey.lookups;
        assertEquals(count, result.components().size());
        assertTrue(result.fallbacks().isEmpty());
        return work;
    }

    private record CountingKey(int id) {
        static long lookups;
        @Override public int hashCode() { lookups++; return id; }
    }

    private static <K> void add(Map<K, List<CraftPattern<K>>> selected, CraftPattern<K> pattern) {
        selected.computeIfAbsent(pattern.output(), ignored -> new ArrayList<>()).add(pattern);
    }

    private static Method method(String name, Class<?>... arguments) throws Exception {
        var method = ConservativeFeedbackAnalysis.class.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }
}
