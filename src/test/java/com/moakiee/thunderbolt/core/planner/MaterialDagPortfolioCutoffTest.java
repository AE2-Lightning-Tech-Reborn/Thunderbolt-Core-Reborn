package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import org.junit.jupiter.api.Test;

class MaterialDagPortfolioCutoffTest {
    @Test
    void partialIndependentPortfolioReportsCutoffAndTheNextAmountProbeCanFinish() {
        // Inject the cutoff in a small portfolio so runtime speed cannot preempt the injection.
        int branchCount = 128;
        var fixture = branches(branchCount);
        assertNotNull(MaterialDagReplay.tryPlan(fixture.graph(), fixture.witness(), "T", 1),
                "an independently supplied count vector proves the original inventory is sufficient");
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.refineMissing = false;
        session.optimizeFeasible = false;
        var probe = new PortfolioProbe(null);
        PlanningResult<String> interrupted;
        try (var ignored = PlanningCancellation.bind(probe)) {
            interrupted = CraftPlannerV2.planDetailed(fixture.graph(), "T", 1, session);
        }
        assertTrue(probe.triggered, "expire inside a cached witness after the first component finishes");
        assertFalse(interrupted.plan().feasible());
        assertTrue(interrupted.diagnostics().lowWidthSolved() > 0);
        assertTrue(interrupted.plan().budgetExhausted(),
                "a truncated positive-witness search cannot establish real missing stock");
        assertTrue(interrupted.diagnostics().lowWidthCutoffs() > 0);
        assertDoesNotThrow(PlanningCancellation::check, "restore the caller's scope after the local timeout");

        var ready = CraftPlannerV2.planDetailed(fixture.graph(), "T", 1, session);
        assertTrue(ready.plan().feasible(), () -> ready.diagnostics().toString());
        assertFalse(ready.plan().budgetExhausted(), "a previous probe's local cutoff is not sticky");
        assertEquals(0, ready.diagnostics().lowWidthCutoffs());
        assertEquals(branchCount - interrupted.diagnostics().lowWidthSolved(),
                ready.diagnostics().lowWidthAttempts(), "solve only the unfinished components");
        ready.plan().usedStock().forEach((key, used) -> assertTrue(used <= fixture.graph().stock(key)));

        var repeated = CraftPlannerV2.planDetailed(fixture.graph(), "T", 1, session);
        assertTrue(repeated.plan().feasible());
        assertEquals(0, repeated.diagnostics().lowWidthAttempts(),
                "a completed component proof must not spend the shared solver budget again");
    }

    @Test
    void anotherAmountRebuildsComponentProofsAndReturnsAnExecutableVector() {
        int branchCount = 128;
        var fixture = branches(branchCount);
        var additionalStock = new HashMap<String, Long>();
        additionalStock.put("raw", 3L * branchCount);
        for (int i = 0; i < branchCount; i++) {
            additionalStock.put("seed" + i, 3L);
            additionalStock.put("fuel" + i, 9L);
        }
        var graph = fixture.graph().withAdditionalStock(additionalStock);
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.refineMissing = false;
        session.optimizeFeasible = false;
        assertTrue(CraftPlannerV2.planDetailed(graph, "T", 1, session).plan().feasible());

        var larger = CraftPlannerV2.planDetailed(graph, "T", 2, session);
        assertTrue(larger.plan().feasible(), () -> larger.diagnostics().toString());
        assertEquals(branchCount, larger.diagnostics().lowWidthAttempts(),
                "different boundary demand needs a fresh proof for every component");
        assertNotNull(MaterialDagReplay.tryPlan(graph, larger.plan().firings(), "T", 2));
        for (int i = 0; i < branchCount; i++) {
            assertEquals(2L, larger.plan().firings().get(graph.patternsFor("A" + i).get(0)));
            assertEquals(3L, larger.plan().firings().get(graph.patternsFor("B" + i).get(0)));
        }

        var smaller = CraftPlannerV2.planDetailed(graph, "T", 1, session);
        assertTrue(smaller.plan().feasible());
        assertEquals(branchCount, smaller.diagnostics().lowWidthAttempts(),
                "retain only the latest boundary proof instead of accumulating amount probes");
    }

