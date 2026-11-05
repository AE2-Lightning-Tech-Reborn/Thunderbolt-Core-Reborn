package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.moakiee.thunderbolt.compat.gto.inv.CraftingSimulationState;
import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;

class FastPlannerUnknownOutcomeTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.ensureInitialized(); }

    @Test void admittedGraphCutoffCannotBecomeAnAuthoritativeNegativeAttempt() throws Exception {
        AEKey output = AEItemKey.of(Items.IRON_INGOT);
        AEKey raw = AEItemKey.of(Items.IRON_ORE);
        int width = 65_537;
        var graph = CraftGraph.<AEKey>builder().stock(raw, width)
                .pattern(output, 1, Collections.nCopies(width, CraftInput.of(raw, 1))).build();
        // Independent one-recipe inventory replay: all inputs are present, so one firing works.
        long available = graph.stock(raw);
        for (var input : graph.patternsFor(output).getFirst().inputs()) available -= input.amount();
        assertEquals(0, available);
        var limited = CraftPlannerV2.planDetailed(graph, output, 1).plan();
        assertFalse(limited.feasible());
        assertTrue(limited.budgetExhausted(), "the real graph admission limit must be reached");
        for (boolean simulate : new boolean[] {false, true}) {
            var session = precompiled(graph);
            var attempt = FastCraftingPlanner.tryAttempt(null, inventory(raw, width), null,
                    output, 1, simulate, null, session);
            assertFalse(attempt.handled(), "graph cutoff is UNKNOWN, not infeasible; simulate=" + simulate);
            assertNull(attempt.plan());
            assertNull(attempt.simulationFallback());
        }
    }

    @Test void completedMissingProofStillProducesTheNormalSimulationFallback() throws Exception {
        AEKey output = AEItemKey.of(Items.IRON_INGOT);
        var graph = CraftGraph.<AEKey>builder().build();
        var attempt = FastCraftingPlanner.tryAttempt(null, inventory(output, 0), null,
                output, 1, false, null, precompiled(graph));
        assertTrue(attempt.handled());
        assertNull(attempt.plan());
        assertNotNull(attempt.simulationFallback());
        assertEquals(1, attempt.simulationFallback().missingItems().get(output));
    }

    @Test void graphExportCutoffDeclinesInsteadOfCachingAnEmptyGraph() {
        AEKey output = AEItemKey.of(Items.IRON_INGOT);
        AEKey raw = AEItemKey.of(Items.IRON_ORE);
        var input = new IPatternDetails.IInput() {
            @Override public GenericStack[] getPossibleInputs() { return new GenericStack[] {new GenericStack(raw, 1)}; }
            @Override public long getMultiplier() { return 1; }
            @Override public boolean isValid(AEKey key, Level level) { return key.equals(raw); }
            @Override public AEKey getRemainingKey(AEKey key) { return null; }
        };
        var slots = new IPatternDetails.IInput[65_537];
        java.util.Arrays.fill(slots, input);
        var pattern = new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return null; }
            @Override public IInput[] getInputs() { return slots; }
            @Override public GenericStack[] getOutputs() { return new GenericStack[] {new GenericStack(output, 1)}; }
        };
        var service = (ICraftingService) Proxy.newProxyInstance(ICraftingService.class.getClassLoader(),
                new Class<?>[] {ICraftingService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCraftingFor" -> output.equals(args[0]) ? List.of(pattern) : List.of();
                    case "getCraftables" -> Set.of(output);
                    case "canEmitFor" -> false;
                    case "getFuzzyCraftable" -> null;
                    default -> throw new AssertionError("unexpected service call: " + method.getName());
                });
        var context = new PlanningAttemptContext() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public void checkpoint() {}
            @Override public void report(PlanningDiagnosticSnapshot snapshot) {}
        };
        var state = inventory(raw, slots.length);
        var calculation = new FastCraftingPlanner.CalculationSession();
        try (var session = new FastPlannerEngineSession((amount, simulate) -> FastCraftingPlanner.tryAttempt(
                service, state, null, output, amount, simulate, null, calculation))) {
            assertSame(PlanningAttempt.DECLINE, session.attempt(1, false, context));
            assertSame(PlanningAttempt.DECLINE, session.attempt(1, true, context));
        }
    }

    @Test void exhaustedRecoveryAllowanceDeclinesRepeatedNormalAndPreviewProbes() throws Exception {
        AEKey target = AEItemKey.of(Items.IRON_INGOT);
        AEKey a = AEItemKey.of(Items.IRON_ORE);
        AEKey b = AEItemKey.of(Items.COAL);
        var builder = CraftGraph.<AEKey>builder().stock(a, 5).stock(b, 14);
        long[] routes = {1,3,3, 2,3,2, 9,1,5, 12,5,4, 4,1,2, 3,4,4, 10,6,6, 8,2,5};
        for (int i = 0; i < routes.length; i += 3) {
            long count = routes[i], aCount = routes[i + 1], bCount = routes[i + 2];
            var source = new IPatternDetails() {
                @Override public AEItemKey getDefinition() { return null; }
                @Override public IInput[] getInputs() {
                    return new IInput[] {strictInput(a, aCount), strictInput(b, bCount)};
                }
                @Override public GenericStack[] getOutputs() {
                    return new GenericStack[] {new GenericStack(target, count)};
                }
            };
            builder.pattern(new CraftPattern<>(target, count,
                    List.of(CraftInput.of(a, aCount), CraftInput.of(b, bCount)), source));
        }
        var calculation = precompiled(builder.build());
        var field = FastCraftingPlanner.CalculationSession.class.getDeclaredField("plannerSession");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var session = (CraftPlannerV2.PlanningSession<AEKey>) field.get(calculation);
        session.conservativeSearchNanos = SmallConservativeSearch.MAX_NANOS;
        var state = new CraftingSimulationState() {
            @Override protected long simulateExtractParent(AEKey key, long amount) {
                return Math.min(amount, key.equals(a) ? 5 : key.equals(b) ? 14 : 0);
            }
            @Override protected Iterable<AEKey> findFuzzyParent(AEKey key) { return List.of(a, b); }
        };
        for (boolean simulate : new boolean[] {false, true, false}) {
            var attempt = FastCraftingPlanner.tryAttempt(null, state, null,
                    target, 23, simulate, null, calculation);
            assertFalse(attempt.handled(), "exhausted same-session recovery is UNKNOWN; simulate=" + simulate);
            assertNull(attempt.plan());
            assertNull(attempt.simulationFallback());
        }
    }

    private static IPatternDetails.IInput strictInput(AEKey key, long amount) {
        return new IPatternDetails.IInput() {
            @Override public GenericStack[] getPossibleInputs() {
                return new GenericStack[] {new GenericStack(key, amount)};
            }
            @Override public long getMultiplier() { return 1; }
            @Override public boolean isValid(AEKey candidate, Level level) { return key.equals(candidate); }
            @Override public AEKey getRemainingKey(AEKey candidate) { return null; }
        };
    }

    private static FastCraftingPlanner.CalculationSession precompiled(CraftGraph<AEKey> graph) throws Exception {
        var session = new FastCraftingPlanner.CalculationSession();
        var field = FastCraftingPlanner.CalculationSession.class.getDeclaredField("compiledGraph");
        field.setAccessible(true);
        Constructor<?> constructor = field.getType().getDeclaredConstructor(
                CraftGraph.class, boolean.class, Map.class, Map.class, Set.class);
        constructor.setAccessible(true);
        field.set(session, constructor.newInstance(graph, false, Map.of(), Map.of(), Set.of()));
        return session;
    }

    private static CraftingSimulationState inventory(AEKey raw, long stock) {
        return new CraftingSimulationState() {
            @Override protected long simulateExtractParent(AEKey key, long amount) {
                return key.equals(raw) ? Math.min(stock, amount) : 0;
            }
            @Override protected Iterable<AEKey> findFuzzyParent(AEKey key) { return List.of(raw); }
        };
    }
}
