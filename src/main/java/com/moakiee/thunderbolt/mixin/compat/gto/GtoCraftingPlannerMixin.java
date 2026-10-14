package com.moakiee.thunderbolt.mixin.compat.gto;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.compat.gto.GtoPlanningCalculation;

/** Wrap the final GTO service body; injection priority must exceed its priority-1000 overwrite. */
@Mixin(value = CraftingService.class, remap = false, priority = 1001)
public abstract class GtoCraftingPlannerMixin {
    @Shadow @Final private IGrid grid;

    @WrapOperation(method = "beginCraftingCalculation", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;"))
    private Future<ICraftingPlan> thunderbolt$attachGtoPlanner(ExecutorService executor,
            Callable<ICraftingPlan> nativeCalculation, Operation<Future<ICraftingPlan>> original,
            Level level, ICraftingSimulationRequester requester, AEKey output, long amount,
            CalculationStrategy strategy) {
        return original.call(executor, GtoPlanningCalculation.capture(
                grid, level, requester, output, amount, strategy, nativeCalculation));
    }
}
