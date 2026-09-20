package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CpSatPetriRefinementTest {
    @BeforeAll static void nativeRuntimeLoads() {
        assertTrue(CpSatIntegerLinearSolver.initializeFromTestClasspath());
    }

    @Test void choosesExecutableAlternativeInsteadOfAddingAnUnnecessaryCycleSeed() {
        var ab = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 1)), "ab");
        var bt = new CraftPattern<>("T", 1, List.of(CraftInput.of("B", 1)),
                List.of(CraftOutput.of("A", 1)), "bt");
        var ru = new CraftPattern<>("U", 1, List.of(CraftInput.of("R", 1)), "ru");
        var uv = new CraftPattern<>("V", 1, List.of(CraftInput.of("U", 1)), "uv");
        var vt = new CraftPattern<>("T", 1, List.of(CraftInput.of("V", 1)), "vt");
        var graph = CraftGraph.<String>builder().pattern(ab).pattern(bt)
                .pattern(ru).pattern(uv).pattern(vt).stock("R", 1).build();
        var solved = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> CpSatRankedFlowSolver.solve(graph, "T", 1));
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status());
        assertTrue(solved.plan().feasible(), () -> "false missing=" + solved.plan().missing());
        assertEquals(1L, solved.plan().firings().get(vt));
        assertEquals(0L, solved.plan().firings().getOrDefault(bt, 0L));
    }

    @Test void aDeadlockAfterAValidFirstFiringDoesNotHideTheLongerAlternative() {
        var ra = new CraftPattern<>("A", 1, List.of(CraftInput.of("R", 1)), "ra");
        var ab = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 2)),
                List.of(CraftOutput.of("C", 1)), "ab");
        var bc = new CraftPattern<>("C", 1, List.of(CraftInput.of("B", 2)),
                List.of(CraftOutput.of("A", 1)), "bc");
        var ca = new CraftPattern<>("A", 1, List.of(CraftInput.of("C", 2)),
                List.of(CraftOutput.of("B", 1)), "ca");
        var graph = CraftGraph.<String>builder().pattern(ra).pattern(ab).pattern(bc).pattern(ca)
                .pattern(new CraftPattern<>("U", 1, List.of(CraftInput.of("R", 1)), "ru"))
                .pattern(new CraftPattern<>("V", 1, List.of(CraftInput.of("U", 1)), "uv"))
                .pattern(new CraftPattern<>("W", 1, List.of(CraftInput.of("V", 1)), "vw"))
                .pattern(new CraftPattern<>("C", 1, List.of(CraftInput.of("W", 1)), "wc"))
                .stock("R", 1).stock("B", 1).build();
        var solved = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> CpSatRankedFlowSolver.solve(graph, "C", 1));
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status());
        assertTrue(solved.plan().feasible(), () -> "false missing=" + solved.plan().missing());
        assertEquals(1L, solved.plan().usedStock().get("R"));
    }

    @Test void keepsBothDirectionsOfAWeightedConversionCycle() {
        var ab = new CraftPattern<>("B", 9, List.of(CraftInput.of("A", 3)), "ab");
        var ba = new CraftPattern<>("A", 1, List.of(CraftInput.of("B", 3)), "ba");
        var target = new CraftPattern<>("T", 1,
                List.of(CraftInput.of("A", 2), CraftInput.of("B", 3)), "target");
        var graph = CraftGraph.<String>builder().pattern(ab).pattern(ba).pattern(target).stock("A", 3).build();
        var solved = CpSatRankedFlowSolver.solve(graph, "T", 1);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, solved.status());
        assertTrue(solved.plan().feasible());
        assertEquals(1L, solved.plan().firings().get(ab));
        assertEquals(2L, solved.plan().firings().get(ba));
    }

    @Test void amountProbesShareNativeRefinementAndVerificationBudgets() {
        var graph = CpSatExecutionBlocksTest.seedFromStock(12);
        var session = new CpSatRankedFlowSolver.PlanningSession(1, 4096, 1_000_000_000L);
        var first = CpSatRankedFlowSolver.solve(graph, "T", 1, session);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, first.status());
        assertTrue(first.plan().feasible());
        assertEquals(1, session.refinementCalls());
        assertEquals(1, session.blockSolves());
        assertEquals(0, session.learnedCuts());
        for (int q = 2; q <= 12; q++) {
            var result = CpSatRankedFlowSolver.solve(graph, "T", q, session);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
            assertEquals(1L, result.plan().missing().values().stream().mapToLong(Long::longValue).sum());
            assertTrue(result.plan().budgetExhausted());
        }
        assertEquals(1, session.refinementCalls());
        var replenished = CpSatRankedFlowSolver.solve(graph.withAdditionalStock(first.plan().missing()), "T", 12);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, replenished.status());
        assertTrue(replenished.plan().feasible());
    }

    @Test void unknownVerificationKeepsTheExecutableSupplyAndNeverLearnsAnExclusion() {
        // The set is marked, but no recipe has enough tokens. The empty-siphon theorem cannot
        // decide this case; zero verifier budget must still remain UNKNOWN, never an exclusion.
        var graph = markedDeadlock();
        var session = new CpSatRankedFlowSolver.PlanningSession(16, 0, 1_000_000_000L);
        var result = CpSatRankedFlowSolver.solve(graph, "C", 1, session);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().budgetExhausted());
        assertEquals(0, session.learnedCuts());
        assertEquals(session.blockSolves(), session.refinementCalls());
        assertTrue(CpSatRankedFlowSolver.solve(graph.withAdditionalStock(result.plan().missing()), "C", 1)
                .plan().feasible());
    }

    @Test void optionalDeadlineRestoresTheCheckpointAndKeepsTheWitness() {
        var graph = CpSatExecutionBlocksTest.seedFromStock(2);
        var session = new CpSatRankedFlowSolver.PlanningSession(16, 4096, 1L);
        var result = CpSatRankedFlowSolver.solve(graph, "T", 2, session);
        assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status());
        assertTrue(result.plan().budgetExhausted());
        assertEquals(1L, result.plan().missing().values().stream().mapToLong(Long::longValue).sum());
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test void routerCancellationDuringRefinementIsNotConvertedIntoMissing() {
        var session = new CpSatRankedFlowSolver.PlanningSession();
        var context = new com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext() {
            public long deadlineNanos() { return Long.MAX_VALUE; }
            public void report(com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot snapshot) { }
            public void checkpoint() {
                if (session.refinementCalls() > 0) {
                    throw new com.moakiee.thunderbolt.api.crafting.PlanningExitException("cancel refinement");
                }
            }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertThrows(com.moakiee.thunderbolt.api.crafting.PlanningExitException.class,
                    () -> CpSatRankedFlowSolver.solve(CpSatExecutionBlocksTest.seedFromStock(2), "T", 2, session));
        }
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test void returnedPlansAndReplenishedPlansExecuteInAnIndependentSmallNetOracle() {
        var random = new Random(901733L);
        var ab = new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 2)), List.of(CraftOutput.of("C", 1)), "ab");
        var bc = new CraftPattern<>("C", 1, List.of(CraftInput.of("B", 2)), List.of(CraftOutput.of("A", 1)), "bc");
        var ca = new CraftPattern<>("A", 1, List.of(CraftInput.of("C", 2)), List.of(CraftOutput.of("B", 1)), "ca");
        var rc = new CraftPattern<>("C", 1, List.of(CraftInput.of("R", 2)), "rc");
        var ct = new CraftPattern<>("T", 1, List.of(CraftInput.of("C", 1)), "ct");
        var patterns = List.of(ab,bc,ca,rc,ct);
        var items = List.of("A","B","C","R","T");
        long[][] pre = {{2,0,0,0,0}, {0,2,0,0,0}, {0,0,2,0,0}, {0,0,0,2,0}, {0,0,1,0,0}};
        long[][] post = {{0,1,1,0,0}, {1,0,1,0,0}, {1,1,0,0,0}, {0,0,1,0,0}, {0,0,0,0,1}};
        for (int sample = 0; sample < 50; sample++) {
            var builder = CraftGraph.<String>builder();
            patterns.forEach(builder::pattern);
            for (int i = 0; i < 4; i++) builder.stock(items.get(i), random.nextInt(3));
            var graph = builder.build();
            long amount = 1 + random.nextInt(3);
            var result = CpSatRankedFlowSolver.solve(graph, "T", amount);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, result.status(),
                    "sample=" + sample + " amount=" + amount + " stock=" + items.stream().map(graph::stock).toList());
            var replenished = CpSatRankedFlowSolver.solve(
                    graph.withAdditionalStock(result.plan().missing()), "T", amount);
            assertEquals(CpSatRankedFlowSolver.Status.SOLVED, replenished.status());
            assertTrue(replenished.plan().feasible(), "replenishment at sample=" + sample);
            for (var plan : List.of(result.plan(), replenished.plan())) {
                assertNotNull(plan);
                long[] counts = patterns.stream().mapToLong(p -> plan.firings().getOrDefault(p, 0L)).toArray();
                long[] marking = items.stream().mapToLong(i -> plan.usedStock().getOrDefault(i, 0L)
                        + plan.missing().getOrDefault(i, 0L)).toArray();
                assertTrue(PetriExecutionVerifierTest.oracle(pre, post, counts, marking, 4, amount, new HashSet<>()),
                        "non-executable returned plan at sample=" + sample);
            }
        }
    }

    private static CraftGraph<String> markedDeadlock() {
        return CraftGraph.<String>builder()
                .pattern(new CraftPattern<>("B", 1, List.of(CraftInput.of("A", 2)), List.of(CraftOutput.of("C", 1)), "ab"))
                .pattern(new CraftPattern<>("C", 1, List.of(CraftInput.of("B", 2)), List.of(CraftOutput.of("A", 1)), "bc"))
                .pattern(new CraftPattern<>("A", 1, List.of(CraftInput.of("C", 2)), List.of(CraftOutput.of("B", 1)), "ca"))
                .stock("A", 1).stock("B", 1).build();
    }
}
