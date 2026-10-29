package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.concurrent.Future;
import java.math.BigInteger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.ISubMenuHost;
import appeng.menu.AEBaseMenu;
import appeng.menu.guisync.GuiSync;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingPlanSummary;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.api.crafting.CraftingRequestMenu;
import com.moakiee.thunderbolt.api.crafting.CraftingAmountMenu;
import com.moakiee.thunderbolt.api.crafting.ExactCraftingBackend;
import com.moakiee.thunderbolt.api.crafting.ExactCraftingPlan;
import com.moakiee.thunderbolt.api.crafting.ICraftingPlanningService;
import com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports;
import com.moakiee.thunderbolt.core.storage.big.BigAmounts;
import com.moakiee.thunderbolt.ae2.crafting.ThunderboltCraftingPlanSummary;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingAlgorithmCalculationStatus;
import com.moakiee.thunderbolt.core.crafting.algorithm.menu.CraftingAlgorithmNameMenu;

/** Shared long/exact request entry and selected-algorithm tracking on AE2's native menu. */
// Data Energistics adds its long-amount entry point at priority 1000. A higher
// priority permits injecting into that merged method as well as AE2's native one.
@Mixin(value = CraftConfirmMenu.class, remap = false, priority = 1100)
public abstract class CraftConfirmMenuMixin extends AEBaseMenu
        implements CraftingAlgorithmNameMenu, CraftingRequestMenu {
    @Shadow
    private Future<ICraftingPlan> job;

    @Shadow
    private ICraftingPlan result;

    @Shadow private AEKey whatToCraft;
    @Shadow private int amount;
    @Shadow private java.util.List<appeng.helpers.IMenuCraftingPacket.AutoCraftEntry> autoCraftingQueue;
    @Shadow public abstract ISubMenuHost getHost();
    @Shadow public abstract void clearError();
    @Shadow public abstract void setPlan(CraftingPlanSummary plan);
    @Shadow private IGrid getGrid() { throw new AssertionError(); }
    @Shadow private IActionSource getActionSrc() { throw new AssertionError(); }

    @Unique private BigInteger thunderbolt$requestAmount;
    @Unique private boolean thunderbolt$planningLong;
    @Unique private ExactCraftingBackend thunderbolt$exactBackend;
    @Unique @GuiSync(30_001) private boolean thunderbolt$exactRequest;
    @Unique @GuiSync(30_002) private Component thunderbolt$requestError = Component.empty();

    protected CraftConfirmMenuMixin(MenuType<?> type, int id, Inventory inventory, Object host) {
        super(type, id, inventory, host);
    }

    @Override
    public boolean thunderbolt$planRequest(AEKey key, BigInteger requested, CalculationStrategy strategy) {
        BigAmounts.nonNegative(requested);
        if (isClientSide() || requested.signum() <= 0 || getGrid() == null) return false;
        if (requested.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
            return thunderbolt$planExact(key, requested);
        thunderbolt$requestAmount = requested;
        thunderbolt$exactRequest = false;
        thunderbolt$exactBackend = null;
        thunderbolt$requestError = Component.empty();
        setPlan(null);
        thunderbolt$planningLong = true;
        try {
            // Retain native planner selection, capture, tracking and CPU admission. The int
            // field is only a mirror; the existing service call below receives the full long.
            return ((CraftConfirmMenu) (Object) this).planJob(
                    key, requested.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValueExact(), strategy);
        } finally {
            thunderbolt$planningLong = false;
        }
    }

    @Unique
    private boolean thunderbolt$planExact(AEKey key, BigInteger requested) {
        var grid = getGrid();
        if (grid == null) return false;
        var backend = CraftingPlanningEngines.selectedExactBackend(grid);
        if (backend == null) return false;
        var selected = grid.getService(ICraftingPlanningService.class).resolve().get(0).engineId();
        CraftingAlgorithmCalculationStatus.forget(job);
        if (job != null) job.cancel(true);
        clearError();
        setPlan(null);
        result = null;
        whatToCraft = key;
        amount = requested.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValueExact();
        thunderbolt$requestAmount = requested;
        thunderbolt$exactBackend = backend;
        thunderbolt$exactRequest = true;
        thunderbolt$requestError = Component.empty();
        ICraftingSimulationRequester requester = this::getActionSrc;
        job = CraftingAlgorithmCalculationStatus.track(requester, () -> {
            CraftingAlgorithmCalculationStatus.select(requester, selected);
            return backend.plan(grid, getPlayer().level(), key, requested, getActionSrc());
        });
        return true;
    }

    @Override public boolean thunderbolt$isExactRequest() { return thunderbolt$exactRequest; }
    @Override public Component thunderbolt$getRequestError() { return thunderbolt$requestError; }

    @Inject(method = "cpuMatches", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$matchExactCpu(ICraftingCPU cpu, CallbackInfoReturnable<Boolean> cir) {
        if (thunderbolt$exactRequest)
            cir.setReturnValue(thunderbolt$exactBackend != null && thunderbolt$exactBackend.matchesCpu(cpu));
    }

    @Inject(method = "replan", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$replanExtendedRequest(CallbackInfo ci) {
        if (isClientSide() || thunderbolt$requestAmount == null) return;
        boolean planned = thunderbolt$exactRequest
                ? thunderbolt$planExact(whatToCraft, thunderbolt$requestAmount)
                : thunderbolt$planRequest(whatToCraft, thunderbolt$requestAmount, CalculationStrategy.CRAFT_LESS);
        if (!planned) ((CraftConfirmMenu) (Object) this).goBack();
        ci.cancel();
    }

    @Inject(method = "goBack", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$restoreExtendedAmount(CallbackInfo ci) {
        // AE2 owns continuation of queued crafting-list requests.
        if (autoCraftingQueue != null && !autoCraftingQueue.isEmpty()) return;
        if (thunderbolt$requestAmount != null && getPlayer() instanceof ServerPlayer player) {
            clearError();
            CraftAmountMenu.open(player, getLocator(), whatToCraft,
                    thunderbolt$requestAmount.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValueExact());
            if (player.containerMenu instanceof CraftingAmountMenu menu)
                menu.thunderbolt$setInitialAmount(thunderbolt$requestAmount);
            ci.cancel();
        }
    }

    @Inject(method = "startJob", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$blockExactPreview(CallbackInfo ci) {
        if (com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.isPreview(result)) ci.cancel();
    }

    @Unique
    @GuiSync(30_000)
    public Component thunderbolt$craftingAlgorithmName = Component.empty();

    @Inject(method = {"planJob", "data_energistics$planJob"}, at = @At("HEAD"))
    private void thunderbolt$resetAlgorithmName(
            CallbackInfoReturnable<Boolean> cir) {
        CraftingAlgorithmCalculationStatus.forget(job);
        thunderbolt$craftingAlgorithmName = Component.empty();
        if (!thunderbolt$planningLong) {
            thunderbolt$requestAmount = null;
            thunderbolt$exactRequest = false;
            thunderbolt$exactBackend = null;
            thunderbolt$requestError = Component.empty();
        }
    }

    @WrapOperation(
            method = {"planJob", "data_energistics$planJob"},
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingService;"
                            + "beginCraftingCalculation(Lnet/minecraft/world/level/Level;"
                            + "Lappeng/api/networking/crafting/ICraftingSimulationRequester;"
                            + "Lappeng/api/stacks/AEKey;J"
                            + "Lappeng/api/networking/crafting/CalculationStrategy;)"
                            + "Ljava/util/concurrent/Future;"))
    private Future<ICraftingPlan> thunderbolt$trackCalculation(
            ICraftingService service,
            Level level,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            CalculationStrategy strategy,
            Operation<Future<ICraftingPlan>> original) {
        return CraftingAlgorithmCalculationStatus.track(
                requester,
                () -> original.call(
                        service, level, requester, what,
                        thunderbolt$planningLong ? thunderbolt$requestAmount.longValueExact() : amount, strategy));
    }

    @Inject(method = "broadcastChanges", at = @At("HEAD"), remap = true)
    private void thunderbolt$syncAlgorithmName(CallbackInfo ci) {
        var selected = CraftingAlgorithmCalculationStatus.selected(job);
        if (selected != null) {
            thunderbolt$craftingAlgorithmName = CraftingPlanningEngines.getName(selected);
        }
        if (isClientSide() || job == null || !job.isDone() || job.isCancelled()) return;
        try {
            var finished = job.get();
            if (!thunderbolt$exactRequest && ExactPlanReports.isPreview(finished)
                    && selected != null && getGrid() != null
                    && CraftingPlanningEngines.isSelected(getGrid(), selected)) {
                // Only an actual long overflow can upgrade a normal request. A large-but-long
                // terminal quantity alone never changes its planner or accepted CPU family.
                thunderbolt$planExact(finished.finalOutput().what(), thunderbolt$requestAmount != null
                        ? thunderbolt$requestAmount : BigInteger.valueOf(finished.finalOutput().amount()));
            } else if (finished instanceof ExactCraftingPlan exact) {
                thunderbolt$requestError = exact.planningError();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException e) {
            // The native menu owns failed calculations.
        }
    }

    @WrapOperation(
            method = "broadcastChanges",
            at = @At(
                    value = "INVOKE",
                    remap = false,
                    target = "Lappeng/menu/me/crafting/CraftingPlanSummary;fromJob"
                            + "(Lappeng/api/networking/IGrid;"
                            + "Lappeng/api/networking/security/IActionSource;"
                            + "Lappeng/api/networking/crafting/ICraftingPlan;)"
                            + "Lappeng/menu/me/crafting/CraftingPlanSummary;"),
            remap = true)
    private CraftingPlanSummary thunderbolt$buildSummaryForSelectedEngine(
            IGrid grid,
            IActionSource source,
            ICraftingPlan result,
            Operation<CraftingPlanSummary> original) {
        if (result instanceof ExactCraftingPlan exact) return exact.summary();
        var selected = CraftingAlgorithmCalculationStatus.selected(job);
        if (!ThunderboltCraftingPlanSummary.handles(selected)
                && !com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.isPreview(result)) {
            return original.call(grid, source, result);
        }
        return ThunderboltCraftingPlanSummary.fromPlan(result);
    }

    @Inject(method = "removed", at = @At("HEAD"), remap = true)
    private void thunderbolt$forgetCalculation(Player player, CallbackInfo ci) {
        CraftingAlgorithmCalculationStatus.forget(job);
    }

    @Override
    public Component thunderbolt$getCraftingAlgorithmName() {
        return thunderbolt$craftingAlgorithmName;
    }
}
