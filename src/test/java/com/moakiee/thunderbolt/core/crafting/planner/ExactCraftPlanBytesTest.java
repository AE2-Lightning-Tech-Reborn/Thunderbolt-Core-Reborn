package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.core.crafting.big.BigCraftingPlanner;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExactCraftPlanBytesTest {
    @Test
    void executableAndMissingPlansRetainCostsBeyondLong() {
        for (var amount : List.of(BigInteger.ONE, BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.ONE.shiftLeft(63), BigInteger.TEN.pow(100).add(BigInteger.valueOf(12345)))) {
            for (boolean stocked : List.of(true, false)) {
                var stock = stocked ? Map.of("raw", amount) : Map.<String, BigInteger>of();
                var graph = CraftGraph.<String>builder().stockExact("raw", stock.getOrDefault("raw", BigInteger.ZERO))
                        .pattern("out", 1, List.of(CraftInput.of("raw", 1))).build();
                var result = BigCraftingPlanner.plan(graph, "out", amount, stock);
                assertEquals(stocked, result.executable());
                assertNotNull(result.diagnostic());
                // Two requested item nodes at eight bytes per unit, one firing per output,
                // plus eight bytes of overhead for each node.
                assertEquals(amount.multiply(BigInteger.valueOf(17)).add(BigInteger.valueOf(16)),
                        result.diagnostic().byteCost(key -> 1, Set.of()));
            }
        }
    }

    @Test
    void aSingleRequestedItemCanStillNeedMoreThanLongBytes() {
        var raw = BigInteger.TEN.pow(25);
        var graph = CraftGraph.<String>builder().stockExact("raw", raw);
        String previous = "raw";
        for (int i = 0; i < 25; i++) {
            String next = "part" + i;
            graph.pattern(next, 1, List.of(CraftInput.of(previous, 10)));
            previous = next;
        }
        var result = BigCraftingPlanner.plan(graph.build(), previous, BigInteger.ONE, Map.of("raw", raw));
        assertTrue(result.executable());
        var expected = BigInteger.TEN.pow(26).subtract(BigInteger.ONE).divide(BigInteger.valueOf(9))
                .add(raw.subtract(BigInteger.ONE).divide(BigInteger.valueOf(9)))
                .add(BigInteger.valueOf(26 * 8));
        assertTrue(expected.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        assertEquals(expected, result.diagnostic().byteCost(key -> 8, Set.of()));
    }

    @Test
    void mixedUnitsRoundOnlyAfterAddingAllFractions() {
        var plan = new ExactCraftPlan<String>(Map.of(), Map.of(), Map.of(),
                Map.of("item", BigInteger.ONE, "water", BigInteger.ONE, "gas", BigInteger.ONE), false);
        // 8 + 8/1000 + 8/3 rounds to 11, then three nodes cost 24.
        assertEquals(BigInteger.valueOf(35), plan.byteCost(key -> switch (key) {
            case "water" -> 1000;
            case "gas" -> 3;
            default -> 1;
        }, Set.of()));
    }

    @Test
    void returnedContainersAndToolUsesStayExact() {
        var n = BigInteger.TEN.pow(150).add(BigInteger.ONE);
        var recipe = new CraftPattern<>("out", 1, List.of(CraftInput.returned("catalyst", 2),
                CraftInput.consumedReturning("filled", 3, "empty"), CraftInput.of("tool", 4)), null);
        var plan = new ExactCraftPlan<>(Map.of(recipe, n), Map.<String, BigInteger>of(), Map.<String, BigInteger>of(),
                Map.of("out", n), false);
        // Requested output: 8N; firings: N; returned/remaining/tool inputs: (2+3+4)N.
        assertEquals(n.multiply(BigInteger.valueOf(18)).add(BigInteger.valueOf(8)),
                plan.byteCost(key -> 1, Set.of("tool")));
    }
}
