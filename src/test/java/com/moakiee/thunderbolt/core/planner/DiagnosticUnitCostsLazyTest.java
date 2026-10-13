package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticUnitCostsLazyTest {
    @Test
    void fundedPassesAndSingleMissingRoutesDoNotSweepDiagnosticCosts() throws Exception {
        var first = ordinary("target", "raw-a", 2);
        var second = ordinary("target", "raw-b", 1);
        var stocked = graph(List.of(first, second), Map.of("target", 7L));
        var funded = graph(List.of(first, second), Map.of("raw-a", 6L));
        var single = graph(List.of(first), Map.of());
        for (var graph : List.of(stocked, funded, single)) {
            var order = new CountingOrder("target", "raw-a", "raw-b");
            var fixture = new Fixture(graph, order);
            var result = fixture.pass(order, "target", 3);
            assertEquals(0, order.indexedReads,
                    "an ordinary forward pass must not request the reverse diagnostic sweep");
            if (graph == single) {
                assertEquals(Map.of("raw-a", 6L), result.missing());
                assertEquals(Map.of(first, 3L), result.fired());
            } else {
                assertTrue(result.missing().isEmpty());
            }
        }
    }

    @Test
    void twoUnresolvedOutputsShareOneSweepButANewPassBuildsItsOwnCosts() throws Exception {
        var target = new CraftPattern<>("target", 1,
                List.of(CraftInput.of("x", 1), CraftInput.of("y", 1)), null);
        var expensiveX = ordinary("x", "raw-a", 9);
        var cheapX = ordinary("x", "raw-b", 1);
        var expensiveY = ordinary("y", "raw-c", 8);
        var cheapY = ordinary("y", "raw-d", 1);
        var graph = graph(List.of(target, expensiveX, cheapX, expensiveY, cheapY), Map.of());
        var order = new CountingOrder("target", "x", "y", "raw-a", "raw-b", "raw-c", "raw-d");
        var fixture = new Fixture(graph, order);

        for (int pass = 1; pass <= 2; pass++) {
            var result = fixture.pass(order, "target", pass);
            assertEquals(order.size() * pass, order.indexedReads);
            assertEquals(Map.of("raw-b", (long) pass, "raw-d", (long) pass), result.missing());
            assertEquals(Map.of(target, (long) pass, cheapX, (long) pass, cheapY, (long) pass),
                    result.fired());
        }
    }

    @Test
    void anEmptyCostResultIsStillMemoizedOnlyInsideItsHolder() throws Exception {
        var free = new CraftPattern<String>("free", 1, List.of(), null);
        var order = new CountingOrder("free");
        var fixture = new Fixture(graph(List.of(free), Map.of()), order);
        var costs = fixture.lazyCosts(order);
        var first = costs.get();
        assertTrue(first.isEmpty());
        assertSame(first, costs.get());
        assertEquals(1, order.indexedReads);
        assertTrue(fixture.lazyCosts(order).get().isEmpty());
        assertEquals(2, order.indexedReads);
    }

    @Test
    void sharedInputDominanceAndSingletonsNeverAskForRawCosts() throws Exception {
        var light = ordinary("target", "shared", 1);
        var heavy = ordinary("target", "shared", 2);
        var fixture = new Fixture(graph(List.of(light, heavy), Map.of()), List.of("target", "shared"));
        Supplier<Map<String, Long>> unexpected = () -> {
            throw new AssertionError("shared-input dominance already decides this route");
        };
        assertSame(light, fixture.route(3, List.of(light), unexpected));
        assertSame(light, fixture.route(3, List.of(light, heavy), unexpected));
        assertSame(light, fixture.route(3, List.of(heavy, light), unexpected));
    }

    @Test
    void lazyCostsPreserveStatefulUnknownOverflowAndStableTieSemantics() throws Exception {
        var fixture = new Fixture(CraftGraph.<String>builder().build(), List.of());
        var random = new Random(2026100321L);
        long[] amounts = {1, 2, 7, Sat.SAT - 1, Sat.SAT, Long.MAX_VALUE};
        long[] unitCosts = {0, 1, 3, Sat.SAT - 1, Sat.SAT};
        for (int sample = 0; sample < 768; sample++) {
            var patterns = new ArrayList<CraftPattern<String>>();
            var costs = new HashMap<String, Long>();
            costs.put("unrelated-raw", 1L);
            for (int route = 0, count = 2 + sample % 7; route < count; route++) {
                // Keys remain disjoint across routes so this oracle isolates the raw-cost tie-break.
                String key = "route-" + route;
                long amount = amounts[random.nextInt(amounts.length)];
                int kind = (sample + route) % 8;
                var input = switch (kind) {
                    case 1 -> CraftInput.returned(key, amount);
                    case 2 -> CraftInput.finiteUse(key, amount, 3);
                    case 3 -> CraftInput.consumedReturning(key, amount, "remainder-" + route);
                    case 4 -> CraftInput.returnedFrom(key, amount, new ReusableStockSource("host", key));
                    default -> CraftInput.of(key, amount);
                };
                var inputs = new ArrayList<CraftInput<String>>();
                inputs.add(input);
                if (sample % 5 == 0) inputs.add(CraftInput.of(key, 1));
                var byproducts = kind == 5 ? List.of(CraftOutput.of("side-" + route, 1))
                        : List.<CraftOutput<String>>of();
                patterns.add(new CraftPattern<>("target", amounts[random.nextInt(amounts.length)],
                        inputs, byproducts, null));
                if (kind != 6) costs.put(key, unitCosts[random.nextInt(unitCosts.length)]);
            }
            if (sample % 29 == 0) costs.clear();
            long demand = amounts[random.nextInt(amounts.length)];
            var expected = patterns.get(0);
            for (var pattern : patterns) {
                if (exactSaturatedCost(pattern, demand, costs) < exactSaturatedCost(expected, demand, costs))
                    expected = pattern;
            }
            assertSame(expected, fixture.route(demand, patterns, () -> costs), "sample " + sample);
        }
    }

    @Test
    void recursiveMissingRouteKeepsItsExistingOrderWithoutEnablingTheRawTieBreak() throws Exception {
        var expensive = ordinary("target", "raw-a", 9);
        var cheap = ordinary("target", "raw-b", 1);
        var fixture = new Fixture(graph(List.of(expensive, cheap), Map.of()),
                List.of("target", "raw-a", "raw-b"));
        var recursiveRoute = CraftPlannerV2.class.getDeclaredMethod(
                "diagnosticRoute", long.class, List.class, Map.class);
        recursiveRoute.setAccessible(true);
        assertSame(expensive, recursiveRoute.invoke(fixture.planner, 1L,
                List.of(expensive, cheap), Map.of()));
        assertSame(cheap, fixture.route(1, List.of(expensive, cheap),
                () -> Map.of("raw-a", 1L, "raw-b", 1L)));
    }

    private static long exactSaturatedCost(
            CraftPattern<String> pattern, long demand, Map<String, Long> costs) {
        if (costs.isEmpty() || !pattern.byproducts().isEmpty()) return Sat.SAT;
        BigInteger limit = BigInteger.valueOf(Sat.SAT);
        BigInteger firings = demand >= Sat.SAT ? limit
                : BigInteger.valueOf(demand).add(BigInteger.valueOf(pattern.outputAmount() - 1))
                        .divide(BigInteger.valueOf(pattern.outputAmount()));
        BigInteger total = BigInteger.ZERO;
        for (var input : pattern.inputs()) {
            Long cost = costs.get(input.key());
            if (input.returned() || input.remainder() != null || cost == null) return Sat.SAT;
            BigInteger units = BigInteger.valueOf(input.amount()).multiply(firings).min(limit);
            total = total.add(units.multiply(BigInteger.valueOf(cost))).min(limit);
        }
        return total.longValueExact();
    }

    private static CraftPattern<String> ordinary(String output, String input, long amount) {
        return new CraftPattern<>(output, 1, List.of(CraftInput.of(input, amount)), null);
    }

    private static CraftGraph<String> graph(List<CraftPattern<String>> patterns, Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder();
        patterns.forEach(builder::pattern);
        stock.forEach(builder::stock);
        return builder.build();
    }

    /** ArrayList's forward iterator does not call get; the diagnostic reverse sweep does. */
    private static final class CountingOrder extends ArrayList<String> {
        private int indexedReads;

        private CountingOrder(String... keys) {
            super(List.of(keys));
        }

        @Override
        public String get(int index) {
            indexedReads++;
            return super.get(index);
        }
    }

    private record Observed(Map<CraftPattern<String>, Long> fired, Map<String, Long> missing) {}

    private static final class Fixture {
        private final Object planner;
        private final Method linearPass;
        private final Method diagnosticRoute;

        private Fixture(CraftGraph<String> graph, List<String> keys) throws Exception {
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
            var patterns = this.<Map<String, List<CraftPattern<String>>>>field("patternsByOutput");
            var capacity = new HashMap<String, Long>();
            for (String key : keys) {
                patterns.put(key, graph.patternsFor(key));
                capacity.put(key, graph.stock(key));
            }
            Field capacityField = plannerClass.getDeclaredField("capacity");
            capacityField.setAccessible(true);
            capacityField.set(planner, capacity);
            linearPass = plannerClass.getDeclaredMethod("linearPassState",
                    List.class, Object.class, long.class, Map.class);
            linearPass.setAccessible(true);
            diagnosticRoute = plannerClass.getDeclaredMethod("diagnosticRoute",
                    long.class, List.class, Map.class, Supplier.class);
            diagnosticRoute.setAccessible(true);
        }

        @SuppressWarnings("unchecked")
        private Observed pass(List<String> order, String target, long amount) throws Exception {
            Object state = linearPass.invoke(planner, order, target, amount, Map.of());
            Method fired = state.getClass().getDeclaredMethod("fired");
            Method missing = state.getClass().getDeclaredMethod("miss");
            fired.setAccessible(true);
            missing.setAccessible(true);
            return new Observed((Map<CraftPattern<String>, Long>) fired.invoke(state),
                    (Map<String, Long>) missing.invoke(state));
        }

        @SuppressWarnings("unchecked")
        private CraftPattern<String> route(long demand, List<CraftPattern<String>> patterns,
                Supplier<Map<String, Long>> costs) throws Exception {
            return (CraftPattern<String>) diagnosticRoute.invoke(planner, demand, patterns, Map.of(), costs);
        }

        @SuppressWarnings("unchecked")
        private Supplier<Map<String, Long>> lazyCosts(List<String> order) throws Exception {
            Class<?> holder = Class.forName(CraftPlannerV2.class.getName() + "$LazyDiagnosticUnitCosts");
            var constructor = holder.getDeclaredConstructor(CraftPlannerV2.class, List.class);
            constructor.setAccessible(true);
            return (Supplier<Map<String, Long>>) constructor.newInstance(planner, order);
        }

        @SuppressWarnings("unchecked")
        private <T> T field(String name) throws Exception {
            Field field = CraftPlannerV2.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(planner);
        }
    }
}
