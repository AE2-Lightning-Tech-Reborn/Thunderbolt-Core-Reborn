package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrimaryInputIndexTest {
    @Test void deepGraphsUseAnExplicitStackAndKeepOnlyTheCyclicTail() {
        int size = 100_000;
        var builder = CraftGraph.<Integer>builder();
        for (int k = 0; k < size - 1; k++) builder.pattern(k, 1, List.of(CraftInput.of(k + 1, 1)));
        builder.pattern(size - 1, 1, List.of(CraftInput.of(size - 2, 1)));
        var index = PrimaryInputIndex.build(builder.build(), 0);
        var tail = Set.of(size - 2, size - 1);
        assertEquals(size, index.keys.size());
        assertEquals(Map.of(size - 2, tail, size - 1, tail), index.cyclicMembership());
    }

    @Test void rawReturnedAndHostSlotsStayStructuralWhileRemaindersDoNotAddArcs() {
        var host = new ReusableStockSource("host", "pool");
        var graph = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.returnedFrom("H", 1, host),
                        CraftInput.returned("R", 1), CraftInput.consumedReturning("A", 1, "B")))
                .pattern("H", 1, List.of(CraftInput.of("T", 1)))
                .pattern("R", 1, List.of(CraftInput.of("T", 1)))
                .pattern("B", 1, List.of(CraftInput.of("B", 1)))
                .build();
        var index = PrimaryInputIndex.build(graph, "T");
        var members = Set.of("T", "H", "R");
        assertEquals(List.of("T", "H", "R", "A"), index.keys);
        assertEquals(Map.of("T", members, "H", members, "R", members), index.cyclicMembership());
    }

    @Test void componentTraversalPropagatesRouterCancellation() {
        var index = PrimaryInputIndex.build(CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("T", 1))).build(), "T");
        var signal = new PlanningExitException("stop structural traversal");
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void checkpoint() { throw signal; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) { }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(signal, assertThrows(PlanningExitException.class, index::cyclicMembership));
        }
    }
}
