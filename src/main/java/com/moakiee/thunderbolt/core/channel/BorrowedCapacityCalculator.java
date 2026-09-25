package com.moakiee.thunderbolt.core.channel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.api.channel.ChannelRequestProvider;
import com.moakiee.thunderbolt.api.channel.ConnectionChannelCapacityProvider;

/**
 * Assigns channels to devices in the high-capacity network using a bidirectional tree seed followed by exact residual max-flow.
 * <p>
 * Flow network model:
 * <ul>
 *   <li><b>Sources</b>: high-capacity controllers (cap={@code channelsPerController}),
 *       vanilla controller faces (cap={@code 32×factor}).</li>
 *   <li><b>Relays</b> (node-split): high-capacity cables/controllers = ∞,
 *       dense cables = 32×f, normal cables = 8×f.</li>
 *   <li><b>Sinks</b>: {@code REQUIRE_CHANNEL} devices → super-sink T (cap=requested channels).</li>
 * </ul>
 * After max-flow, a device is active iff its device→T edge carries its full request.
 */
public final class BorrowedCapacityCalculator {

    private static final Logger LOG = LoggerFactory.getLogger("thunderbolt-channel-maxflow");
    private static final int INF = Integer.MAX_VALUE / 2;

    /**
     * Active flow data set by the current PathingCalculation for use by
     * {@code GridNode.finalizeChannels()} injection. Cleared after finalization.
     * Thread-safe: Minecraft world ticks are single-threaded.
     */
    public static volatile Reference2IntOpenHashMap<IGridNode> activeNodeFlow;
    public static volatile Set<IGridNode> activeNetworkNodes;

    /**
     * Active per-connection flow data for use by the GridConnection mixin.
     */
    public static volatile Reference2IntOpenHashMap<GridConnection> activeConnectionFlow;

    /**
     * Result of a max-flow channel assignment.
     *
     * @param channelNodes    devices that were granted a channel (its full request on device→T)
     * @param networkNodes    all nodes discovered in the network (for usedChannels override)
     * @param nodeFlow        flow through each node's node-split edge
     *                        (= usedChannels for that cable/device); default 0 for missing keys
     * @param connectionFlow  exact flow on each GridConnection from the final feasible flow
     */
    public record Result(Set<GridNode> channelNodes,
                         Set<IGridNode> networkNodes,
                         Reference2IntOpenHashMap<IGridNode> nodeFlow,
                         Reference2IntOpenHashMap<GridConnection> connectionFlow) {}

    private BorrowedCapacityCalculator() {}

    /**
     * Clears all static active-flow fields. Called defensively at the start
     * of each pathing computation to guard against stale data from a previous
     * computation that may have terminated abnormally.
     */
    public static void clearActiveData() {
        activeNodeFlow = null;
        activeNetworkNodes = null;
        activeConnectionFlow = null;
    }

    /**
     * Runs max-flow on the entire controller network.
     *
     * @return channel assignment result, or {@code null} if the channel mode
     *         is INFINITE (caller should fall through to vanilla logic)
     */
    public static Result assignChannels(IGrid grid, List<IGridNode> capacitySources) {
        var channelMode = grid.getPathingService().getChannelMode();
        if (channelMode == ChannelMode.INFINITE) return null;

        Set<IGridNode> network = discoverNetwork(grid, capacitySources);

        return solve(grid, capacitySources, network, channelMode);
    }

