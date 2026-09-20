package com.moakiee.thunderbolt.core.crafting.big;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.core.crafting.planner.*;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

class BigExecutionProgramTest {
    static final BigInteger N = BigInteger.TEN.pow(100);

    @Test
    void repeatedCycleKeepsOneSeedAndNeverExpandsTheQuantity() {
        var a =
                new CraftPattern<>(
                        "B", 1, List.of(CraftInput.of("A", 1), CraftInput.of("raw", 1)), "a");
        var b =
                new CraftPattern<>(
                        "T",
                        1,
                        List.of(CraftInput.of("B", 1)),
                        List.of(CraftOutput.of("A", 1)),
                        "b");
        var counts = new LinkedHashMap<CraftPattern<String>, BigInteger>();
        counts.put(a, N);
        counts.put(b, N);
        var program =
                BigExecutionProgram.certify(counts, Map.of("A", BigInteger.ONE, "raw", N))
                        .orElseThrow();
        assertEquals(1, program.blocks().size());
        assertEquals(N, program.blocks().get(0).repetitions());
        assertEquals(Map.of("A", BigInteger.ONE, "raw", N), program.required());
        var stock = new HashMap<>(program.required());
        BigExecutionProgram.apply(stock, program.delta(), BigInteger.ONE);
        assertEquals(Map.of("A", BigInteger.ONE, "T", N), stock);
    }

    @Test
    void unseededConservativeCycleIsNotCertifiedByBalanceAlone() {
        var a = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)), "a");
        var b = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 1)), "b");
        assertTrue(
                BigExecutionProgram.certify(Map.of(a, N, b, N), Map.<String, BigInteger>of())
                        .isEmpty());
    }

    @Test
    void longOverflowChainProducesAnExecutableExactPlan() {
        var b = CraftGraph.<String>builder().stockExact("raw", N.multiply(BigInteger.TEN.pow(25)));
        String previous = "raw";
        for (int i = 0; i < 25; i++) {
            String next = "p" + i;
            b.pattern(next, 1, List.of(CraftInput.of(previous, 10)));
            previous = next;
        }
        var result =
                BigCraftingPlanner.plan(
                        b.build(), previous, N, Map.of("raw", N.multiply(BigInteger.TEN.pow(25))));
        assertTrue(result.executable());
        assertEquals(N, result.program().delta().get(previous));
        assertEquals(N.multiply(BigInteger.TEN.pow(25)), result.program().required().get("raw"));
    }

    @Test
    void catalystReturnsOnceAndContainerReturnScalesExactly() {
        var p =
                new CraftPattern<>(
                        "T",
                        1,
                        List.of(
                                CraftInput.returned("tool", 1),
                                CraftInput.consumedReturning("full", 1, "empty")),
                        null);
        var program =
                BigExecutionProgram.certify(Map.of(p, N), Map.of("full", N, "tool", BigInteger.ONE))
                        .orElseThrow();
        assertEquals(BigInteger.ONE, program.required().get("tool"));
        assertFalse(program.delta().containsKey("tool"));
        assertEquals(N, program.delta().get("empty"));
    }

    @Test
    void summaryCannotBeForgedAndFailedCommitDoesNotMutateStock() {
        var recipe = new CraftPattern<>("out", 1, List.of(CraftInput.of("raw", 2)), null);
        var forged =
                new BigExecutionProgram.Block<>(
                        List.of(new BigExecutionProgram.Step<>(recipe, N)),
                        BigInteger.ONE,
                        Map.of(),
                        Map.of("out", N));
        var verified = BigExecutionProgram.of(List.of(forged));
        assertEquals(
                N.multiply(BigInteger.TWO), verified.blocks().get(0).required().get("raw"));
        var stock = new LinkedHashMap<String, BigInteger>();
        stock.put("first", BigInteger.TEN);
        var delta = new LinkedHashMap<String, BigInteger>();
        delta.put("first", BigInteger.ONE);
        delta.put("second", BigInteger.ONE.negate());
        assertThrows(
                IllegalArgumentException.class,
                () -> BigExecutionProgram.apply(stock, delta, BigInteger.ONE));
        assertEquals(Map.of("first", BigInteger.TEN), stock);
    }

    @Test
    void composedPrefixAgreesWithIndependentSequentialExecution() {
        var random = new Random(918);
        for (int run = 0; run < 500; run++) {
            var steps = new ArrayList<BigExecutionProgram.Step<String>>();
            for (int i = 0; i < 8; i++) {
                String raw = "m" + random.nextInt(4), out = "m" + random.nextInt(4);
                var p =
                        new CraftPattern<>(
                                out,
                                1 + random.nextInt(3),
                                List.of(CraftInput.of(raw, 1 + random.nextInt(3))),
                                null);
                steps.add(
                        new BigExecutionProgram.Step<>(
                                p, BigInteger.valueOf(1 + random.nextInt(4))));
            }
            var repetitions = BigInteger.valueOf(1 + random.nextInt(4));
            var block = BigExecutionProgram.block(steps, repetitions);
            var stock = new HashMap<>(block.required());
            for (int cycle = 0; cycle < repetitions.intValueExact(); cycle++)
                for (var step : steps)
                    for (int n = 0; n < step.copies().intValueExact(); n++) {
                        var p = step.recipe();
                        var input = p.inputs().get(0);
                        assertTrue(
                                stock.getOrDefault(input.key(), BigInteger.ZERO)
                                                .compareTo(input.exactAmount())
                                        >= 0);
                        stock.merge(input.key(), input.exactAmount().negate(), BigInteger::add);
                        stock.merge(p.output(), p.exactOutputAmount(), BigInteger::add);
                    }
            stock.values().removeIf(n -> n.signum() == 0);
            var bulk = new HashMap<>(block.required());
            BigExecutionProgram.apply(bulk, block.delta(), BigInteger.ONE);
            assertEquals(stock, bulk);
        }
    }
}
