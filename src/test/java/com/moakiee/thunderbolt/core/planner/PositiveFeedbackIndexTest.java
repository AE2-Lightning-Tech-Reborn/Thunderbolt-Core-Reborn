package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class PositiveFeedbackIndexTest {
    @Test
    void wideRecipesVisitSparseFeedbackEdgesWithoutAnInputByproductCrossProduct() throws Exception {
        long small = sparseFeedbackWork(128), large = sparseFeedbackWork(512);
        assertTrue(large <= small * 5, () -> "key lookups grew from " + small + " to " + large);
    }

    private static long sparseFeedbackWork(int width) throws Exception {
        var builder = CraftGraph.<CountingKey>builder();
        var inputs = new ArrayList<CraftInput<CountingKey>>();
        var sides = new ArrayList<CraftOutput<CountingKey>>();
        for (int i = 0; i < width; i++) {
            var input = new CountingKey(i);
            var side = new CountingKey(width + i);
            inputs.add(CraftInput.of(input, 1));
            sides.add(CraftOutput.of(side, 1));
            builder.pattern(input, 2, List.of(CraftInput.of(side, 1)));
        }
        var root = new CountingKey(width * 2);
        var consumer = new CraftPattern<>(root, 1, inputs, sides, null);
        var graph = builder.pattern(consumer).build();
        CountingKey.lookups = 0;
        var actual = suppressed(graph, Map.of(), root, false);
        long work = CountingKey.lookups;
        assertEquals(1, actual.size());
        assertEquals(width, actual.get(consumer).size());
        return work;
    }

    private record CountingKey(int id) {
        static long lookups;
        @Override public int hashCode() { lookups++; return id; }
    }

    @Test
    void indexedPairsMatchIndependentSaturatedRoundArithmetic() throws Exception {
        var random = new Random(2026101509L);
        long[] quantities = {1, 2, 7, Sat.SAT - 1, Sat.SAT, Long.MAX_VALUE};
        for (int sample = 0; sample < 256; sample++) {
            var builder = CraftGraph.<Integer>builder();
            var patterns = new ArrayList<CraftPattern<Integer>>();
            Map<Integer, List<CraftPattern<Integer>>> selected = new HashMap<>();
            for (int output = 0; output < 6; output++) {
                for (int route = 0; route < 3; route++) {
                    var inputs = new ArrayList<CraftInput<Integer>>();
                    for (int slot = 0; slot < 1 + random.nextInt(6); slot++) {
                        int key = random.nextInt(8);
                        long amount = quantities[random.nextInt(quantities.length)];
                        inputs.add(switch (random.nextInt(5)) {
                            case 0 -> CraftInput.returned(key, amount);
                            case 1 -> CraftInput.finiteUse(key, amount, 3);
                            case 2 -> CraftInput.consumedReturning(key, amount, random.nextInt(8));
                            default -> CraftInput.of(key, amount);
                        });
                    }
                    var sides = new ArrayList<CraftOutput<Integer>>();
                    for (int slot = 0; slot < random.nextInt(4); slot++)
                        sides.add(CraftOutput.of(random.nextInt(8), quantities[random.nextInt(quantities.length)]));
                    var pattern = new CraftPattern<>(output, quantities[random.nextInt(quantities.length)],
                            inputs, sides, "shared source");
                    patterns.add(pattern); builder.pattern(pattern);
                    if (random.nextBoolean()) selected.computeIfAbsent(output, ignored -> new ArrayList<>()).add(pattern);
                }
            }
            var rootInputs = new ArrayList<CraftInput<Integer>>();
            for (int key = 0; key < 8; key++) rootInputs.add(CraftInput.of(key, 1));
            var graph = builder.pattern(100, 1, rootInputs).build();
            for (boolean compiled : List.of(false, true)) {
                var actual = suppressed(graph, selected, 100, compiled);
                var expected = oracle(patterns, compiled ? selected : null, graph);
                String label = "sample=" + sample + ", compiled=" + compiled;
                assertEquals(expected.size(), actual.size(), label);
                expected.forEach((pattern, keys) -> assertEquals(keys, actual.get(pattern), label));
            }
        }
    }

    @Test
    void aRefillRemovedByTheDagCannotSuppressItsFormerReturn() throws Exception {
        var consumer = new CraftPattern<>("T", 1, List.of(CraftInput.of("A", 1)),
                List.of(CraftOutput.of("B", 1)), "consumer");
        var refill = new CraftPattern<>("A", 2, List.of(CraftInput.of("B", 1)), "refill");
        var graph = CraftGraph.<String>builder().pattern(consumer).pattern(refill).build();
        var selected = Map.of("T", List.of(consumer), "A", List.<CraftPattern<String>>of());
        assertEquals(Map.of(consumer, Set.of("B")), suppressed(graph, selected, "T", false));
        assertTrue(suppressed(graph, selected, "T", true).isEmpty());
    }

    @Test
    void buildingAnInputIndexPropagatesExternalCancellation() {
        var graph = CraftGraph.<String>builder().pattern("T", 1, List.of(CraftInput.of("A", 1)),
                List.of(CraftOutput.of("B", 1))).pattern("A", 2, List.of(CraftInput.of("B", 1))).build();
        var exit = new PlanningExitException("cancel refill indexing");
        var context = new PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void report(PlanningDiagnosticSnapshot snapshot) {}
            public void checkpoint() {
                if (StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        frame.getClassName().equals(CraftPlannerV2.class.getName())
                                && frame.getMethodName().equals("feedbackRefills")))) throw exit;
            }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> suppressed(graph, Map.of(), "T", false)));
        }
    }

    private static final BigInteger CAP = BigInteger.valueOf(Sat.SAT);

    private static <K> Map<CraftPattern<K>, Set<K>> oracle(List<CraftPattern<K>> consumers,
            Map<K, List<CraftPattern<K>>> selected, CraftGraph<K> graph) {
        var expected = new IdentityHashMap<CraftPattern<K>, Set<K>>();
        for (var consumer : consumers) {
            Map<K, BigInteger> inputs = new LinkedHashMap<>(), sides = new LinkedHashMap<>();
            for (var input : consumer.inputs()) if (!input.returned() && input.remainder() == null)
                inputs.merge(input.key(), BigInteger.valueOf(input.amount()), (a, b) -> a.add(b).min(CAP));
            for (var side : consumer.byproducts())
                sides.merge(side.key(), BigInteger.valueOf(side.amount()), (a, b) -> a.add(b).min(CAP));
            for (var input : inputs.entrySet()) for (var side : sides.entrySet()) {
                var refills = selected == null ? graph.patternsFor(input.getKey())
                        : selected.getOrDefault(input.getKey(), List.of());
                for (var refill : refills) {
                    BigInteger amount = refill.inputs().stream()
                            .filter(i -> !i.returned() && i.remainder() == null && i.key().equals(side.getKey()))
                            .map(i -> BigInteger.valueOf(i.amount())).reduce(BigInteger.ZERO, BigInteger::add).min(CAP);
                    if (amount.signum() <= 0) continue;
                    BigInteger gcd = side.getValue().gcd(amount);
                    BigInteger consumed = input.getValue().multiply(amount.divide(gcd)).min(CAP);
                    BigInteger recovered = BigInteger.valueOf(refill.outputAmount())
                            .multiply(side.getValue().divide(gcd)).min(CAP);
                    if (recovered.compareTo(consumed) > 0)
                        expected.computeIfAbsent(consumer, ignored -> new LinkedHashSet<>()).add(side.getKey());
                }
            }
        }
        return expected;
    }

    @SuppressWarnings("unchecked")
    private static <K> Map<CraftPattern<K>, Set<K>> suppressed(CraftGraph<K> graph,
            Map<K, List<CraftPattern<K>>> selected, K target, boolean compiled) throws Exception {
        Class<?> diagnosticsType = Class.forName(CraftPlannerV2.class.getName() + "$DiagnosticsCollector");
        Class<?> budgetType = Class.forName(CraftPlannerV2.class.getName() + "$SearchBudget");
        var dc = diagnosticsType.getDeclaredConstructor(int.class, int.class, CraftPlannerV2.PlanningSession.class);
        dc.setAccessible(true);
        var constructor = CraftPlannerV2.class.getDeclaredConstructor(CraftGraph.class, int.class,
                budgetType, BoundedIntegerLinearSolver.WorkBudget.class, diagnosticsType);
        constructor.setAccessible(true);
        var planner = constructor.newInstance(graph, 1, null, null,
                dc.newInstance(1, 1, new CraftPlannerV2.PlanningSession<K>()));
        var patterns = CraftPlannerV2.class.getDeclaredField("patternsByOutput"); patterns.setAccessible(true);
        ((Map<K, List<CraftPattern<K>>>) patterns.get(planner)).putAll(selected);
        var method = CraftPlannerV2.class.getDeclaredMethod("identifyPositiveFeedbackByproducts", Object.class, boolean.class);
        method.setAccessible(true);
        try { method.invoke(planner, target, compiled); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw e;
        }
        var result = CraftPlannerV2.class.getDeclaredField("suppressedPositiveFeedbackOutputs"); result.setAccessible(true);
        return (Map<CraftPattern<K>, Set<K>>) result.get(planner);
    }
}