    /**
     * BFS from ALL controllers to discover the entire network.
     * <ul>
     *   <li>High-capacity controllers are added as relay nodes (BFS through them).</li>
     *   <li>Vanilla controller faces seed their adjacent cables into the BFS.</li>
     *   <li>REQUIRE_CHANNEL + CANNOT_CARRY devices are included as sinks
     *       but not expanded through.</li>
     * </ul>
     */
    private static Set<IGridNode> discoverNetwork(
            IGrid grid, List<IGridNode> capacitySources) {

        Set<IGridNode> nodes = new ReferenceOpenHashSet<>();
        Queue<IGridNode> q = new ArrayDeque<>();

        for (var oc : capacitySources) {
            nodes.add(oc);
            q.add(oc);
        }

        for (var vc : HighCapacityChannelSupport.getAllControllerNodes(grid)) {
            if (ChannelSourceRegistry.isChannelSource(vc.getOwner())) continue;
            for (var c : vc.getConnections()) {
                if (!(c instanceof GridConnection gc)) continue;
                var other = gc.getOtherSide(vc);
                if (other.getOwner() instanceof ControllerBlockEntity) continue;
                tryEnqueue(other, nodes, q);
            }
        }

        while (!q.isEmpty()) {
            var cur = q.poll();
            for (var c : cur.getConnections()) {
                if (!(c instanceof GridConnection gc)) continue;
                var other = gc.getOtherSide(cur);
                tryEnqueue(other, nodes, q);
            }
        }
        return nodes;
    }

    /**
     * Attempts to add a discovered neighbour to the BFS frontier.
     * High-capacity controllers are traversed; vanilla controllers are skipped;
     * CANNOT_CARRY devices are added as sinks only if they REQUIRE_CHANNEL.
     */
    private static void tryEnqueue(IGridNode other, Set<IGridNode> nodes, Queue<IGridNode> q) {
        if (nodes.contains(other)) return;

        if (other.getOwner() instanceof ControllerBlockEntity) {
            if (ChannelSourceRegistry.isChannelSource(other.getOwner())) {
                nodes.add(other);
                q.add(other);
            }
            return;
        }

        if (other instanceof GridNode gn && gn.hasFlag(GridFlags.CANNOT_CARRY)) {
            if (gn.hasFlag(GridFlags.REQUIRE_CHANNEL)) nodes.add(other);
            return;
        }

        nodes.add(other);
        q.add(other);
    }

    // ── flow-network construction & solve ────────────────────────────

