package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AggregateDemandTraversalTest {
    @Test
    void independentConesDoNotRescanEverySibling() {
        long small = checkpointsForIndependentCones(128);
        long large = checkpointsForIndependentCones(512);
        assertTrue(large <= 5 * small,
                "four times the independent work must not become quadratic: " + small + " -> " + large);
    }

    @Test
    void sharedInputIsBatchedAfterAllOfItsConsumers() {
        var source = new ReusableStockSource("host", "pool");
        var shared = new CraftPattern<>("shared", 3, List.of(CraftInput.of("raw", 2)), "shared");
        for (long amount : new long[] {1, 2, 7, 1_000_000_000_000L}) {
            long batches = (2 * amount + 2) / 3;
            var graph = CraftGraph.<String>builder().pattern(shared)
                    .pattern("left", 1, List.of(CraftInput.of("shared", 1)))
                    .pattern("right", 1, List.of(CraftInput.of("shared", 1)))
                    .pattern("wrapper", 1, List.of(CraftInput.of("left", 1), CraftInput.of("right", 1)))
                    .pattern("T", 1, List.of(CraftInput.returnedFrom("tool", 1, source),
                            CraftInput.of("wrapper", 1)))
                    .stock("raw", 2 * batches).reusableStock("host", "tool", 1)
                    .reusableStockRoute(source, "tool", List.of("tool")).build();
            var result = CraftPlannerV2.planDetailed(graph, "T", amount, 1, 1);
            assertTrue(result.plan().feasible());
            assertFalse(result.plan().budgetExhausted());
            assertEquals(batches, result.plan().firings().get(shared));
            assertEquals(Map.of("raw", 2 * batches), result.plan().usedStock());
            assertEquals(0, result.diagnostics().consumedSearchBudget());
        }
    }

    @Test
    void cancellationStillPropagatesInsideTheDemandTraversal() {
        var graph = independentCones(16);
        var exit = new PlanningExitException("cancel aggregate traversal");
        var context = new PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void report(PlanningDiagnosticSnapshot snapshot) {}
            public void checkpoint() {
                if (StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                        frame.getClassName().equals(CraftPlannerV2.class.getName())
                                && frame.getMethodName().equals("obtainAggregate")))) throw exit;
            }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> CraftPlannerV2.planDetailed(graph, "T", 1, 1, 1)));
        }
    }

    private static long checkpointsForIndependentCones(int size) {
        var graph = independentCones(size);
        long[] checks = {0};
        var context = new PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void report(PlanningDiagnosticSnapshot snapshot) {}
            public void checkpoint() { checks[0]++; }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            var result = CraftPlannerV2.planDetailed(graph, "T", 1, 1, 1);
            assertTrue(result.plan().feasible());
            assertFalse(result.plan().budgetExhausted());
            assertEquals(size, result.plan().usedStock().size());
            assertTrue(result.plan().usedStock().values().stream().allMatch(n -> n == 1L));
            assertEquals(size + 1, result.plan().firings().size());
            assertEquals(0, result.diagnostics().consumedSearchBudget());
        }
        return checks[0];
    }

    private static CraftGraph<String> independentCones(int size) {
        var source = new ReusableStockSource("host", "pool");
        var builder = CraftGraph.<String>builder();
        var inputs = new ArrayList<CraftInput<String>>();
        inputs.add(CraftInput.returnedFrom("tool", 1, source));
        for (int i = 0; i < size; i++) {
            inputs.add(CraftInput.of("wrapper" + i, 1));
            builder.pattern("wrapper" + i, 1, List.of(CraftInput.of("raw" + i, 1)))
                    .stock("raw" + i, 1);
        }
        return builder.pattern("T", 1, inputs).reusableStock("host", "tool", 1)
                .reusableStockRoute(source, "tool", List.of("tool")).build();
    }
}
