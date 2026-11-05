package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ExactPlanPreviewTest {
    private static final BigInteger LIMIT = BigInteger.valueOf(Sat.SAT);

    @Test
    void outputProductsAndCrossPatternSumsRespectTheExactThreshold() {
        for (long count : new long[]{0, 1, 2, 3, Sat.SAT / 2, Sat.SAT - 1, Sat.SAT}) {
            for (BigInteger amount : List.of(BigInteger.ONE, BigInteger.TWO, LIMIT.subtract(BigInteger.ONE),
                    LIMIT, LIMIT.add(BigInteger.ONE), BigInteger.ONE.shiftLeft(64), BigInteger.TEN.pow(100))) {
                var recipe = pattern("out", amount, List.of(), List.of());
                var plan = plan(Map.of(recipe, count));
                assertEquals(oracle(plan), ExactPlanPreview.needsExact(plan), count + " * " + amount);
            }
        }
        var first = pattern("out", LIMIT.subtract(BigInteger.ONE), List.of(), List.of());
        var second = pattern("out", BigInteger.ONE, List.of(), List.of());
        assertFalse(ExactPlanPreview.needsExact(plan(Map.of(first, 1L))));
        assertTrue(ExactPlanPreview.needsExact(plan(Map.of(first, 1L, second, 1L))));
    }

    @Test
    void exactInputAndByproductFieldsOverrideTheirLongProjections() {
        var narrowInput = new CraftInput<>("in", Long.MAX_VALUE, false, 1, null, null, BigInteger.ONE);
        var wideInput = new CraftInput<>("in", 1, false, 1, null, null, LIMIT);
        var narrowOutput = new CraftOutput<>("side", Long.MAX_VALUE, BigInteger.ONE);
        var wideOutput = new CraftOutput<>("side", 1, BigInteger.TEN.pow(100));
        assertFalse(ExactPlanPreview.needsExact(plan(Map.of(
                pattern("out", BigInteger.ONE, List.of(narrowInput), List.of(narrowOutput)), 1L))));
        assertTrue(ExactPlanPreview.needsExact(plan(Map.of(
                pattern("out", BigInteger.ONE, List.of(wideInput), List.of()), 0L))));
        assertFalse(ExactPlanPreview.needsExact(plan(Map.of(
                pattern("out", BigInteger.ONE, List.of(), List.of(wideOutput)), 0L))));
        assertTrue(ExactPlanPreview.needsExact(plan(Map.of(
                pattern("out", BigInteger.ONE, List.of(), List.of(wideOutput)), 1L))));
    }

    @Test
    void primaryByproductsAndContainerRemaindersAccumulateByKeyEquality() {
        var recipe = pattern(new String("same"), LIMIT.subtract(BigInteger.TWO), List.of(),
                List.of(CraftOutput.of(new String("same"), 1), CraftOutput.of("same", 1)));
        assertTrue(ExactPlanPreview.needsExact(plan(Map.of(recipe, 1L))));
        var container = pattern("out", BigInteger.ONE,
                List.of(CraftInput.consumedReturning("full", 2, "empty")), List.of());
        var producer = pattern("empty", LIMIT.subtract(BigInteger.TWO), List.of(), List.of());
        assertTrue(ExactPlanPreview.needsExact(plan(Map.of(container, 1L, producer, 1L))));
    }

    @Test
    void signedCountsRetainExactCancellationRegardlessOfIterationOrder() {
        var first = pattern("out", LIMIT, List.of(), List.of());
        var second = pattern("out", LIMIT, List.of(), List.of());
        for (boolean reversed : List.of(false, true)) {
            var counts = new LinkedHashMap<CraftPattern<String>, Long>();
            counts.put(first, reversed ? -1L : 1L);
            counts.put(second, reversed ? 1L : -1L);
            assertFalse(ExactPlanPreview.needsExact(plan(counts)));
            counts.put(pattern("third", BigInteger.ONE, List.of(), List.of()), Sat.SAT);
            assertTrue(ExactPlanPreview.needsExact(plan(counts)));
        }
    }

    @Test
    void allExistingLongSummaryGuardsRemainActive() {
        for (int field = 0; field < 3; field++) {
            var amounts = Map.of("x", Sat.SAT);
            var plan = new CraftPlan<String>(true, true, Map.of(), field == 0 ? amounts : Map.of(),
                    Map.of(), field == 1 ? amounts : Map.of(), field == 2 ? amounts : Map.of(), 0, false);
            assertTrue(ExactPlanPreview.needsExact(plan));
        }
        assertFalse(ExactPlanPreview.needsExact(plan(Map.of())));
    }

    @Test
    void randomizedPlansMatchAnIndependentBigIntegerOracle() {
        var random = new Random(0x5eeda11L);
        long[] counts = {0, 1, 2, 127, 1_000_000, Sat.SAT / 2, Sat.SAT - 1, Sat.SAT, -1, -Sat.SAT};
        BigInteger[] amounts = {BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(128),
                LIMIT.divide(BigInteger.TWO), LIMIT.subtract(BigInteger.ONE), LIMIT, LIMIT.add(BigInteger.ONE),
                BigInteger.ONE.shiftLeft(100), BigInteger.TEN.pow(100)};
        int exact = 0;
        int ordinary = 0;
        for (int sample = 0; sample < 10_000; sample++) {
            var firings = new LinkedHashMap<CraftPattern<String>, Long>();
            boolean small = sample % 3 == 0;
            for (int p = 0, n = random.nextInt(12); p < n; p++) {
                var inputs = new ArrayList<CraftInput<String>>();
                var outputs = new ArrayList<CraftOutput<String>>();
                for (int i = 0, size = random.nextInt(4); i < size; i++) {
                    inputs.add(new CraftInput<>("k" + random.nextInt(5), 1, false, 1, null, null,
                            amounts[random.nextInt(small ? 3 : amounts.length)]));
                }
                for (int i = 0, size = random.nextInt(4); i < size; i++) {
                    outputs.add(new CraftOutput<>("k" + random.nextInt(5), 1,
                            amounts[random.nextInt(small ? 3 : amounts.length)]));
                }
                firings.put(pattern("k" + random.nextInt(5),
                        amounts[random.nextInt(small ? 3 : amounts.length)], inputs, outputs),
                        counts[random.nextInt(small ? 5 : counts.length)]);
            }
            var plan = plan(firings);
            boolean expected = oracle(plan);
            if (expected) exact++; else ordinary++;
            assertEquals(expected, ExactPlanPreview.needsExact(plan), "sample " + sample);
        }
        assertTrue(exact > 1_000 && ordinary > 1_000);
    }

    private static CraftPattern<String> pattern(String key, BigInteger amount,
            List<CraftInput<String>> inputs, List<CraftOutput<String>> outputs) {
        return new CraftPattern<>(key, amount, inputs, outputs, null);
    }

    private static CraftPlan<String> plan(Map<CraftPattern<String>, Long> firings) {
        return new CraftPlan<>(true, true, firings, Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
    }

    // Compute full unbounded totals, then compare; no saturating helper or early output exit.
    private static boolean oracle(CraftPlan<String> plan) {
        for (Map<?, Long> map : List.of(plan.grossDemand(), plan.firings(), plan.usedStock(), plan.missing())) {
            for (long value : map.values()) if (BigInteger.valueOf(value).compareTo(LIMIT) >= 0) return true;
        }
        Map<String, BigInteger> totals = new HashMap<>();
        for (var entry : plan.firings().entrySet()) {
            var pattern = entry.getKey();
            for (var input : pattern.inputs()) if (input.exactAmount().compareTo(LIMIT) >= 0) return true;
            BigInteger count = BigInteger.valueOf(entry.getValue());
            totals.merge(pattern.output(), pattern.exactOutputAmount().multiply(count), BigInteger::add);
            for (var output : pattern.byproducts())
                totals.merge(output.key(), output.exactAmount().multiply(count), BigInteger::add);
        }
        return totals.values().stream().anyMatch(value -> value.compareTo(LIMIT) >= 0);
    }
}