    private static Result solve(IGrid grid,
                                List<IGridNode> capacitySources,
                                Set<IGridNode> network,
                                ChannelMode mode) {

        IGridNode[] nodes = network.toArray(IGridNode[]::new);
        int total = nodes.length;
        Reference2IntOpenHashMap<IGridNode> idx = new Reference2IntOpenHashMap<>(total);
        idx.defaultReturnValue(-1);
        for (int i = 0; i < total; i++) idx.put(nodes[i], i);
        int S = 2 * total, T = 2 * total + 1;
        ChannelFlowNetwork flowNetwork = new ChannelFlowNetwork(2 * total + 2);

        // 1) node-split: in → out, capacity = relay capacity
        //    Record the edge index of each node-split edge for flow readback.
        int[] splitEdge = new int[total];
        int[] nodeCapacity = new int[total];
        for (int ci = 0; ci < total; ci++) {
            int cap = nodeCapacity[ci] = nodeCap(nodes[ci], mode);
            splitEdge[ci] = flowNetwork.edgeCount();
            flowNetwork.addEdge(2 * ci, 2 * ci + 1, cap);
        }

        // 2) connections between discovered nodes (bidirectional)
        //    Track residual edge indices per GridConnection for flow readback.
        record ConnEdge(GridConnection gc, int edgeAB, int edgeBA, int cap, int a, int b) {}
        List<ConnEdge> connEdges = new ArrayList<>();
        for (int ci = 0; ci < total; ci++) {
            var n = nodes[ci];
            for (var c : n.getConnections()) {
                if (!(c instanceof GridConnection gc)) continue;
                var other = gc.getOtherSide(n);
                int oi = idx.getInt(other);
                // A GridConnection occurs at both endpoints. Keep every parallel
                // connection, visiting it only from the lower numbered endpoint.
                if (oi <= ci) continue;
                int edgeCap = getConnectionCap(gc, n, other, mode);
                int eAB = flowNetwork.edgeCount();
                flowNetwork.addEdge(2 * ci + 1, 2 * oi, edgeCap);
                int eBA = flowNetwork.edgeCount();
                flowNetwork.addEdge(2 * oi + 1, 2 * ci, edgeCap);
                connEdges.add(new ConnEdge(gc, eAB, eBA, edgeCap, ci, oi));
            }
        }

        // 3) high-capacity controller sources: S → OC_in
        int supply = HighCapacityChannelSupport.supplyPerController(mode.getCableCapacityFactor());
        for (var oc : capacitySources) {
            int ci = idx.getInt(oc);
            flowNetwork.addEdge(S, 2 * ci, supply);
        }

        // 4) vanilla controller face sources: S → cable_in
        //    Also track edge indices so we can compute flow on face connections.
        int faceCap = 32 * mode.getCableCapacityFactor();
        record FaceEdge(GridConnection gc, int edgeIdx, int cap) {}
        List<FaceEdge> faceEdges = new ArrayList<>();
        for (var node : HighCapacityChannelSupport.getAllControllerNodes(grid)) {
            if (ChannelSourceRegistry.isChannelSource(node.getOwner())) continue;
            for (var c : node.getConnections()) {
                if (!(c instanceof GridConnection gc)) continue;
                var other = gc.getOtherSide(node);
                if (other.getOwner() instanceof ControllerBlockEntity) continue;
                int oi = idx.getInt(other);
                if (oi >= 0) {
                    int feIdx = flowNetwork.edgeCount();
                    flowNetwork.addEdge(S, 2 * oi, faceCap);
                    faceEdges.add(new FaceEdge(gc, feIdx, faceCap));
                }
            }
        }

        // 5) REQUIRE_CHANNEL devices → T, cap=requested channels (normally 1)
        //    Multiblock groups (e.g. crafting CPUs) share a single sink edge
        //    so the entire multiblock consumes only 1 channel.
        Set<IGridNode> multiblockSkip = new ReferenceOpenHashSet<>();
        for (var n : nodes) {
            if (!(n instanceof GridNode gn)) continue;
            if (!gn.hasFlag(GridFlags.REQUIRE_CHANNEL) || !gn.hasFlag(GridFlags.MULTIBLOCK)) continue;
            if (multiblockSkip.contains(n)) continue;

            var multiblock = n.getService(IGridMultiblock.class);
            if (multiblock == null) continue;

            // Mark all siblings in the network as skip; the first encountered
            // node becomes the representative and keeps its sink edge.
            var siblings = multiblock.getMultiblockNodes();
            while (siblings.hasNext()) {
                var sibling = siblings.next();
                if (sibling != n && idx.getInt(sibling) >= 0) {
                    multiblockSkip.add(sibling);
                }
            }
        }

        List<IGridNode> sinkNodes = new ArrayList<>();
        IntList sinkEdgeIndices = new IntArrayList();
        for (var n : nodes) {
            if (!(n instanceof GridNode gn)) continue;
            if (!gn.hasFlag(GridFlags.REQUIRE_CHANNEL)) continue;
            if (multiblockSkip.contains(n)) continue;
            int ci = idx.getInt(n);
            int requested = gn.getOwner() instanceof ChannelRequestProvider p
                    ? Math.max(1, p.thunderbolt$getRequestedChannels()) : 1;
            sinkEdgeIndices.add(flowNetwork.edgeCount());
            flowNetwork.addEdge(2 * ci + 1, T, requested);
            sinkNodes.add(n);
        }

        int initialFlow = BidirectionalFlowSeed.assign(flowNetwork, S, T, splitEdge, INF);
        int maxFlow = initialFlow + flowNetwork.maxFlow(S, T, INF - initialFlow);

        // Remove the four-edge circulation on each bidirectional physical link.
        // Its net connection flow is zero, but both endpoint split edges carried it.
        for (var ce : connEdges) {
            int both = Math.min(ce.cap - flowNetwork.residual(ce.edgeAB),
                    ce.cap - flowNetwork.residual(ce.edgeBA));
            if (both > 0) {
                flowNetwork.cancelFlow(ce.edgeAB, both);
                flowNetwork.cancelFlow(ce.edgeBA, both);
                flowNetwork.cancelFlow(splitEdge[ce.a], both);
                flowNetwork.cancelFlow(splitEdge[ce.b], both);
            }
        }

        LOG.debug("maxFlow={}, network={}, sinks={}, capacitySources={}, supply/ctrl={}",
                maxFlow, network.size(), sinkNodes.size(), capacitySources.size(), supply);

        // Collect winning devices
        Set<GridNode> winners = new ReferenceOpenHashSet<>(sinkNodes.size());
        for (int j = 0; j < sinkNodes.size(); j++) {
            int edgeIdx = sinkEdgeIndices.getInt(j);
            int requested = sinkNodes.get(j).getOwner() instanceof ChannelRequestProvider p
                    ? Math.max(1, p.thunderbolt$getRequestedChannels()) : 1;
            int assigned = requested - flowNetwork.residual(edgeIdx);
            if (assigned >= requested) {
                winners.add((GridNode) sinkNodes.get(j));
            }
            if (sinkNodes.get(j).getOwner() instanceof ChannelRequestProvider p) {
                p.thunderbolt$setUsedChannels(assigned);
            }
        }

        // Collect flow through each node-split (= usedChannels for that node)
        Reference2IntOpenHashMap<IGridNode> nodeFlow = new Reference2IntOpenHashMap<>(total);
        nodeFlow.defaultReturnValue(0);
        for (int ci = 0; ci < total; ci++) {
            int flowThrough = nodeCapacity[ci] - flowNetwork.residual(splitEdge[ci]);
            if (flowThrough > 0) {
                nodeFlow.put(nodes[ci], flowThrough);
            }
        }

        // Collect exact flow on each GridConnection from residual edges
        Reference2IntOpenHashMap<GridConnection> connectionFlow =
                new Reference2IntOpenHashMap<>(connEdges.size() + faceEdges.size());
        connectionFlow.defaultReturnValue(0);
        for (var ce : connEdges) {
            int flowAB = ce.cap - flowNetwork.residual(ce.edgeAB);
            int flowBA = ce.cap - flowNetwork.residual(ce.edgeBA);
            int netFlow = Math.abs(flowAB - flowBA);
            if (netFlow > 0) {
                connectionFlow.put(ce.gc, netFlow);
            }
        }

        // Collect flow on vanilla controller face connections.
        // These connections are not modeled as edges in the flow network
        // (vanilla controllers are not in 'network'), so we derive their
        // flow from the source edge S → cable_in.
        for (var fe : faceEdges) {
            int flow = fe.cap - flowNetwork.residual(fe.edgeIdx);
            if (flow > 0) {
                connectionFlow.mergeInt(fe.gc, flow, Integer::sum);
            }
        }

        return new Result(winners, network, nodeFlow, connectionFlow);
    }

