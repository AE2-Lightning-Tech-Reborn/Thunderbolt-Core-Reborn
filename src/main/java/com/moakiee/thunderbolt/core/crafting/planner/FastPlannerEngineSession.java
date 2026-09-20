package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;

import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;
import com.moakiee.thunderbolt.core.crafting.pattern.CraftingStockPolicy;

/**
 * Shared session lifecycle for the built-in graph planners. Each calculation owns its probe state
 * and metadata; third-party engines remain free to implement {@link PlanningEngineSession} directly.
 */
final class FastPlannerEngineSession implements PlanningEngineSession {
    private final Probe probe;
    private final Map<CraftingPlan, Map<ReusableStockUsageKey<AEKey>, Long>> reusableStock =
            new IdentityHashMap<>();

    FastPlannerEngineSession(
            PlanningRequest request, FastCraftingPlanner.CalculationSession graphSession) {
        this((amount, simulate) -> FastCraftingPlanner.tryAttempt(
                request.craftingService(), request.networkInventory(), request.level(),
                request.output(), amount, simulate,
                request.requester() instanceof CraftingStockPolicy policy ? policy : null,
                graphSession));
    }

    FastPlannerEngineSession(Probe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    @Override
    public PlanningAttempt attempt(long amount, boolean simulate, PlanningAttemptContext context) {
        final FastCraftingPlanner.FastAttempt result;
        try {
            context.checkpoint();
            result = probe.attempt(amount, simulate);
        } catch (PlanningExitException exit) {
            return PlanningAttempt.DECLINE;
        }
        if (!result.handled()) {
            return PlanningAttempt.DECLINE;
        }
        if (result.plan() != null) {
            reusableStock.put(result.plan(), result.usedReusableStock());
        }
        if (result.simulationFallback() != null) {
            reusableStock.put(result.simulationFallback(), result.usedReusableStock());
        }
        return new PlanningAttempt(
                PlanningAttempt.Status.HANDLED, result.plan(), result.simulationFallback());
    }

    @Override
    public ICraftingPlan finish(ICraftingPlan result, PlanningAttemptContext context) {
        context.report(PlanningDiagnosticSnapshot.phase("finishing"));
        if (result instanceof CraftingPlan craftingPlan) {
            var used = reusableStock.get(craftingPlan);
            if (used != null) {
                PlanningMetadataStore.record(craftingPlan, used);
            }
        }
        return result;
    }

    @Override
    public void close() {
        reusableStock.clear();
    }

    @FunctionalInterface
    interface Probe {
        FastCraftingPlanner.FastAttempt attempt(long amount, boolean simulate);
    }
}
