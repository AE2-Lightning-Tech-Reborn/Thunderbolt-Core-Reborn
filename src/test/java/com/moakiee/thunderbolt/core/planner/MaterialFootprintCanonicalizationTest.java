package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

class MaterialFootprintCanonicalizationTest {
    @Test
    void smallAndLargeRecipesMatchIndependentExactCanonicalShapes() throws Exception {
        Class<?> indexClass = MaterialFootprintIndex.class;
        Class<?> internerClass = Class.forName(indexClass.getName() + "$FootprintInterner");
        Class<?> termClass = Class.forName(indexClass.getName() + "$MaterialTerm");
        Class<?> recipeClass = Class.forName(indexClass.getName() + "$MaterialRecipe");
        var internerCtor = internerClass.getDeclaredConstructor(); internerCtor.setAccessible(true);
        var interner = internerCtor.newInstance();
        var idsField = internerClass.getDeclaredField("ids"); idsField.setAccessible(true);
        Map<?, ?> interned = (Map<?, ?>) idsField.get(interner);
        var termCtor = termClass.getDeclaredConstructor(int.class, long.class); termCtor.setAccessible(true);
        var recipeCtor = recipeClass.getDeclaredConstructor(long.class, List.class); recipeCtor.setAccessible(true);
        var method = indexClass.getDeclaredMethod("materialFootprint", CraftPattern.class,
                Map.class, internerClass, int[].class, long[].class);
        method.setAccessible(true);
        var random = new Random(2026100123L);
        int[] scratchIds = new int[8]; long[] scratchAmounts = new long[8];
        long[] sizes = {1, 2, 9, Integer.MAX_VALUE, Long.MAX_VALUE / 2, Long.MAX_VALUE};
        for (int sample = 0; sample < 4_096; sample++) {
            var mapped = new HashMap<Integer, Integer>();
            for (int key = 0; key < 12; key++) if (random.nextInt(12) != 0) mapped.put(key, 1 + random.nextInt(7));
            var inputs = new ArrayList<CraftInput<Integer>>();
            for (int k = 0, count = sample % 17; k < count; k++) {
                int key = random.nextInt(12); long amount = sizes[random.nextInt(sizes.length)];
                inputs.add(switch (sample % 41) {
                    case 0 -> CraftInput.returned(key, amount);
                    case 1 -> CraftInput.consumedReturning(key, amount, 90);
                    case 2 -> CraftInput.returnedFrom(key, amount, new ReusableStockSource("host", "pool"));
                    default -> CraftInput.of(key, amount);
                });
            }
            var pattern = new CraftPattern<>(99, 1 + random.nextInt(7), inputs,
                    sample % 23 == 0 ? List.of(new CraftOutput<>(98, 1)) : List.of(), null);
            Object actual = method.invoke(null, pattern, mapped, interner, scratchIds, scratchAmounts);
            boolean admitted = pattern.byproducts().isEmpty();
            var sums = new TreeMap<Integer, BigInteger>();
            for (var input : inputs) {
                Integer id = mapped.get(input.key());
                if (input.returned() || input.remainder() != null || input.reusableStockSource() != null || id == null) {
                    admitted = false; continue;
                }
                var sum = sums.merge(id, BigInteger.valueOf(input.amount()), BigInteger::add);
                if (sum.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) admitted = false;
            }
            if (!admitted) {
                if (actual != null) throw new AssertionError("admission " + sample);
            } else {
                var terms = new ArrayList<Object>();
                for (var entry : sums.entrySet()) terms.add(termCtor.newInstance(entry.getKey(), entry.getValue().longValueExact()));
                Object shape = recipeCtor.newInstance(pattern.outputAmount(), List.copyOf(terms));
                if (actual == null || !actual.equals(interned.get(shape))) throw new AssertionError("shape " + sample);
            }
        }
    }
}
