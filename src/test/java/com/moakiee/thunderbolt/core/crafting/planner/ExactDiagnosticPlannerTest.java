package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExactDiagnosticPlannerTest {
    private static final BigInteger HUGE = BigInteger.TEN.pow(40).add(BigInteger.ONE);

    private ExactCraftPlan<String> solve(CraftGraph<String> graph, String output, BigInteger amount) {
        return CraftPlannerV2.planExactDiagnostic(graph, output, amount,
                new CraftPlannerV2.PlanningSession<>(), Set.of());
    }

    @Test
    void intermediateDemandAndBatchCeilingRemainExactBeyondLong() {
        var graph = CraftGraph.<String>builder()
                .pattern("target", 1, List.of(CraftInput.of("part", 7)))
                .pattern("part", 3, List.of(CraftInput.of("raw", 11)))
                .stock("raw", 13).build();
        var plan = solve(graph, "target", HUGE);
        BigInteger parts = HUGE.multiply(BigInteger.valueOf(7));
        BigInteger batches = parts.add(BigInteger.TWO).divide(BigInteger.valueOf(3));
        assertEquals(parts, plan.grossDemand().get("part"));
        assertEquals(batches.multiply(BigInteger.valueOf(11)).subtract(BigInteger.valueOf(13)),
                plan.missing().get("raw"));
        assertEquals(BigInteger.valueOf(13), plan.usedStock().get("raw"));
        assertFalse(plan.incomplete());
    }

    @Test
    void ordinaryTargetCanHaveAnIntermediateLargerThanLong() {
        var graph = CraftGraph.<String>builder()
                .pattern("target", 1, List.of(CraftInput.of("part", Long.MAX_VALUE)))
                .pattern("part", Long.MAX_VALUE, List.of(CraftInput.of("raw", 1)))
                .stock("raw", 2).build();
        var plan = solve(graph, "target", BigInteger.TWO);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), plan.grossDemand().get("part"));
        assertEquals(BigInteger.TWO, plan.usedStock().get("raw"));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void coefficientMultiplicationIsPreservedBeforePlanning() {
        var input = CraftInput.of("raw", Long.MAX_VALUE).scaled(17);
        var graph = CraftGraph.<String>builder().pattern("target", 1, List.of(input)).build();
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(34)),
                solve(graph, "target", BigInteger.TWO).missing().get("raw"));
    }

    @Test
    void stockAboveOldSaturationLimitIsNotLost() {
        var graph = CraftGraph.<String>builder().pattern("target", 1, List.of(CraftInput.of("raw", 1)))
                .stock("raw", Long.MAX_VALUE).build();
        var plan = solve(graph, "target", BigInteger.valueOf(Long.MAX_VALUE));
        assertTrue(plan.missing().isEmpty());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), plan.usedStock().get("raw"));
    }

    @Test
    void catalystsAreReservedOnceWhileConsumablesScale() {
        var graph = CraftGraph.<String>builder().pattern("target", 1,
                List.of(CraftInput.returned("catalyst", 2), CraftInput.of("raw", 3)))
                .stock("catalyst", 2).build();
        var plan = solve(graph, "target", HUGE);
        assertEquals(BigInteger.TWO, plan.usedStock().get("catalyst"));
        assertEquals(HUGE.multiply(BigInteger.valueOf(3)), plan.missing().get("raw"));
    }

    @Test
    void finiteDurabilityUsesExactCeiling() {
        var input = CraftInput.finiteUse("tool", 2, 7);
        assertEquals(HUGE.add(BigInteger.valueOf(6)).divide(BigInteger.valueOf(7)).multiply(BigInteger.TWO),
                input.unitsForExact(HUGE));
        assertEquals(BigInteger.ZERO, input.unitsForExact(BigInteger.ZERO));
    }

    @Test
    void siblingByproductOffsetsHugeDemand() {
        var graph = CraftGraph.<String>builder()
                .pattern("target", 1, List.of(CraftInput.of("b", 3), CraftInput.of("a", 1)))
                .pattern("a", 1, List.of(CraftInput.of("raw", 1)), List.of(CraftOutput.of("b", 2)))
                .build();
        var plan = solve(graph, "target", HUGE);
        assertEquals(HUGE, plan.missing().get("b"));
        assertEquals(HUGE, plan.missing().get("raw"));
    }

    @Test
    void sharedDagAggregatesBeforeRounding() {
        var graph = CraftGraph.<String>builder()
                .pattern("target", 1, List.of(CraftInput.of("a", 1), CraftInput.of("b", 1)))
                .pattern("a", 1, List.of(CraftInput.of("shared", 2)))
                .pattern("b", 1, List.of(CraftInput.of("shared", 3)))
                .pattern("shared", 9, List.of(CraftInput.of("raw", 1))).build();
        var plan = solve(graph, "target", HUGE);
        assertEquals(HUGE.multiply(BigInteger.valueOf(5)).add(BigInteger.valueOf(8)).divide(BigInteger.valueOf(9)),
                plan.missing().get("raw"));
    }

    @Test
    void cyclicRecipeDoesNotCreateFreeMaterial() {
        var graph = CraftGraph.<String>builder()
                .pattern("a", 1, List.of(CraftInput.of("b", 2)))
                .pattern("b", 1, List.of(CraftInput.of("a", 1))).build();
        var plan = solve(graph, "a", HUGE);
        assertFalse(plan.missing().isEmpty());
        assertTrue(plan.incomplete());
    }

    @Test
    void normalOrderCanExpandToHundredsOfDigitsWithoutPerCraftLoops() {
        var builder = CraftGraph.<String>builder();
        for (int i = 0; i < 1000; i++) {
            builder.pattern("p" + i, 1, List.of(CraftInput.of("p" + (i + 1), 9)));
        }
        var plan = solve(builder.build(), "p0", BigInteger.ONE);
        assertEquals(BigInteger.valueOf(9).pow(1000), plan.missing().get("p1000"));
        assertEquals(1000, plan.firings().size());
    }

    @Test
    void representationGuardIsExplicit() {
        assertThrows(IllegalArgumentException.class, () -> solve(CraftGraph.<String>builder().build(),
                "a", BigInteger.ONE.shiftLeft(32768)));
    }
}