    /**
     * Relay capacity for flow-network node-split.
     * REQUIRE_CHANNEL + CANNOT_CARRY devices get cap=1 (consume one channel).
     * Delegates to GridNode.getMaxChannels() to respect mixin overrides
     * (e.g. AE2-Crystal-Science's CustomChannelProviderHost).
     */
    private static int nodeCap(IGridNode node, ChannelMode mode) {
        if (HighCapacityChannelSupport.is128ChannelOwner(node.getOwner())) return INF;
        if (node instanceof GridNode gn) {
            if (gn.hasFlag(GridFlags.CANNOT_CARRY)) {
                return gn.hasFlag(GridFlags.REQUIRE_CHANNEL) ? 1 : 0;
            }
            return gn.getMaxChannels();
        }
        return 8 * mode.getCableCapacityFactor();
    }

    /**
     * Edge capacity for a connection. Virtual wireless connections (no physical
     * direction) where one endpoint implements {@link ConnectionChannelCapacityProvider}
     * are capped according to the provider's channel limit.
     */
    private static int getConnectionCap(GridConnection gc, IGridNode a, IGridNode b, ChannelMode mode) {
        // Physical connections (have a direction) are uncapped in the flow model
        if (gc.getDirection(a) != null) return INF;

        int capA = a.getOwner() instanceof ConnectionChannelCapacityProvider pA ? pA.getConnectionChannelCapacity(mode) : INF;
        int capB = b.getOwner() instanceof ConnectionChannelCapacityProvider pB ? pB.getConnectionChannelCapacity(mode) : INF;
        return Math.min(capA, capB);
    }


}
