package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConservativeFeedbackAnalysisTest {
    @Test
    void independentCyclesAndTheirWideConsumerAreScannedInLinearWork() {
        var small = analyzeIndependentCycles(64);
        var large = analyzeIndependentCycles(256);
        assertEquals(64, small.components);
        assertEquals(256, large.components);
        assertTrue(large.hashCalls < small.hashCalls * 6,
                () -> "quadrupling independent cycles grew key visits from "
                        + small.hashCalls + " to " + large.hashCalls);
    }

    private static Measurement analyzeIndependentCycles(int branches) {
        var counts = new Counter();
        var target = new CountedKey(0, counts);
        var root = new ArrayList<CraftInput<CountedKey>>();
        var byOutput = new LinkedHashMap<CountedKey, List<CraftPattern<CountedKey>>>();
        for (int i = 0; i < branches; i++) {
            var a = new CountedKey(2 * i + 1, counts);
            var b = new CountedKey(2 * i + 2, counts);
            byOutput.put(a, List.of(new CraftPattern<>(a, 1, List.of(CraftInput.of(b, 1)), null)));
            byOutput.put(b, List.of(new CraftPattern<>(b, 1, List.of(CraftInput.of(a, 1)), null)));
            root.add(CraftInput.of(a, 1));
        }
        byOutput.put(target, List.of(new CraftPattern<>(target, 1, root, null)));
        counts.hashCalls = 0;
        var result = ConservativeFeedbackAnalysis.analyzeAll(List.copyOf(byOutput.keySet()), byOutput);
        assertTrue(result.fallbacks().isEmpty());
        return new Measurement(result.components().size(), counts.hashCalls);
    }

    @Test
    void componentIndexKeepsExternalProducersReadArcsAndRepeatedOutputs() {
        var a = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)), null);
        var b = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)), null);
        var entry = new CraftPattern<>("A", 1, List.of(CraftInput.of("raw", 1)),
                List.of(CraftOutput.of("A", 1)), null);
        var graph = new LinkedHashMap<String, List<CraftPattern<String>>>();
        graph.put("A", List.of(a, entry));
        graph.put("B", List.of(b));
        graph.put("raw", List.of());
        var result = ConservativeFeedbackAnalysis.analyzeAll(List.copyOf(graph.keySet()), graph);
        assertEquals(1, result.components().size());
        assertTrue(result.components().get(0).hasExternalProducer());
        assertEquals(List.of(b, a), result.components().get(0).patterns());

        // An external consumer's read arc still disqualifies the simple conversion proof.
        graph.put("T", List.of(new CraftPattern<>("T", 1, List.of(CraftInput.returned("A", 1)), null)));
        result = ConservativeFeedbackAnalysis.analyzeAll(List.copyOf(graph.keySet()), graph);
        assertTrue(result.components().isEmpty());
        assertEquals(1, result.fallbacks().size());
        assertEquals(List.of(a, b), result.fallbacks().get(0).patterns());
    }

    private record Measurement(int components, long hashCalls) {}
    private static final class Counter { long hashCalls; }
    private record CountedKey(int id, Counter counts) {
        @Override public int hashCode() { counts.hashCalls++; return id; }
    }
}
