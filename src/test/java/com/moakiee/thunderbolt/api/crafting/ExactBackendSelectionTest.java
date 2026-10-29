package com.moakiee.thunderbolt.api.crafting;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ExactBackendSelectionTest {
    @Test
    void exactBackendRequiresTheFirstSelectedAlgorithmAndLiveCapability() {
        var id = new ResourceLocation("thunderbolt_test", "exact_" + UUID.randomUUID());
        var engine = new CraftingPlanningEngine() {
            public ResourceLocation id() { return id; }
            public boolean check(IGrid grid, PlanningRequest request) { return true; }
            public PlanningEngineSession createSession(PlanningRequest request, Object input,
                    PlanningAttemptContext context) { return null; }
        };
        var available = new boolean[]{true};
        var backend = new ExactCraftingBackend() {
            public boolean isAvailable(IGrid grid) { return available[0]; }
            public boolean matchesCpu(ICraftingCPU cpu) { return false; }
            public Future<ICraftingPlan> plan(IGrid grid, Level level, AEKey target,
                    BigInteger amount, IActionSource source) { throw new AssertionError(); }
        };
        CraftingPlanningEngines.register(engine, 0, false);
        CraftingPlanningEngines.registerExactBackend(id, backend);
        CraftingPlanningEngines.registerExactBackend(id, backend);
        var choice = PlanningChoice.engine(id);
        assertSame(backend, CraftingPlanningEngines.selectedExactBackend(grid(List.of(choice))));
        assertTrue(CraftingPlanningEngines.isSelected(grid(List.of(choice)), id));
        available[0] = false;
        assertNull(CraftingPlanningEngines.selectedExactBackend(grid(List.of(choice))));
        available[0] = true;
        assertNull(CraftingPlanningEngines.selectedExactBackend(grid(List.of())));
        // A later exact-capable algorithm must never override the preferred native planner.
        var nativeChoice = PlanningChoice.engine(CraftingPlanningEngines.VANILLA_ID);
        assertNull(CraftingPlanningEngines.selectedExactBackend(grid(List.of(nativeChoice, choice))));
        assertFalse(CraftingPlanningEngines.isSelected(grid(List.of(nativeChoice, choice)), id));
        assertThrows(IllegalArgumentException.class, () -> CraftingPlanningEngines.registerExactBackend(
                new ResourceLocation("thunderbolt_test", "unknown"), backend));
    }

    private static IGrid grid(List<PlanningChoice> choices) {
        var service = (ICraftingPlanningService) Proxy.newProxyInstance(
                ExactBackendSelectionTest.class.getClassLoader(), new Class<?>[]{ICraftingPlanningService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("resolve")) return choices;
                    throw new AssertionError(method);
                });
        return (IGrid) Proxy.newProxyInstance(ExactBackendSelectionTest.class.getClassLoader(),
                new Class<?>[]{IGrid.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getService") && args[0] == ICraftingPlanningService.class) return service;
                    throw new AssertionError(method);
                });
    }
}
