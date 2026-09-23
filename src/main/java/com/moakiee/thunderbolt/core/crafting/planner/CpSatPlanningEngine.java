package com.moakiee.thunderbolt.core.crafting.planner;

import java.nio.file.Path;

import appeng.api.networking.IGrid;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;

/** Independent full-graph OR-Tools CP-SAT planning engine. */
public final class CpSatPlanningEngine implements CraftingPlanningEngine {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(
            ThunderboltCore.MODID, "cp_sat");
    public static final CpSatPlanningEngine INSTANCE = new CpSatPlanningEngine();

    private CpSatPlanningEngine() {
    }

    /** Downloads (when absent), verifies, and loads the matching optional native runtime. */
    public boolean initialize(Path cacheRoot) {
        return CpSatIntegerLinearSolver.installRuntime(cacheRoot);
    }

    public boolean isAvailable() {
        return CpSatIntegerLinearSolver.isAvailable();
    }

    @Nullable
    public Throwable availabilityFailure() {
        return CpSatIntegerLinearSolver.loadFailure();
    }

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public Component getName() {
        return Component.translatable("algorithm.thunderbolt.cp_sat");
    }

    @Override
    public boolean check(IGrid grid, PlanningRequest request) {
        return isAvailable()
                && request.requestedAmount() > 0
                && request.output() != null
                && request.requester().getGridNode() != null
                && request.requester().getGridNode().getGrid() == grid;
    }

    @Override
    public PlanningEngineSession createSession(
            PlanningRequest request,
            @Nullable Object capturedInput,
            PlanningAttemptContext context) {
        return isAvailable()
                ? new FastPlannerEngineSession(request, FastCraftingPlanner.CalculationSession.cpSat())
                : null;
    }
}