    @Test
    void callerCancellationInsideAPartialPortfolioStillPropagates() {
        var fixture = branches(128);
        var exit = new PlanningExitException("cancel inside independent material solving");
        var probe = new PortfolioProbe(exit);
        try (var ignored = PlanningCancellation.bind(probe)) {
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> CraftPlannerV2.plan(fixture.graph(), "T", 1)));
        }
        assertTrue(probe.triggered);
        assertDoesNotThrow(PlanningCancellation::check);
    }

    @Test
    void cachedComponentReuseStillPropagatesCallerCancellation() {
        var fixture = branches(128);
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.refineMissing = false;
        session.optimizeFeasible = false;
        assertTrue(CraftPlannerV2.planDetailed(fixture.graph(), "T", 1, session).plan().feasible());
        var exit = new PlanningExitException("cancel while reusing independent component proofs");
        boolean[] triggered = {false};
        var callers = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
            @Override public void checkpoint() {
                boolean reusing = callers.walk(frames -> frames.skip(2).findFirst().map(frame ->
                        frame.getDeclaringClass() == CraftPlannerV2.class
                                && frame.getMethodName().equals("tryIndependentRawStock")).orElse(false));
                if (reusing) {
                    triggered[0] = true;
                    throw exit;
                }
            }
        };
        try (var ignored = PlanningCancellation.bind(context)) {
            assertSame(exit, assertThrows(PlanningExitException.class,
                    () -> CraftPlannerV2.planDetailed(fixture.graph(), "T", 1, session)));
        }
        assertTrue(triggered[0]);
        assertDoesNotThrow(PlanningCancellation::check);
        var ready = CraftPlannerV2.planDetailed(fixture.graph(), "T", 1, session);
        assertTrue(ready.plan().feasible());
        assertEquals(0, ready.diagnostics().lowWidthAttempts());
    }

    @Test
    void cachedLocalProofsStillRequireCertificationOfSharedRawStock() {
        var fixture = branches(128, 127);
        var session = new CraftPlannerV2.PlanningSession<String>();
        session.refineMissing = false;
        session.optimizeFeasible = false;
        var first = CraftPlannerV2.planDetailed(fixture.graph(), "T", 1, session);
        assertFalse(first.plan().feasible());
        assertEquals(Map.of("raw", 1L), first.plan().missing());
        var repeated = CraftPlannerV2.planDetailed(fixture.graph(), "T", 1, session);
        assertFalse(repeated.plan().feasible());
        assertEquals(Map.of("raw", 1L), repeated.plan().missing());
        assertEquals(0, repeated.diagnostics().lowWidthAttempts());
    }

    private record Fixture(CraftGraph<String> graph, Map<CraftPattern<String>, Long> witness) {}

    private static Fixture branches(int branches) {
        return branches(branches, branches);
    }

    private static Fixture branches(int branches, long rawStock) {
        var builder = CraftGraph.<String>builder().stock("raw", rawStock);
        var inputs = new ArrayList<CraftInput<String>>();
        var witness = new IdentityHashMap<CraftPattern<String>, Long>();
        for (int i = 0; i < branches; i++) {
            String fuel = "fuel" + i, seed = "seed" + i, side = "S" + i, a = "A" + i, b = "B" + i;
            var supply = new CraftPattern<>(b, 1,
                    List.of(CraftInput.of("raw", 1), CraftInput.of(fuel, 1)),
                    List.of(CraftOutput.of(side, 1)), null);
            var consumer = new CraftPattern<>(a, 2,
                    List.of(CraftInput.of(seed, 1), CraftInput.of(side, 2)),
                    List.of(CraftOutput.of(b, 1)), null);
            builder.stock(fuel, 3).stock(seed, 1).stock(side, 1).stock(b, 4)
                    .pattern(supply).pattern(consumer);
            witness.put(supply, 1L);
            witness.put(consumer, 1L);
            inputs.add(CraftInput.of(a, 2));
            inputs.add(CraftInput.of(b, 2));
        }
        var target = new CraftPattern<>("T", 1, inputs, null);
        witness.put(target, 1L);
        return new Fixture(builder.pattern(target).build(), witness);
    }

    /** Expire the actual optional scope at a known point; no sleeps or reduced test budget. */
    private static final class PortfolioProbe implements PlanningAttemptContext {
        private final ThreadLocal<Long> optionalDeadline;
        private final StackWalker callers = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        private final PlanningExitException exit;
        boolean triggered;

        @SuppressWarnings("unchecked")
        PortfolioProbe(PlanningExitException exit) {
            this.exit = exit;
            try {
                var field = PlanningCancellation.class.getDeclaredField("OPTIONAL_DEADLINE");
                field.setAccessible(true);
                optionalDeadline = (ThreadLocal<Long>) field.get(null);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override public long deadlineNanos() { return Long.MAX_VALUE; }
        @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
        @Override public void checkpoint() {
            if (triggered || optionalDeadline.get() == null) return;
            boolean cachedWitness = callers.walk(frames -> {
                var iterator = frames.skip(2).iterator();
                if (!iterator.hasNext()
                        || iterator.next().getDeclaringClass() != BoundedIntegerLinearSolver.WorkBudget.class)
                    return false;
                boolean witness = false, portfolio = false;
                while (iterator.hasNext()) {
                    var frame = iterator.next();
                    if (frame.getDeclaringClass() == BoundedIntegerLinearSolver.class
                            && frame.getMethodName().equals("solve")) return false;
                    witness |= frame.getDeclaringClass() == BoundedIntegerLinearSolver.FeasibleWitness.class;
                    portfolio |= frame.getDeclaringClass() == CraftPlannerV2.class
                            && frame.getMethodName().equals("tryIndependentRawStock");
                }
                return witness && portfolio;
            });
            if (!cachedWitness) return;
            triggered = true;
            if (exit != null) throw exit;
            optionalDeadline.set(System.nanoTime() - 1L);
        }
    }
}
