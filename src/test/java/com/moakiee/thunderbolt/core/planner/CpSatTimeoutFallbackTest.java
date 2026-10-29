package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Forces the native UNKNOWN path without depending on machine load or wall-clock timing. */
public class CpSatTimeoutFallbackTest {
    private static int nativeCalls;

    public static long[] timedOut(Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9, Object p10, Object p11, Object p12, Object p13, Object p14, Object p15, Object p16, Object p17, Object p18, Object p19, Object p20, Object p21, Object p22, Object p23, Object p24, Object p25, Object p26, Object p27, Object p28, Object p29, Object p30) {
        nativeCalls++;
        return new long[] {3L, 0L};
    }

    @Test void nativeTimeoutCannotReplaceAnExecutablePreflightWithMissingMaterials() throws Exception {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
        var graph = CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("A", 1, List.of(CraftInput.of("R", 1)), "ra"))
                .pattern(new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 2)), List.of(CraftOutput.of("C", 1)), "ab"))
                .pattern(new CraftPattern<>("C", 1, List.of(CraftInput.of("B", 2)), List.of(CraftOutput.of("A", 1)), "bc"))
                .pattern(new CraftPattern<>("A", 1, List.of(CraftInput.of("C", 2)), List.of(CraftOutput.of("B", 1)), "ca"))
                .pattern(new CraftPattern<>("U", 1, List.of(CraftInput.of("R", 1)), "ru"))
                .pattern(new CraftPattern<>("V", 1, List.of(CraftInput.of("U", 1)), "uv"))
                .pattern(new CraftPattern<>("W", 1, List.of(CraftInput.of("V", 1)), "vw"))
                .pattern(new CraftPattern<>("C", 1, List.of(CraftInput.of("W", 1)), "wc"))
                .stock("R", 1).stock("B", 1).build();
        assertNotNull(SmallConservativeSearch.tryPlan(graph, "C", 1, 256, () -> true));
        var field = CpSatRuntime.class.getDeclaredField("bridge");
        field.setAccessible(true);
        Object original = field.get(null);
        var components = original.getClass().getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        Class<?>[] arguments = new Class<?>[31];
        Arrays.fill(arguments, Object.class);
        Method timeout = CpSatTimeoutFallbackTest.class.getMethod("timedOut", arguments);
        for (int i = 0; i < components.length; i++) {
            var getter = components[i].getAccessor();
            getter.setAccessible(true);
            values[i] = components[i].getName().equals("solveRankedPlan") ? timeout : getter.invoke(original);
            types[i] = components[i].getType();
        }
        var constructor = original.getClass().getDeclaredConstructor(types);
        constructor.setAccessible(true);
        nativeCalls = 0;
        try {
            field.set(null, constructor.newInstance(values));
            var session = new CpSatRankedFlowSolver.PlanningSession();
            var result = CpSatRankedFlowSolver.solve(graph, "C", 1, session);
            assertEquals(1, nativeCalls, "test must reach the native timeout");
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertTrue(result.plan().feasible(), "timeout discarded the executable alternative");
            assertTrue(result.plan().missing().isEmpty());
            assertEquals(1L, result.plan().usedStock().get("R"));
            assertTrue(result.plan().budgetExhausted(), "native timeout still prevents an optimality claim");
            assertTrue(session.incomplete());
            assertEquals(1, session.smallRecoveryAttempts());
        } finally {
            field.set(null, original);
        }
    }
}
