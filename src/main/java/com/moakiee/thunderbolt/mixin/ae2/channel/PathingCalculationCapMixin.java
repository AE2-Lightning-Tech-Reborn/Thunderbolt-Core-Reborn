package com.moakiee.thunderbolt.mixin.ae2.channel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.channel.BorrowedCapacityCalculator;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.core.channel.HighCapacityChannelSupport;
import com.moakiee.thunderbolt.core.channel.HighCapacitySubtreeNode;
import com.moakiee.thunderbolt.config.ThunderboltCommonConfig;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import appeng.me.pathfinding.IPathItem;
import appeng.me.pathfinding.PathingCalculation;

/**
 * Builds AE2's controller routing tree without assigning channels, then applies max-flow results.
 * Finite controller networks follow the configured policy; ad-hoc and infinite networks keep AE2 routing.
 */
// AE2 classes have no obfuscation mappings in the Forge dev environment — remap must be off.
@Mixin(value = PathingCalculation.class, remap = false)
public abstract class PathingCalculationCapMixin {

    @Shadow @Final private IGrid grid;
    @Shadow @Final private Set<IPathItem> visited;
    @Shadow @Final private Queue<IPathItem>[] queues;
    @Shadow @Final private Set<GridNode> channelNodes;
    @Shadow @Final private Set<GridNode> multiblocksWithChannel;

    @Unique private List<IGridNode> thunderbolt$capacitySources;
    @Unique private boolean thunderbolt$useMaxFlow;
    @Unique private BorrowedCapacityCalculator.Result thunderbolt$flowResult;
    // -1 = not applicable, fall through to vanilla channelsInUse
    @Unique private int thunderbolt$maxFlowChannelsInUse;

    // Phase 1: constructor – identify & unify high-capacity controllers
    @Inject(method = "<init>", at = @At("TAIL"))
    private void thunderbolt$unifyCapacitySources(IGrid grid, CallbackInfo ci) {
        thunderbolt$maxFlowChannelsInUse = -1;
        var allControllers = HighCapacityChannelSupport.getAllControllerNodes(grid);

        List<IGridNode> capacitySources = new ArrayList<>();
        for (var node : allControllers) {
            if (ChannelSourceRegistry.isChannelSource(node.getOwner())) {
                capacitySources.add(node);
            }
        }

        thunderbolt$capacitySources = capacitySources;
        boolean hasControllers = !allControllers.isEmpty();
        var channelMode = grid.getPathingService().getChannelMode();
        thunderbolt$useMaxFlow = channelMode != ChannelMode.INFINITE
                && ThunderboltCommonConfig.useMaxFlow(grid, hasControllers);

        // Keep AE2's multi-root tree intact whenever its allocator owns channel assignment.
        if (!thunderbolt$useMaxFlow || capacitySources.size() <= 1) {
            return;
        }

        IGridNode source = capacitySources.get(0);
        Set<IGridNode> nonSource = new ReferenceOpenHashSet<>(capacitySources.subList(1, capacitySources.size()));

        for (var node : nonSource) {
            if (node instanceof IPathItem p) {
                visited.remove(p);
            }
        }

        Queue<IPathItem> q0 = queues[0];
        var keep = new ArrayDeque<IPathItem>();
        while (!q0.isEmpty()) {
            var item = q0.poll();
            if (item instanceof GridConnection gc
                    && (nonSource.contains(gc.a()) || nonSource.contains(gc.b()))) {
                visited.remove((IPathItem) gc);
                gc.setControllerRoute(null);
                continue;
            }
            keep.add(item);
        }
        q0.addAll(keep);

        // Traverse vanilla controllers too: they can connect separate capacity sources.
        Queue<IGridNode> bfs = new ArrayDeque<>();
        Set<IGridNode> bfsVisited = new ReferenceOpenHashSet<>();
        bfs.add(source);
        bfsVisited.add(source);
        while (!bfs.isEmpty()) {
            var cur = bfs.poll();
            for (var conn : cur.getConnections()) {
                if (!(conn instanceof GridConnection gc)) continue;
                var neighbor = gc.getOtherSide(cur);
                if (!bfsVisited.add(neighbor)) continue;
                if (!(neighbor.getOwner() instanceof ControllerBlockEntity)) continue;
                if (nonSource.remove(neighbor)) {
                    if (!visited.contains((IPathItem) gc)) {
                        gc.setControllerRoute((IPathItem) cur);
                        visited.add((IPathItem) gc);
                        q0.add((IPathItem) gc);
                    }
                }
                bfs.add(neighbor);
            }
        }
    }

