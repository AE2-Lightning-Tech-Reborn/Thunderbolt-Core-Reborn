package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RawResourcePotentialTest {
    @Test
    void batchFractionsRemainPositiveAndScaleToExactIntegers() {
        var weights = RawResourcePotential.weights(List.of("B", "A", "R"), Map.of(
                "A", List.of(new CraftPattern<>("A", 3, List.of(CraftInput.of("R", 1)), null)),
                "B", List.of(new CraftPattern<>("B", 5, List.of(CraftInput.of("A", 1)), null))));
        assertEquals(Map.of("R", BigInteger.valueOf(15), "A", BigInteger.valueOf(5), "B", BigInteger.ONE), weights);
    }

    @Test
    void everyRandomBatchRecipeSatisfiesTheCommonMaterialLowerBound() {
        Random random = new Random(0x524157434F5354L);
        for (int sample = 0; sample < 200; sample++) {
            var order = new ArrayList<String>();
            var patterns = new HashMap<String, List<CraftPattern<String>>>();
            for (int i = 0; i < 10; i++) {
                order.add("M" + i);
                if (i >= 8) continue;
                var recipes = new ArrayList<CraftPattern<String>>();
                for (int choice = 0; choice < 2; choice++) recipes.add(new CraftPattern<>("M" + i,
                        1 + random.nextInt(7), List.of(
                        CraftInput.of("M" + (i + 1 + random.nextInt(9 - i)), 1 + random.nextInt(3)),
                        CraftInput.of("M" + (i + 1 + random.nextInt(9 - i)), 1 + random.nextInt(3))), null));
                patterns.put("M" + i, recipes);
            }
            var weights = RawResourcePotential.weights(order, patterns);
            assertEquals(order.size(), weights.size());
            assertTrue(weights.values().stream().allMatch(weight -> weight.signum() > 0));
            for (var recipes : patterns.values()) for (var recipe : recipes) {
                BigInteger input = BigInteger.ZERO;
                for (var in : recipe.inputs()) input = input.add(weights.get(in.key()).multiply(BigInteger.valueOf(in.amount())));
                assertTrue(weights.get(recipe.output()).multiply(BigInteger.valueOf(recipe.outputAmount())).compareTo(input) <= 0);
            }
        }
    }

    @Test
    void excessiveFractionSizeDeclinesOnlyTheOptionalBound() {
        var order = new ArrayList<String>();
        var patterns = new HashMap<String, List<CraftPattern<String>>>();
        for (int i = 0; i < 49; i++) {
            order.add("M" + i);
            if (i < 48) patterns.put("M" + i, List.of(new CraftPattern<>("M" + i, 97,
                    List.of(CraftInput.of("M" + (i + 1), 1)), null)));
        }
        assertTrue(RawResourcePotential.weights(order, patterns).isEmpty());
    }
}
