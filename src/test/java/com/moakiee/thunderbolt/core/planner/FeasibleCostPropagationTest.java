package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** Cost passes must remain independent after work-limited cycles and differently sized regions. */
class FeasibleCostPropagationTest {
    @Test
    void cachedFullAdjacencyAndScratchPreserveLocalRegionsAndStockChanges() throws Exception {
        var repeated = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1),
                CraftInput.of("A", 2)), "repeated");
        var graph = CraftGraph.<String>builder().stock("raw", 1000)
                .pattern(repeated).pattern(repeated)
                .pattern("T", 2, List.of(CraftInput.of("tag", 1)))
                .pattern(CraftPattern.tagConversion("raw", "tag", "tag"))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)))
                .pattern("A", 1, List.of()).build();
        var reused = new Harness(graph);
        int[] full = reused.fullPatterns();
        Object retained = reused.propagate(full, true);
        int[] consumers = ((int[]) field(retained, "consumer")).clone();
        double[] costs = ((double[]) field(retained, "cost")).clone();
        for (boolean available : List.of(false, true, false)) {
            set(reused.search, "availableStock", available);
            for (int[] region : List.of(new int[0], new int[] {0}, full, new int[] {1, 0}, full)) {
                var fresh = new Harness(graph);
                set(fresh.search, "availableStock", available);
                assertPropagationEquals(fresh.propagate(region.clone(), true), reused.propagate(region, true));
            }
        }
        assertArrayEquals(consumers, (int[]) field(retained, "consumer"));
        assertArrayEquals(costs, (double[]) field(retained, "cost"));
        assertSame(field(retained, "consumer"), field(reused.propagate(full, true), "consumer"));
        int raw = reused.keys.indexOf("raw");
        ((long[]) field(reused.search, "limits"))[raw] = 0;
        set(reused.search, "limited", new int[0]);
        var fresh = new Harness(graph);
        ((long[]) field(fresh.search, "limits"))[raw] = 0;
        set(fresh.search, "limited", new int[0]);
        assertPropagationEquals(fresh.propagate(full.clone(), true), reused.propagate(full, true));
    }

    @Test
    void canceledFullAdjacencyIsNotPublishedAndCanBeRebuilt() throws Exception {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("raw", 1))).build();
        var harness = new Harness(graph);
        try {
            Thread.currentThread().interrupt();
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class, harness::fullPatterns);
            assertInstanceOf(java.util.concurrent.CancellationException.class, error.getCause());
        } finally {
            Thread.interrupted();
        }
        assertNull(field(harness.index, "fullTopology"));
        assertArrayEquals(harness.patterns(), harness.fullPatterns());
        assertSame(harness.fullPatterns(), harness.fullPatterns());
    }

    @Test
    void cancellationDuringAdjacencyFillDiscardsPartialArrays() throws Exception {
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("raw", 1))).build();
        var harness = new Harness(graph);
        var canceled = new java.util.concurrent.CancellationException("cancel adjacency fill");
        int[] checks = {0};
        var context = new com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() { if (++checks[0] == 3) throw canceled; }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class, harness::fullPatterns);
            assertSame(canceled, error.getCause());
        }
        assertNull(field(harness.index, "fullTopology"));
        int[] full = harness.fullPatterns();
        var fresh = new Harness(graph);
        assertPropagationEquals(fresh.propagate(full.clone(), true), harness.propagate(full, true));
    }

    @Test
    void repeatedCostPassesPreserveCostsChoicesAndBoundsAcrossRegionSizes() throws Exception {
        var builder = CraftGraph.<String>builder().stock("raw", 1000).stock("A", 1000)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("A", 1001, List.of(CraftInput.of("B", 1000)))
                .pattern("B", 1001, List.of(CraftInput.of("A", 1000)))
                .pattern("A", 1, List.of(CraftInput.of("raw", 1)));
        for (int i = 0; i < 600; i++)
            builder.pattern("T", 1, List.of(CraftInput.of("absent" + i, 1)));
        var graph = builder.build();
        var reused = new Harness(graph);
        int[] all = reused.patterns();
        int[] small = Arrays.stream(all).filter(p -> !reused.output(p).equals("T")
                || p == 0).toArray();
        Object retained = reused.propagate(all, true);
        double[] retainedCosts = ((double[]) field(retained, "cost")).clone();
        int[] retainedChoices = ((int[]) field(retained, "argmin")).clone();

        for (boolean fullStock : List.of(false, true, false)) {
            set(reused.search, "availableStock", fullStock);
            for (int[] region : List.of(small, new int[0], all, small, all)) {
                var fresh = new Harness(graph);
                set(fresh.search, "availableStock", fullStock);
                assertPropagationEquals(fresh.propagate(region, true), reused.propagate(region, true));
                assertPropagationEquals(fresh.propagate(region, false), reused.propagate(region, false));
            }
        }
        assertArrayEquals(retainedCosts, (double[]) field(retained, "cost"));
        assertArrayEquals(retainedChoices, (int[]) field(retained, "argmin"));
    }

    @Test
    void cyclicCheapestPolicyFallsBackToTheOriginalDiscoveryOrder() throws Exception {
        var direct = CraftPattern.weighted("A", 1, List.of(CraftInput.of("raw", 1)),
                List.of(), "direct", 2);
        var target = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1)), "target");
        var graph = CraftGraph.<String>builder().stock("raw", 1000)
                .pattern(target)
                .pattern("A", 1001, List.of(CraftInput.of("B", 1000)))
                .pattern("B", 1001, List.of(CraftInput.of("A", 1000)))
                .pattern(direct).build();
        var harness = new Harness(graph);
        Object prop = harness.propagate(harness.fullPatterns(), true);
        set(harness.search, "global", prop);
        Method policy = harness.search.getClass().getDeclaredMethod("policy", double[].class);
        policy.setAccessible(true);
        Object result = policy.invoke(harness.search, (Object) new double[harness.keys.size()]);
        assertNotNull(result);
        // The cheaper labels choose A <-> B. The fallback must instead reach A from raw first.
        assertNull(field(result, "order"));
        assertEquals(java.util.Map.of("T", List.of(target), "A", List.of(direct), "raw", List.of()),
                field(result, "selected"));
    }

    @Test
    void aWorkLimitedRelaxationDoesNotLeaveQueuedFlagsInTheNextPass() throws Exception {
        var graph = CraftGraph.<String>builder().stock("A", 1000)
                .pattern("T", 1, List.of(CraftInput.of("A", 1)))
                .pattern("A", 1001, List.of(CraftInput.of("B", 1000)))
                .pattern("B", 1001, List.of(CraftInput.of("A", 1000))).build();
        var reused = new Harness(graph);
        Object prop = reused.propagate(reused.patterns(), true);
        Method relax = reused.search.getClass().getDeclaredMethod("relax", prop.getClass(),
                double.class, double[].class, int[].class);
        relax.setAccessible(true);
        double[] cost = new double[reused.keys.size()];
        Arrays.fill(cost, 1.0);
        assertEquals(false, relax.invoke(reused.search, prop, 0.0, cost, null),
                "a slowly growing cycle must leave work queued at the unchanged iteration cap");
        var fresh = new Harness(graph);
        assertPropagationEquals(fresh.propagate(fresh.patterns(), true),
                reused.propagate(reused.patterns(), true));
    }

    private static void assertPropagationEquals(Object expected, Object actual) throws Exception {
        for (String name : List.of("patterns", "consumerStart", "consumer", "order", "rank", "first", "argmin", "freeArgmin"))
            assertArrayEquals((int[]) field(expected, name), (int[]) field(actual, name), name);
        for (String name : List.of("fired", "avail", "side", "produced"))
            assertArrayEquals((boolean[]) field(expected, name), (boolean[]) field(actual, name), name);
        assertArrayEquals((double[]) field(expected, "cost"), (double[]) field(actual, "cost"));
        for (String name : List.of("firedCount", "targetAvailable", "execBound"))
            assertEquals(field(expected, name), field(actual, name), name);
    }

    private static Field member(Object value, String name) throws Exception {
        Field f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object field(Object value, String name) throws Exception { return member(value, name).get(value); }
    private static void set(Object value, String name, Object field) throws Exception { member(value, name).set(value, field); }

    private static final class Harness {
        final Object search;
        final Object index;
        final List<?> keys;
        final Method propagate;

        Harness(CraftGraph<String> graph) throws Exception {
            var cache = new FeasibleConsumptionOptimizer.IndexCache<String>();
            index = cache.get(graph, "T");
            keys = (List<?>) field(index, "keys");
            var type = Class.forName(FeasibleConsumptionOptimizer.class.getName() + "$Search");
            var constructor = type.getDeclaredConstructor(CraftGraph.class, Object.class, long.class,
                    CraftPlan.class, int.class, Function.class, FeasibleConsumptionOptimizer.IndexCache.class, IntPredicate.class);
            constructor.setAccessible(true);
            search = constructor.newInstance(graph, "T", 100L, null, 32,
                    (Function<CraftGraph<String>, CraftPlan<String>>) ignored -> null, cache, (IntPredicate) work -> true);
            set(search, "index", index);
            long[] limits = new long[keys.size()];
            var limited = new java.util.ArrayList<Integer>();
            for (int k = 0; k < keys.size(); k++) {
                limits[k] = graph.stock((String) keys.get(k));
                if (limits[k] > 0) limited.add(k);
            }
            set(search, "limits", limits);
            set(search, "limited", limited.stream().mapToInt(Integer::intValue).toArray());
            propagate = type.getDeclaredMethod("propagate", int[].class, boolean.class);
            propagate.setAccessible(true);
        }

        Object propagate(int[] patterns, boolean choices) throws Exception { return propagate.invoke(search, patterns, choices); }
        int[] fullPatterns() throws Exception {
            Method full = index.getClass().getDeclaredMethod("fullTopology");
            full.setAccessible(true);
            return (int[]) field(full.invoke(index), "patterns");
        }
        int[] patterns() throws Exception { return java.util.stream.IntStream.range(0, ((List<?>) field(index, "patterns")).size()).toArray(); }
        Object output(int p) { try { return keys.get(((int[]) field(index, "out"))[p]); } catch (Exception e) { throw new AssertionError(e); } }
    }
}