    // Phase 2: skip AE2 channel assignment for ALL devices
    @Inject(method = "tryUseChannel", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$skipAllDevices(GridNode node, CallbackInfoReturnable<Boolean> cir) {
        if (thunderbolt$useMaxFlow) {
            cir.setReturnValue(false);
        }
    }

    // Publish flow data for GridNode.propagateChannelsUpwards during AE2 propagation.

    @Inject(method = "compute",
            at = @At(value = "INVOKE",
                     target = "Lappeng/me/pathfinding/PathingCalculation;propagateAssignments()V"))
    private void thunderbolt$runMaxFlowBeforeDFS(CallbackInfo ci) {
        BorrowedCapacityCalculator.clearActiveData();

        if (!thunderbolt$useMaxFlow) {
            return;
        }

        thunderbolt$flowResult = BorrowedCapacityCalculator.assignChannels(grid, thunderbolt$capacitySources);
        if (thunderbolt$flowResult == null) {
            return;
        }

        channelNodes.addAll(thunderbolt$flowResult.channelNodes());

        for (var winner : thunderbolt$flowResult.channelNodes()) {
            if (!winner.hasFlag(GridFlags.MULTIBLOCK)) continue;
            var multiblock = ((IGridNode) winner).getService(IGridMultiblock.class);
            if (multiblock == null) continue;
            var siblings = multiblock.getMultiblockNodes();
            while (siblings.hasNext()) {
                var sibling = siblings.next();
                if (sibling != null && sibling != winner) {
                    multiblocksWithChannel.add((GridNode) sibling);
                }
            }
        }

        BorrowedCapacityCalculator.activeNodeFlow = thunderbolt$flowResult.nodeFlow();
        BorrowedCapacityCalculator.activeNetworkNodes = thunderbolt$flowResult.networkNodes();
        BorrowedCapacityCalculator.activeConnectionFlow = thunderbolt$flowResult.connectionFlow();
    }

    // TAIL does not run on exceptions; clear the published flow in finally as well.

    @WrapOperation(method = "compute",
            at = @At(value = "INVOKE",
                     target = "Lappeng/me/pathfinding/PathingCalculation;propagateAssignments()V"))
    private void ae2lt$guardPropagateAssignments(PathingCalculation instance, Operation<Void> op) {
        try {
            op.call(instance);
        } finally {
            BorrowedCapacityCalculator.clearActiveData();
        }
    }

    // Phase 4: force-apply max-flow results & cleanup after DFS
    @Inject(method = "compute", at = @At("TAIL"))
    private void thunderbolt$applyFlowAndCleanup(CallbackInfo ci) {
        if (thunderbolt$flowResult != null) {
            // Clear stale counts even when AE2 propagation misses registered controllers.
            Set<GridConnection> resetSeen = new ReferenceOpenHashSet<>();
            Set<IGridNode> networkNodes = thunderbolt$flowResult.networkNodes();
            for (var node : networkNodes) {
                for (var conn : node.getConnections()) {
                    if (conn instanceof GridConnection gc && resetSeen.add(gc)) {
                        gc.setAdHocChannels(0);
                    }
                }
                if (node instanceof HighCapacitySubtreeNode osn) {
                    osn.thunderbolt$setUsedChannels(0);
                }
            }

            var nodeFlow = thunderbolt$flowResult.nodeFlow();
            for (var node : networkNodes) {
                if (node instanceof HighCapacitySubtreeNode osn) {
                    int flow = nodeFlow.getInt(node);
                    osn.thunderbolt$setUsedChannels(flow);
                }
            }

            // Restore AE2's sibling bonus so the whole reserved multiblock stays active.
            for (var sibling : multiblocksWithChannel) {
                if (networkNodes.contains(sibling)) {
                    sibling.incrementChannelCount(1);
                }
            }

            var connFlow = thunderbolt$flowResult.connectionFlow();
            for (var entry : connFlow.reference2IntEntrySet()) {
                entry.getKey().setAdHocChannels(entry.getIntValue());
            }

            // Count one winner per device or multiblock for PathingService reporting.
            thunderbolt$maxFlowChannelsInUse = thunderbolt$flowResult.channelNodes().size();
        }
        BorrowedCapacityCalculator.clearActiveData();
        thunderbolt$flowResult = null;
    }

    // PathingService reads this after compute() for network status.

    @Inject(method = "getChannelsInUse", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$overrideChannelsInUse(CallbackInfoReturnable<Integer> cir) {
        if (thunderbolt$maxFlowChannelsInUse >= 0) {
            cir.setReturnValue(thunderbolt$maxFlowChannelsInUse);
        }
    }
}
