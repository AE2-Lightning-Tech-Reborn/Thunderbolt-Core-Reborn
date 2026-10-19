package com.moakiee.thunderbolt.mixin.ae2.channel;

import java.util.List;
import java.util.Set;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.me.GridConnection;
import appeng.me.pathfinding.IPathItem;
import appeng.me.pathfinding.PathingCalculation;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.config.ThunderboltCommonConfig;
import com.moakiee.thunderbolt.core.channel.BorrowedCapacityCalculator;
import com.moakiee.thunderbolt.core.channel.HighCapacityChannelSupport;
import com.moakiee.thunderbolt.core.channel.HighCapacitySubtreeNode;
import com.moakiee.thunderbolt.core.channel.PendingChannelConnection;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs max-flow after AE2 12's incremental BFS, before its native ChannelFinalizer. */
@Mixin(value = PathingCalculation.class, remap = false)
public abstract class PathingCalculationCapMixin {
    @Shadow private List<IPathItem> queue;
    @Shadow private int channelsInUse;
    @Shadow private int channelsByBlocks;
    @Unique private IGrid thunderbolt$grid;
    @Unique private List<IGridNode> thunderbolt$capacitySources;
    @Unique private boolean thunderbolt$useMaxFlow;
    @Unique private boolean thunderbolt$applied;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void thunderbolt$initialize(IGrid grid, CallbackInfo ci) {
        thunderbolt$grid = grid;
        var controllers = HighCapacityChannelSupport.getAllControllerNodes(grid);
        thunderbolt$capacitySources = controllers.stream()
                .filter(node -> ChannelSourceRegistry.isChannelSource(node.getOwner())).toList();
        thunderbolt$useMaxFlow = grid.getPathingService().getChannelMode() != ChannelMode.INFINITE
                && ThunderboltCommonConfig.useMaxFlow(grid, !controllers.isEmpty());
    }

    @Inject(method = "tryUseChannel", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$deferAllocation(IPathItem node, CallbackInfoReturnable<Boolean> cir) {
        if (thunderbolt$useMaxFlow) cir.setReturnValue(false);
    }

    @Inject(method = "step", at = @At("TAIL"))
    private void thunderbolt$applyFlow(CallbackInfo ci) {
        if (!thunderbolt$useMaxFlow || thunderbolt$applied || !queue.isEmpty()) return;
        var flow = BorrowedCapacityCalculator.assignChannels(thunderbolt$grid, thunderbolt$capacitySources);
        if (flow == null) return;
        thunderbolt$applied = true;
        Set<GridConnection> seen = new ReferenceOpenHashSet<>();
        long usage = 0;
        for (var node : flow.networkNodes()) {
            int count = flow.nodeFlow().getInt(node);
            ((HighCapacitySubtreeNode) node).thunderbolt$setUsedChannels(count);
            usage += count;
            for (var connection : node.getConnections()) {
                if (connection instanceof GridConnection gc && seen.add(gc)) {
                    int connectionCount = flow.connectionFlow().getInt(gc);
                    ((PendingChannelConnection) gc).thunderbolt$setPendingChannels(connectionCount);
                    usage += connectionCount;
                }
            }
        }
        // Each winning multiblock consumes one route but all of its nodes must be active.
        Set<IGridNode> siblings = new ReferenceOpenHashSet<>();
        for (var winner : flow.channelNodes()) {
            if (!winner.hasFlag(GridFlags.MULTIBLOCK)) continue;
            var multiblock = winner.getService(IGridMultiblock.class);
            if (multiblock == null) continue;
            var iterator = multiblock.getMultiblockNodes();
            while (iterator.hasNext()) {
                var sibling = iterator.next();
                if (sibling != winner && flow.networkNodes().contains(sibling)
                        && !flow.channelNodes().contains(sibling) && siblings.add(sibling)) {
                    ((IPathItem) sibling).incrementChannelCount(1);
                    usage++;
                }
            }
        }
        channelsInUse = flow.channelNodes().size();
        channelsByBlocks = (int) Math.min(Integer.MAX_VALUE, usage);
    }
}
