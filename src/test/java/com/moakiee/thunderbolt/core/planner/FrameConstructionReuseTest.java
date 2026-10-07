package com.moakiee.thunderbolt.core.crafting.planner;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameConstructionReuseTest {
    private static final String OUTPUT = "output";

    @Test
    void anEmptyLeafDoesNotAddChildrenOrErasePreviouslyDiscoveredItems() throws Exception {
        var fixture = new Fixture(CraftGraph.<String>builder().build());
        var items = new LinkedHashSet<>(List.of("earlier", OUTPUT));
        var observed = fixture.frame(new HashMap<>(Map.of(OUTPUT, 1)), items);

        assertTrue(observed.retained().isEmpty());
        assertTrue(observed.children().isEmpty());
        assertEquals(List.of("earlier", OUTPUT), List.copyOf(items));
        assertTrue(fixture.<Set<String>>field("cutOutputs").isEmpty());
    }

    @Test
    void allRetainedRecipesReuseTheImmutableListWithoutDeduplicatingRegistrations() throws Exception {
        var repeated = ordinary("first", "second", "first");
        var other = ordinary("third", "second");
        var graph = graph(List.of(repeated, other, repeated));
        var fixture = new Fixture(graph);
        var observed = fixture.frame(new HashMap<>(Map.of(OUTPUT, 1)), new LinkedHashSet<>());

        assertSame(graph.patternsFor(OUTPUT), observed.retained());
        assertIdentities(List.of(repeated, other, repeated), observed.retained());
        assertEquals(List.of("first", "second", "third"), observed.children());
        assertThrows(UnsupportedOperationException.class, () -> observed.retained().add(other));
        assertIdentities(List.of(repeated, other, repeated), graph.patternsFor(OUTPUT));
    }

    @Test
    void childOrderSurvivesWideFramesDuplicateKeysAndInterleavedCuts() throws Exception {
        for (int count : new int[] {7, 8, 9, 32, 256}) {
            var keys = new ArrayList<String>();
            for (int i = 0; i < count; i++) keys.add("child-" + i);
            var first = ordinary(keys.toArray(String[]::new));
            var reversed = new ArrayList<String>();
            for (int i = count - 1; i >= 0; i--) reversed.add(new String(keys.get(i)));
            reversed.add("last");
            var second = ordinary(reversed.toArray(String[]::new));
            var rejected = ordinary("blocked", "excluded");
            var fixture = new Fixture(graph(List.of(first, rejected, second, first)));
            var colors = new HashMap<>(Map.of(OUTPUT, 1, "blocked", 1));
            var items = new LinkedHashSet<>(List.of("earlier", OUTPUT));
            var observed = fixture.frame(colors, items);

            var expected = new ArrayList<>(keys);
            expected.add("last");
            assertEquals(expected, observed.children(), "child count " + count);
            assertIdentities(List.of(first, second, first), observed.retained());
            var expectedItems = new ArrayList<>(List.of("earlier", OUTPUT));
            expectedItems.addAll(expected);
            assertEquals(expectedItems, List.copyOf(items));
            assertEquals(Set.of(OUTPUT), fixture.<Set<String>>field("cutOutputs"));
        }
    }

    @Test
    void everyCutKindPreservesDuplicateRecipesBeforeAndAfterTheFirstCut() throws Exception {
        var repeated = ordinary("first", "shared");
        var other = ordinary("shared", "last");
        var rejected = ordinary("blocked", "never-a-child");
        var survivors = List.of(repeated, other, repeated, other, repeated);
        for (CutKind kind : CutKind.values()) {
            for (int cutPosition : new int[] {0, 2, survivors.size()}) {
                var registered = new ArrayList<>(survivors);
                registered.add(cutPosition, rejected);
                // A second occurrence of the same rejected object must not shift the retained prefix.
                registered.add(rejected);
                var graph = graph(registered);
                var fixture = new Fixture(graph);
                var colors = new HashMap<>(Map.of(OUTPUT, 1));
                fixture.configure(kind, colors);
                var before = Map.copyOf(colors);
                var items = new LinkedHashSet<>(List.of(OUTPUT));
                var observed = fixture.frame(colors, items);

                assertIdentities(survivors, observed.retained());
                assertIdentities(registered, graph.patternsFor(OUTPUT));
                assertEquals(List.of("first", "shared", "last"), observed.children());
                assertEquals(List.of(OUTPUT, "first", "shared", "last"), List.copyOf(items));
                assertEquals(Set.of(OUTPUT), fixture.<Set<String>>field("cutOutputs"));
                assertEquals(before, colors, kind + ": frame construction must not advance DFS colors");
            }
        }
    }

    @Test
    void aGrayBackEdgeDoesNotSkipLaterReturnedInputOrByproductBookkeeping() throws Exception {
        var rejected = new CraftPattern<>(OUTPUT, 1,
                List.of(CraftInput.of("ancestor", 1), CraftInput.returned("catalyst", 1),
                        CraftInput.returned(OUTPUT, 1)),
                List.of(CraftOutput.of("scrap", 1)), null);
        var fixture = new Fixture(graph(List.of(rejected)));
        var items = new LinkedHashSet<>(List.of(OUTPUT));
        var observed = fixture.frame(new HashMap<>(Map.of(OUTPUT, 1, "ancestor", 1)), items);

        assertTrue(observed.retained().isEmpty());
        assertTrue(observed.children().isEmpty());
        assertEquals(List.of(OUTPUT), List.copyOf(items));
        assertTrue(fixture.<Boolean>field("requiresSeedOrderedPlanning"));
        assertEquals(Set.of("catalyst"), fixture.<Set<String>>field("ordinaryReturnedSeedKeys"));
        assertEquals(Set.of("scrap"), fixture.<Set<String>>field("reachableByproductKeys"));
        assertEquals(Set.of(OUTPUT), fixture.<Set<String>>field("cutOutputs"));
    }

    @Test
    void ordinaryFramesMatchAnIndependentFilterAndFirstOccurrenceOracle() throws Exception {
        var random = new Random(2026100317L);
        for (int sample = 0; sample < 256; sample++) {
            var pool = new ArrayList<CraftPattern<String>>();
            for (int p = 0; p < 6; p++) {
                var inputs = new ArrayList<CraftInput<String>>();
                for (int i = 0, count = random.nextInt(6); i < count; i++)
                    inputs.add(CraftInput.of("item-" + random.nextInt(7), 1));
                pool.add(new CraftPattern<>(OUTPUT, 1, inputs, "recipe-" + p));
            }
            var registered = new ArrayList<CraftPattern<String>>();
            for (int p = 0; p < sample % 19; p++) registered.add(pool.get(random.nextInt(pool.size())));
            var colors = new HashMap<>(Map.of(OUTPUT, 1));
            for (int key = 0; key < 7; key++) {
                int color = random.nextInt(3);
                if (color != 0) colors.put("item-" + key, color);
            }
            var expected = registered.stream()
                    .filter(pattern -> pattern.inputs().stream()
                            .noneMatch(input -> Integer.valueOf(1).equals(colors.get(input.key()))))
                    .toList();
            var expectedChildren = expected.stream().flatMap(pattern -> pattern.inputs().stream())
                    .map(CraftInput::key).distinct().toList();
            var graph = graph(registered);
            var fixture = new Fixture(graph);
            var items = new LinkedHashSet<>(List.of(OUTPUT));
            var observed = fixture.frame(colors, items);

            assertIdentities(expected, observed.retained());
            assertEquals(expectedChildren, observed.children(), "children for sample " + sample);
            var expectedItems = new ArrayList<>(List.of(OUTPUT));
            expectedItems.addAll(expectedChildren);
            assertEquals(expectedItems, List.copyOf(items));
            assertEquals(expected.size() < registered.size(),
                    fixture.<Set<String>>field("cutOutputs").contains(OUTPUT));
            assertFalse(fixture.<Boolean>field("requiresSeedOrderedPlanning"));
            assertIdentities(registered, graph.patternsFor(OUTPUT));
        }
    }

    private static CraftPattern<String> ordinary(String... keys) {
        return new CraftPattern<>(OUTPUT, 1, Arrays.stream(keys).map(key -> CraftInput.of(key, 1)).toList(), null);
    }

    private static CraftGraph<String> graph(List<CraftPattern<String>> patterns) {
        var builder = CraftGraph.<String>builder();
        patterns.forEach(builder::pattern);
        return builder.build();
    }

    private static void assertIdentities(List<CraftPattern<String>> expected, List<CraftPattern<String>> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i), "recipe " + i);
    }

    private enum CutKind { GRAY_BACK_EDGE, EXPLICIT_MEMBER, EXPLICIT_RANK, PRODUCIBILITY_RANK }

    private record Observed(List<CraftPattern<String>> retained, List<String> children) {}

    private static final class Fixture {
        private final Object planner;
        private final Method frameFor;

        private Fixture(CraftGraph<String> graph) throws Exception {
            Class<?> plannerClass = CraftPlannerV2.class;
            Class<?> diagnosticsClass = Class.forName(plannerClass.getName() + "$DiagnosticsCollector");
            var diagnosticsCtor = diagnosticsClass.getDeclaredConstructor(
                    int.class, int.class, CraftPlannerV2.PlanningSession.class);
            diagnosticsCtor.setAccessible(true);
            var diagnostics = diagnosticsCtor.newInstance(1, 1, new CraftPlannerV2.PlanningSession<String>());
            var plannerCtor = Arrays.stream(plannerClass.getDeclaredConstructors())
                    .filter(ctor -> ctor.getParameterTypes()[0] == CraftGraph.class).findFirst().orElseThrow();
            plannerCtor.setAccessible(true);
            planner = plannerCtor.newInstance(graph, 1, null, null, diagnostics);
            frameFor = plannerClass.getDeclaredMethod("frameFor", Object.class, Map.class, Set.class);
            frameFor.setAccessible(true);
        }

        private Observed frame(Map<String, Integer> colors, Set<String> items) throws Exception {
            var frame = frameFor.invoke(planner, OUTPUT, colors, items);
            Field children = frame.getClass().getDeclaredField("children");
            children.setAccessible(true);
            @SuppressWarnings("unchecked")
            var childKeys = (List<String>) children.get(frame);
            return new Observed(this.<Map<String, List<CraftPattern<String>>>>field("patternsByOutput").get(OUTPUT),
                    childKeys);
        }

        private void configure(CutKind kind, Map<String, Integer> colors) throws Exception {
            switch (kind) {
                case GRAY_BACK_EDGE -> colors.put("blocked", 1);
                case EXPLICIT_MEMBER -> this.<Map<String, Set<String>>>field("explicitCutMembers")
                        .put(OUTPUT, Set.of("blocked"));
                case EXPLICIT_RANK -> {
                    this.<Map<String, Set<String>>>field("explicitRankMembers").put(OUTPUT, Set.of("blocked"));
                    var ranks = this.<Map<String, Integer>>field("explicitCutRanks");
                    ranks.put(OUTPUT, 0);
                    ranks.put("blocked", 1);
                }
                case PRODUCIBILITY_RANK -> {
                    setField("producibilityRanked", true);
                    setField("producibilityCycleMembers", Map.of(OUTPUT, Set.of(OUTPUT, "blocked")));
                    var ranks = this.<Map<String, Integer>>field("producibilityOrdinal");
                    ranks.put(OUTPUT, 0);
                    ranks.put("blocked", 1);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T field(String name) throws Exception {
            Field field = CraftPlannerV2.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(planner);
        }

        private void setField(String name, Object value) throws Exception {
            Field field = CraftPlannerV2.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(planner, value);
        }
    }
}
