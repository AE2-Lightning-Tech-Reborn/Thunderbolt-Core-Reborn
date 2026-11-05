package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import com.moakiee.thunderbolt.api.crafting.*;

class FeedbackFiringVectorTest {
    @Test
    void balancedAndLossyCyclesMatchEveryLegacyRotation() throws Exception {
        var random = new Random(20261024);
        for (int trial = 0; trial < 80; trial++) {
            int size = 2 + random.nextInt(9);
            var cycle = new ArrayList<ConservativeFeedbackAnalysis.Transition<Integer>>();
            for (int i = 0; i < size; i++) {
                long amount = 1 + random.nextInt(7);
                long input = amount * (trial % 2 == 0 && i == 0 ? 2 : 1);
                var pattern = new CraftPattern<Integer>((i + 1) % size, amount,
                        List.of(CraftInput.of(i, input)), null);
                cycle.add(new ConservativeFeedbackAnalysis.Transition<>(pattern, i, input, (i + 1) % size, amount));
            }
            var expected = new LinkedHashSet<Map<CraftPattern<Integer>, Long>>();
            var single = method("primitiveFiringVector");
            for (int start = 0; start < size; start++) {
                var rotated = new ArrayList<>(cycle);
                Collections.rotate(rotated, -start);
                @SuppressWarnings("unchecked") var vector = (Map<CraftPattern<Integer>, Long>) single.invoke(null, rotated);
                if (vector != null) expected.add(vector);
            }
            @SuppressWarnings("unchecked") var result = (List<ConservativeFeedbackAnalysis.RoundVector<Integer>>)
                    method("primitiveFiringVectors").invoke(null, cycle);
            assertEquals(expected, new LinkedHashSet<>(result.stream().map(ConservativeFeedbackAnalysis.RoundVector::firings).toList()));
        }
    }

    @Test
    void balancedThousandStateCycleUsesLinearCheckpoints() throws Exception {
        var cycle = new ArrayList<ConservativeFeedbackAnalysis.Transition<Integer>>();
        for (int i = 0; i < 1000; i++) {
            var pattern = new CraftPattern<Integer>((i + 1) % 1000, 1, List.of(CraftInput.of(i, 1)), null);
            cycle.add(new ConservativeFeedbackAnalysis.Transition<>(pattern, i, 1, (i + 1) % 1000, 1));
        }
        int[] checkpoints = {0};
        var context = new PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void checkpoint() { assertTrue(++checkpoints[0] < 10_000, "balanced cycle must not repeat each rotation"); }
            public void report(PlanningDiagnosticSnapshot snapshot) {}
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            var result = (List<?>) method("primitiveFiringVectors").invoke(null, cycle);
            assertEquals(1, result.size());
        }
        assertTrue(checkpoints[0] >= 1000);
    }

    private static Method method(String name) throws Exception {
        var method = ConservativeFeedbackAnalysis.class.getDeclaredMethod(name, List.class);
        method.setAccessible(true);
        return method;
    }
}
