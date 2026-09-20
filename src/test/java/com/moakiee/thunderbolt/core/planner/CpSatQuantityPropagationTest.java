package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Fixed small graphs that used to perform millions of integer propagations even for amount 1. */
class CpSatQuantityPropagationTest {
    @BeforeAll static void loadNative() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
    }

    private static CraftPattern<String> recipe(String out, long count, String id, Object... inputs) {
        var arcs = new ArrayList<CraftInput<String>>();
        for (int i = 0; i < inputs.length; i += 2)
            arcs.add(CraftInput.of((String) inputs[i], ((Number) inputs[i + 1]).longValue()));
        return new CraftPattern<>(out, count, arcs, id);
    }

    @Test void overlappingBalancesProveMissingWithoutEnumeratingLongDomains() {
        var patterns = List.of(
                recipe("M3", 1, "r0", "M4", 1),
                recipe("M3", 2, "r1", "M0", 1, "M4", 1),
                recipe("M4", 1, "r2", "M0", 1, "M3", 1),
                recipe("M0", 1, "r3", "M1", 1, "M2", 1, "M4", 1),
                recipe("M1", 2, "r4", "M0", 1, "M2", 1, "M3", 1),
                recipe("T", 1, "target", "M4", 2));
        for (long q : new long[] {1, 1_000_000, 1_000_000_000_000L}) {
            for (boolean reverse : new boolean[] {false, true}) {
                var order = new ArrayList<>(patterns);
                if (reverse) Collections.reverse(order);
                var builder = CraftGraph.<String>builder().stock("M3", q).stock("M4", q);
                order.forEach(builder::pattern);
                check(builder.build(), q, "M0");
            }
        }
    }

    @Test void primaryDemandAndByproductBalancesProveMissingWithoutExtraPrimaryFirings() {
        var patterns = List.of(
                recipe("M2", 2, "r0", "M0", 2, "M4", 1),
                new CraftPattern<>("M3", 1, List.of(CraftInput.of("M0", 1), CraftInput.of("M4", 1)),
                        List.of(CraftOutput.of("M1", 1)), "r1"),
                recipe("M0", 1, "r2", "M1", 1, "M2", 1, "M3", 1),
                recipe("M3", 1, "r3", "M1", 1),
                recipe("T", 1, "target", "M1", 1, "M2", 1));
        for (long q : new long[] {1, 1_000_000, 1_000_000_000_000L}) {
            for (boolean reverse : new boolean[] {false, true}) {
                var order = new ArrayList<>(patterns);
                if (reverse) Collections.reverse(order);
                var builder = CraftGraph.<String>builder().stock("M2", q);
                order.forEach(builder::pattern);
                check(builder.build(), q, "M1");
            }
        }
    }

    @Test void redundantRowsDoNotReplaceASmallReplenishmentWithEnormousCirculation() {
        var first = CraftGraph.<String>builder().stock("M0", 1).stock("M1", 1).stock("M2", 4)
                .pattern(recipe("M3", 1, "r0", "M0", 1, "M1", 1, "M2", 1))
                .pattern(recipe("M4", 1, "r1", "M3", 1))
                .pattern(recipe("M1", 1, "r2", "M0", 2, "M2", 1))
                .pattern(recipe("M2", 2, "r3", "M3", 1, "M4", 1))
                .pattern(recipe("M2", 2, "r4", "M1", 2))
                .pattern(recipe("M0", 1, "r5", "M3", 1))
                .pattern(recipe("M4", 1, "r6", "M0", 1))
                .pattern(recipe("M1", 1, "r7", "M3", 1, "M4", 1))
                .pattern(recipe("M3", 1, "r8", "M4", 1))
                .pattern(recipe("T", 1, "target", "M3", 2)).build();
        var second = CraftGraph.<String>builder().stock("M0", 1).stock("M1", 2).stock("M2", 3)
                .pattern(recipe("M2", 2, "r0", "M3", 1, "M4", 1))
                .pattern(recipe("M4", 1, "r1", "M3", 1))
                .pattern(recipe("M2", 2, "r2", "M1", 1, "M3", 1))
                .pattern(recipe("M4", 3, "r3", "M0", 2, "M2", 1))
                .pattern(recipe("M0", 1, "r4", "M4", 1))
                .pattern(recipe("M1", 1, "r5", "M2", 1, "M4", 1))
                .pattern(recipe("M3", 2, "r6", "M0", 2))
                .pattern(recipe("M3", 1, "r7", "M4", 1))
                .pattern(recipe("M2", 2, "r8", "M1", 2))
                .pattern(recipe("T", 1, "target", "M0", 1, "M4", 1)).build();
        for (var graph : List.of(first, second)) {
            var result = CpSatRankedFlowSolver.solve(graph, "T", 1);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertEquals(1L, result.plan().missing().values().stream().mapToLong(Long::longValue).sum());
            assertTrue(result.plan().firings().values().stream().mapToLong(Long::longValue).sum() <= 6L,
                    "a small replenishment must not acquire long-domain redundant cycles");
            assertTrue(CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "T", 1)
                    .plan().feasible());
        }
    }

    private static void check(CraftGraph<String> graph, long q, String missing) {
        var session = new CpSatRankedFlowSolver.PlanningSession();
        var result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> CpSatRankedFlowSolver.solve(graph, "T", q, session));
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertEquals(Map.of(missing, q), result.plan().missing());
        assertFalse(result.plan().budgetExhausted(), "the missing lower bound must be proved");
        assertEquals(0, session.refinementCalls(), "exact balance proof needs no optional Petri search");
        var refill = CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "T", q);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, refill.status());
        assertTrue(refill.plan().feasible(), () -> "refill missing=" + refill.plan().missing());
        assertFalse(refill.plan().budgetExhausted());
    }
}
