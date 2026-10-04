package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class ProducibleRouteOrderTest {
    @Test void indexedQueueMatchesIndependentFullScanOnRandomHypergraphs() {
        var random = new Random(2026093063L);
        for (int sample = 0; sample < 2_000; sample++) {
            int n = 2 + random.nextInt(18);
            var builder = CraftGraph.<Integer>builder();
            var distance = new HashMap<Integer, Integer>();
            for (int k = 0; k < n; k++) {
                if (random.nextBoolean()) distance.put(k, random.nextInt(n));
                if (random.nextBoolean()) builder.stock(k, 1 + random.nextInt(4));
                int routes = random.nextInt(5);
                for (int p = 0; p < routes; p++) {
                    var inputs = new ArrayList<CraftInput<Integer>>();
                    int slots = random.nextInt(5);
                    for (int i = 0; i < slots; i++) inputs.add(CraftInput.of(random.nextInt(n), 1));
                    var pattern = new CraftPattern<Integer>(k, 1, inputs, null);
                    builder.pattern(pattern);
                    // Duplicate registrations must not count one material's readiness twice.
                    if (random.nextInt(20) == 0) builder.pattern(pattern);
                }
            }
            var graph = builder.build();
            for (boolean stockSeeded : new boolean[] {false, true}) {
                assertEquals(reference(graph, 0, distance, stockSeeded),
                        ProducibleRouteOrder.rank(graph, 0, distance, stockSeeded, (p, in) -> true),
                        "sample=" + sample + " stockSeeded=" + stockSeeded);
            }
        }
    }

    @Test void repeatedInputSlotsAndDuplicateRecipesNeedEveryDistinctInput() {
        var join = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("A", 1),
                        CraftInput.of("B", 1)), null);
        var graph = CraftGraph.<String>builder().pattern(join).pattern(join)
                .pattern("B", 1, List.of(CraftInput.of("C", 1)))
                .pattern("C", 1, List.of(CraftInput.of("B", 1)))
                .stock("A", 1).build();
        assertEquals(Map.of("A", 0), ProducibleRouteOrder.rank(graph, "T", Map.of(), true, (p, in) -> true));
    }

    @Test void ignoredDependenciesDoNotCreateAReadinessRequirement() {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("T", 1)))
                .build();
        assertEquals(Map.of("T", 0), ProducibleRouteOrder.rank(graph, "T", Map.of(), true, (p, in) -> false));
        var ranked = ProducibleRouteOrder.rankWithMembership(graph, "T", Map.of(), true, (p, in) -> false);
        assertEquals(Map.of("T", 0), ranked.ordinals());
        assertEquals(Map.of("T", Set.of("T")), ranked.cycleMembers());
    }

    @Test void sharedStructuralIndexKeepsRawCyclesWhileFilteringReadinessSlots() {
        var random = new Random(2026093071L);
        BiPredicate<CraftPattern<Integer>, CraftInput<Integer>> dependency = (p, in) -> !in.returned();
        for (int sample = 0; sample < 1_000; sample++) {
            int n = 2 + random.nextInt(18);
            var builder = CraftGraph.<Integer>builder();
            var distances = new HashMap<Integer, Integer>();
            for (int k = 0; k < n; k++) {
                if (random.nextBoolean()) builder.stock(k, 1);
                distances.put(k, random.nextInt(n));
                int routes = random.nextInt(5);
                for (int p = 0; p < routes; p++) {
                    var inputs = new ArrayList<CraftInput<Integer>>();
                    int slots = random.nextInt(5);
                    for (int i = 0; i < slots; i++) {
                        int input = random.nextInt(n);
                        inputs.add(random.nextBoolean() ? CraftInput.returned(input, 1) : CraftInput.of(input, 1));
                    }
                    var pattern = new CraftPattern<Integer>(k, 1, inputs, null);
                    builder.pattern(pattern);
                    if (random.nextInt(5) == 0) builder.pattern(pattern);
                }
            }
            var graph = builder.build();
            for (boolean seeded : new boolean[] {false, true}) {
                var ranked = ProducibleRouteOrder.rankWithMembership(graph, 0, distances, seeded, dependency);
                assertEquals(reference(graph, 0, distances, seeded, dependency), ranked.ordinals(), "sample=" + sample);
                var referenceCycles = CycleAnalysis.analyze(graph, 0);
                var expectedCycles = new HashMap<Integer, Set<Integer>>();
                for (int key = 0; key < n; key++) {
                    var members = referenceCycles.membersOf(key);
                    if (!members.isEmpty()) expectedCycles.put(key, members);
                }
                assertEquals(expectedCycles, ranked.cycleMembers(), "sample=" + sample);
            }
        }
    }

    @Test void preprocessingPropagatesCancellation() {
        var signal = new PlanningExitException("stop rank");
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void checkpoint() { throw signal; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) { }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(signal, assertThrows(PlanningExitException.class, () -> ProducibleRouteOrder.rank(
                    CraftGraph.<String>builder().build(), "T", Map.of(), true, (p, in) -> true)));
        }
    }

    private record Label(int key, int tier, int level, int distance, int stable) {}

    /** Deliberately quadratic reference: rescan every recipe after selecting each next key. */
    private static Map<Integer, Integer> reference(CraftGraph<Integer> graph, int target,
            Map<Integer, Integer> distance, boolean stockSeeded) {
        return reference(graph, target, distance, stockSeeded, (p, in) -> true);
    }

    private static Map<Integer, Integer> reference(CraftGraph<Integer> graph, int target,
            Map<Integer, Integer> distance, boolean stockSeeded,
            BiPredicate<CraftPattern<Integer>, CraftInput<Integer>> dependency) {
        var keys = new ArrayList<Integer>();
        var stable = new HashMap<Integer, Integer>();
        keys.add(target); stable.put(target, 0);
        for (int at = 0; at < keys.size(); at++) {
            for (var pattern : graph.patternsFor(keys.get(at))) for (var input : pattern.inputs()) {
                if (!stable.containsKey(input.key())) {
                    stable.put(input.key(), keys.size()); keys.add(input.key());
                }
            }
        }
        var chosen = new HashMap<Integer, Label>();
        var result = new LinkedHashMap<Integer, Integer>();
        var order = Comparator.comparingInt(Label::tier).thenComparingInt(Label::level)
                .thenComparing(Comparator.comparingInt(Label::distance).reversed())
                .thenComparingInt(Label::stable);
        while (true) {
            var choices = new ArrayList<Label>();
            for (int key : keys) {
                if (chosen.containsKey(key)) continue;
                var patterns = graph.patternsFor(key);
                if (patterns.isEmpty()) choices.add(new Label(key,
                        stockSeeded && graph.stock(key) > 0 ? 0 : 2, 0,
                        distance.getOrDefault(key, 0), stable.get(key)));
                else if (stockSeeded && graph.stock(key) > 0) choices.add(new Label(key, 1, 0,
                        distance.getOrDefault(key, 0), stable.get(key)));
                for (var pattern : patterns) {
                    int tier = 0, level = 0;
                    boolean available = true;
                    for (var input : pattern.inputs()) {
                        if (!dependency.test(pattern, input)) continue;
                        var label = chosen.get(input.key());
                        if (label == null) { available = false; break; }
                        if (label.tier > tier || label.tier == tier && label.level > level) {
                            tier = label.tier; level = label.level;
                        }
                    }
                    if (available) choices.add(new Label(key, tier, level + 1,
                            distance.getOrDefault(key, 0), stable.get(key)));
                }
            }
            if (choices.isEmpty()) return result;
            var next = choices.stream().min(order).orElseThrow();
            chosen.put(next.key, next); result.put(next.key, result.size());
        }
    }
}
