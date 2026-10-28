package com.moakiee.thunderbolt.compat.gto;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;
import net.minecraft.world.level.Level;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.ae2.crafting.CapturedPlanningChoice;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports;
import com.moakiee.thunderbolt.api.crafting.*;
import com.moakiee.thunderbolt.compat.gto.inv.NetworkCraftingSimulationState;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingAlgorithmCalculationStatus;
import com.moakiee.thunderbolt.core.crafting.algorithm.PlanningCandidateDeclinedException;
import com.moakiee.thunderbolt.core.crafting.algorithm.PlanningCandidateExecutor;
import com.moakiee.thunderbolt.core.crafting.algorithm.PlanningFailurePlans;
import com.moakiee.thunderbolt.core.crafting.plan.LoopCraftingPlan;
import com.moakiee.thunderbolt.core.crafting.planner.PlanningMetadataStore;

/**
 * Runs TB candidates on GTO's existing calculation worker and retains the original GTO task as
 * the native fallback. Neither GTO's planner classes nor its protected implementation are linked.
 * Capture runs synchronously before submission; workers only read the captured inventory.
 */
public final class GtoPlanningCalculation implements Callable<ICraftingPlan> {
    private final Callable<ICraftingPlan> nativeCalculation;
    private final PlanningRequest request;
    private final ICraftingSimulationRequester requester;
    private final List<CapturedPlanningChoice> candidates;

    private GtoPlanningCalculation(Callable<ICraftingPlan> nativeCalculation, PlanningRequest request,
            ICraftingSimulationRequester requester, List<CapturedPlanningChoice> candidates) {
        this.nativeCalculation = nativeCalculation;
        this.request = request;
        this.requester = requester;
        this.candidates = candidates;
    }

    public static GtoPlanningCalculation capture(IGrid grid, Level level,
            ICraftingSimulationRequester requester, AEKey output, long amount,
            CalculationStrategy strategy, Callable<ICraftingPlan> nativeCalculation) {
        var choices = grid.getService(ICraftingPlanningService.class).resolve();
        PlanningRequest request = null;
        var captured = new ArrayList<CapturedPlanningChoice>();
        for (var choice : choices) {
            if (choice.kind() == PlanningChoice.Kind.VANILLA) {
                captured.add(CapturedPlanningChoice.vanilla());
                break;
            }
            var engine = CraftingPlanningEngines.get(choice.engineId());
            if (engine == null) continue;
            if (request == null) {
                request = new PlanningRequest(level, grid.getCraftingService(),
                        new NetworkCraftingSimulationState(grid.getStorageService(), requester.getActionSource()),
                        output, amount, strategy, requester);
            }
            try {
                if (engine.check(grid, request)) {
                    captured.add(new CapturedPlanningChoice(choice, engine, engine.capture(grid, request)));
                }
            } catch (RuntimeException failure) {
                ThunderboltCore.LOGGER.warn("GTO planning candidate capture failed: {}", choice.engineId(), failure);
            }
        }
        if (captured.isEmpty() || captured.get(captured.size() - 1).choice().kind() != PlanningChoice.Kind.VANILLA) {
            captured.add(CapturedPlanningChoice.vanilla());
        }
        return new GtoPlanningCalculation(nativeCalculation, request, requester, List.copyOf(captured));
    }

    @Override
    public ICraftingPlan call() throws Exception {
        for (var candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("GTO calculation cancelled");
            if (candidate.choice().kind() == PlanningChoice.Kind.VANILLA) {
                CraftingAlgorithmCalculationStatus.select(requester, CraftingPlanningEngines.VANILLA_ID);
                // Return this exact object: GTO attaches allocation and primary-output metadata to it.
                return nativeCalculation.call();
            }
            var engineId = candidate.choice().engineId();
            try {
                var execution = PlanningCandidateExecutor.execute(engineId,
                        "GTO output=" + request.output() + " requested=" + request.requestedAmount(),
                        context -> runCandidate(candidate, context),
                        () -> Thread.sleep(1), GtoPlanningCalculation::discard);
                if (execution.status() != PlanningCandidateExecutor.Status.SUCCESS) {
                    if (execution.failure() instanceof Error error) throw error;
                    // A budget interrupt belongs to this candidate; the executor propagates
                    // cancellation of the owning calculation separately as InterruptedException.
                    if (execution.status() == PlanningCandidateExecutor.Status.FAILED
                            && execution.failure() instanceof CancellationException cancelled) throw cancelled;
                    continue;
                }
                ICraftingPlan result = execution.value();
                if (result == null) continue;
                CraftingAlgorithmCalculationStatus.select(requester, engineId);
                if (result instanceof CraftingPlan plan && !ExactPlanReports.isPreview(result)) {
                    return LoopCraftingPlan.wrapIfNeeded(plan, PlanningMetadataStore.take(plan));
                }
                return result;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                ThunderboltCore.LOGGER.warn("GTO planning candidate failed; trying next: {}", engineId, failure);
            }
        }
        CraftingAlgorithmCalculationStatus.select(requester, CraftingPlanningEngines.ALL_FAILED_ID);
        return PlanningFailurePlans.allFailed(request.output(), request.requestedAmount());
    }

    private ICraftingPlan runCandidate(CapturedPlanningChoice candidate, PlanningAttemptContext context) {
        context.report(PlanningDiagnosticSnapshot.phase("creating_session"));
        context.checkpoint();
        var session = candidate.engine().createSession(request, candidate.capturedInput(), context);
        if (session == null) throw new PlanningCandidateDeclinedException();
        try (session) {
            PlanningAttempt first = probe(session, context, false, request.requestedAmount());
            CraftingPlan result = first.plan();
            if (result == null && request.strategy() == CalculationStrategy.CRAFT_LESS && !expired(context)) {
                long crafted = 0;
                for (long bit = Long.highestOneBit(request.requestedAmount()); bit > 0; bit /= 2) {
                    long amount = crafted + bit;
                    if (amount >= request.requestedAmount()) continue;
                    var attempt = probe(session, context, false, amount);
                    if (attempt.plan() != null) {
                        crafted = amount;
                        result = attempt.plan();
                        if (expired(context)) break;
                    }
                }
            }
            if (result == null) {
                result = first.simulationFallback() != null ? first.simulationFallback()
                        : probe(session, context, true, request.requestedAmount()).plan();
            }
            if (result == null) throw new PlanningCandidateDeclinedException();
            context.report(PlanningDiagnosticSnapshot.phase("finishing"));
            ICraftingPlan finished = session.finish(result, context);
            if (finished == null) throw new PlanningCandidateDeclinedException();
            return finished;
        }
    }

    private static PlanningAttempt probe(PlanningEngineSession session, PlanningAttemptContext context,
            boolean simulate, long amount) {
        PlanningAttempt attempt = session.attempt(amount, simulate, context);
        if (attempt.status() == PlanningAttempt.Status.DECLINE) throw new PlanningCandidateDeclinedException();
        if (attempt.plan() == null && attempt.simulationFallback() == null) {
            if (expired(context)) throw new PlanningCandidateDeclinedException();
            context.checkpoint();
        }
        return attempt;
    }

    private static boolean expired(PlanningAttemptContext context) {
        return context.deadlineNanos() != Long.MAX_VALUE && System.nanoTime() - context.deadlineNanos() >= 0;
    }

    private static void discard(ICraftingPlan plan) {
        if (plan instanceof CraftingPlan concrete) PlanningMetadataStore.take(concrete);
    }
}
