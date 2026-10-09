package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import com.moakiee.thunderbolt.core.crafting.batch.BatchExecutor;
import com.moakiee.thunderbolt.core.crafting.batch.BatchCpuAccounting;
import com.moakiee.thunderbolt.core.crafting.batch.BatchProviderFilterIterable;
import com.moakiee.thunderbolt.core.crafting.batch.DefaultBatchJobView;
import com.moakiee.thunderbolt.core.crafting.batch.TickProviderDispatchSchedule;

/**
 * Batches identical native pattern firings within a tick. Fuzzy extraction resolves against the
 * live CPU inventory through {@code ParallelBatchCpuHelper}; planned keys do not fix substitutes.
 */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicBatchMixin {
    @Shadow
    private ExecutingCraftingJob job;

    @Shadow
    @Final
    CraftingCPUCluster cluster;

    @Shadow
    public abstract ListCraftingInventory getInventory();

    @Unique
    @Nullable
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> thunderbolt$batchedByTask;

    @Unique
    private long thunderbolt$batchTick;

    @Unique
    private boolean thunderbolt$batchExhaustedThisTick;

    @Unique
    private TickProviderDispatchSchedule thunderbolt$dispatchSchedule;

    @WrapOperation(
            method = "tickCraftingLogic",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/crafting/execution/CraftingCpuLogic;executeCrafting"
                            + "(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;"
                            + "Lnet/minecraft/world/level/Level;)I"
            )
    )
    private int thunderbolt$wrapExecuteCrafting(CraftingCpuLogic self,
                                          int remainingOps,
                                          CraftingService craftingService,
                                          IEnergyService energyService,
                                          Level level,
                                          Operation<Integer> original) {
        long now = TickHandler.instance().getCurrentTick();
        if (thunderbolt$dispatchSchedule == null) {
            thunderbolt$dispatchSchedule = new TickProviderDispatchSchedule();
        }
        thunderbolt$dispatchSchedule.beginTick(now);
        var batchedByTask = thunderbolt$getBatchedByTask();
        if (now != thunderbolt$batchTick) {
            thunderbolt$batchTick = now;
            batchedByTask.clear();
            thunderbolt$batchExhaustedThisTick = false;
        }

        if (job == null || thunderbolt$batchExhaustedThisTick) {
            return original.call(self, remainingOps, craftingService, energyService, level);
        }

        var jobAccessor = (ExecutingCraftingJobAccessor) job;
        var timeTracker = (ElapsedTimeTrackerAccessor) jobAccessor.thunderbolt$getTimeTracker();
        var batchResult = BatchExecutor.runBatchOnly(
                remainingOps,
                BatchCpuAccounting.Mode.LINEAR,
                craftingService,
                energyService,
                new DefaultBatchJobView(
                        level,
                        jobAccessor.thunderbolt$getLink().getCraftingID(),
                        jobAccessor.thunderbolt$getTasks(),
                        jobAccessor.thunderbolt$getWaitingFor(),
                        task -> ((TaskProgressAccessor) task).thunderbolt$getValue(),
                        (task, value) -> ((TaskProgressAccessor) task).thunderbolt$setValue(value),
                        timeTracker,
                        (tracker, count, type) -> ((ElapsedTimeTrackerAccessor) tracker)
                                .thunderbolt$invokeAddMaxItems(count, type)).onDispatchFailure(() -> {
                                    jobAccessor.thunderbolt$getLink().cancel();
                                    cluster.markDirty();
                                }),
                getInventory(),
                batchedByTask,
                cluster::markDirty,
                Map.of(), Integer.MAX_VALUE, Long.MAX_VALUE, false,
                thunderbolt$dispatchSchedule);

        if (jobAccessor.thunderbolt$getLink().isCanceled()) return remainingOps;
        if (batchResult.dispatchedCopies() > 0) {
            // Vanilla CPUs keep batch extraction/provider dispatch, but pay one operation per copy.
            // UNBOUNDED providers (such as creative item sources) still pay one operation per dispatch.
            return batchResult.consumedCpuOps();
        }

        // No batch-dispatchable task this tick (no batch provider / all full / out of material).
        // Game time is frozen within a tick, so capacity cannot recover; skip the per-round re-probe.
        thunderbolt$batchExhaustedThisTick = true;
        return original.call(self, remainingOps, craftingService, energyService, level);
    }

    @WrapOperation(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/me/service/CraftingService;getProviders"
                            + "(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;"
            )
    )
    private Iterable<ICraftingProvider> thunderbolt$filterBatched(CraftingService craftingService,
                                                            IPatternDetails details,
                                                            Operation<Iterable<ICraftingProvider>> original) {
        var raw = original.call(craftingService, details);
        var batchedByTask = thunderbolt$getBatchedByTask();
        if (batchedByTask.isEmpty()) return raw;
        var perTask = batchedByTask.get(details);
        if (perTask == null || perTask.isEmpty()) return raw;
        return new BatchProviderFilterIterable(raw, perTask);
    }

    @Unique
    private Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> thunderbolt$getBatchedByTask() {
        if (this.thunderbolt$batchedByTask == null) {
            this.thunderbolt$batchedByTask = new HashMap<>();
        }
        return this.thunderbolt$batchedByTask;
    }
}
