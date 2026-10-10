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
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.api.channel.ChannelRequestProvider;
import com.moakiee.thunderbolt.api.channel.ConnectionChannelCapacityProvider;

/**
 * Channel assignment using a bidirectional tree seed and exact residual max-flow.
 * Sources are registered controllers or vanilla faces; node-split relays enforce cable capacity.
 * Each REQUIRE_CHANNEL device receives a channel only when its sink edge carries the full request.
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

        var network = discoverNetwork(grid, capacitySources);

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
    private static DiscoveredNetwork discoverNetwork(
            IGrid grid, List<IGridNode> capacitySources) {

        var network = new DiscoveredNetwork();
        Queue<IGridNode> q = new ArrayDeque<>();

        for (var oc : capacitySources) {
            if (network.add(oc)) q.add(oc);
        }

        for (var vc : HighCapacityChannelSupport.getAllControllerNodes(grid)) {
            if (ChannelSourceRegistry.isChannelSource(vc.getOwner())) continue;
            for (var c : vc.getConnections()) {
                if (!(c instanceof GridConnection gc)) continue;
                var other = gc.getOtherSide(vc);
                if (other.getOwner() instanceof ControllerBlockEntity) continue;
                tryEnqueue(other, network, q);
            }
        }

        while (!q.isEmpty()) {
            var cur = q.poll();
            var connections = cur.getConnections();
            network.incidences += connections.size();
            for (var c : connections) {
                if (!(c instanceof GridConnection gc)) continue;
                var other = gc.getOtherSide(cur);
                tryEnqueue(other, network, q);
            }
        }
        return network;
    }

    /** BFS-ordered nodes, their identity index, and per-calculation storage hints. */
    private static final class DiscoveredNetwork {
        final Reference2IntOpenHashMap<IGridNode> index = new Reference2IntOpenHashMap<>();
        final List<IGridNode> nodes = new ArrayList<>();
        long incidences;
        int sinks;

        DiscoveredNetwork() {
            index.defaultReturnValue(-1);
        }

        boolean add(IGridNode node) {
            if (index.putIfAbsent(node, nodes.size()) != -1) return false;
            nodes.add(node);
            if (node instanceof GridNode gn && gn.hasFlag(GridFlags.REQUIRE_CHANNEL)) sinks++;
            return true;
        }
    }

    /**
     * Attempts to add a discovered neighbour to the BFS frontier.
     * High-capacity controllers are traversed; vanilla controllers are skipped;
     * CANNOT_CARRY devices are added as sinks only if they REQUIRE_CHANNEL.
     */
    private static void tryEnqueue(IGridNode other, DiscoveredNetwork network, Queue<IGridNode> q) {
        if (network.index.containsKey(other)) return;

        if (other.getOwner() instanceof ControllerBlockEntity) {
            if (ChannelSourceRegistry.isChannelSource(other.getOwner())) {
                network.add(other);
                q.add(other);
            }
            return;
        }

        if (other instanceof GridNode gn && gn.hasFlag(GridFlags.CANNOT_CARRY)) {
            if (gn.hasFlag(GridFlags.REQUIRE_CHANNEL) && network.add(other))
                network.incidences += other.getConnections().size();
            return;
        }

        network.add(other);
        q.add(other);
    }

    // flow-network construction & solve
    private static Result solve(IGrid grid,
                                List<IGridNode> capacitySources,
                                DiscoveredNetwork discovered,
                                ChannelMode mode) {

        // Keep BFS discovery order for graph assembly and the seed's forest scan.
        // Reuse its identity index instead of rebuilding one in hash-table order.
        var nodes = discovered.nodes;
        int total = nodes.size();
        var idx = discovered.index;
        // Discovery is finished. Its membership view owns the index for this
        // result; subsequent calculations create an independent map.
        Set<IGridNode> network = idx.keySet();
        // With no channel requests, every node and connection carries zero.
        // Keep discovery for membership/finalization, but omit the residual graph.
        if (discovered.sinks == 0) {
            return new Result(new ReferenceOpenHashSet<>(), network,
                    new Reference2IntOpenHashMap<>(), new Reference2IntOpenHashMap<>());
        }
        int S = 2 * total, T = 2 * total + 1;
        // An internal link needs two forward arcs,
        // and an external controller face needs one. Excluded links only overestimate.
        ChannelFlowNetwork flowNetwork = new ChannelFlowNetwork(2 * total + 2,
                2L * (total + discovered.incidences + capacitySources.size() + discovered.sinks));

        // 1) node-split: in → out, capacity = relay capacity
        //    Record the edge index of each node-split edge for flow readback.
        int[] splitEdge = new int[total];
        for (int ci = 0; ci < total; ci++) {
            int cap = nodeCap(nodes.get(ci), mode);
            splitEdge[ci] = flowNetwork.edgeCount();
            flowNetwork.addEdge(2 * ci, 2 * ci + 1, cap);
        }

        // 2) connections between discovered nodes (bidirectional)
        // Each physical link adds four consecutive residual edges after the splits.
        // Edge endpoints and reverse capacities already encode the readback data;
        // retain only the connection reference instead of allocating a record per link.
        int connectionEdgeStart = flowNetwork.edgeCount();
        List<GridConnection> connEdges = new ArrayList<>(total);
        for (int ci = 0; ci < total; ci++) {
            var n = nodes.get(ci);
            var adjacent = n.getConnections();
            for (int j = 0; j < adjacent.size(); j++) {
                var c = adjacent.get(j);
                if (!(c instanceof GridConnection gc)) continue;
                var other = gc.getOtherSide(n);
                int oi = idx.getInt(other);
                // A GridConnection occurs at both endpoints. Keep every parallel
                // connection, visiting it only from the lower numbered endpoint.
                if (oi <= ci) continue;
                int edgeCap = getConnectionCap(gc, n, other, mode);
                flowNetwork.addEdge(2 * ci + 1, 2 * oi, edgeCap);
                flowNetwork.addEdge(2 * oi + 1, 2 * ci, edgeCap);
                connEdges.add(gc);
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

        // Sink arcs are consecutive and their reverse residual is the assigned
        // flow. Keep only the node references for readback and owner callbacks.
        List<IGridNode> sinkNodes = new ArrayList<>(discovered.sinks);
        int sinkEdgeStart = flowNetwork.edgeCount();
        for (int ci = 0; ci < total; ci++) {
            var n = nodes.get(ci);
            if (!(n instanceof GridNode gn)) continue;
            if (!gn.hasFlag(GridFlags.REQUIRE_CHANNEL)) continue;
            if (multiblockSkip.contains(n)) continue;
            int requested = gn.getOwner() instanceof ChannelRequestProvider p
                    ? Math.max(1, p.thunderbolt$getRequestedChannels()) : 1;
            flowNetwork.addEdge(2 * ci + 1, T, requested);
            sinkNodes.add(n);
        }

        int initialFlow = BidirectionalFlowSeed.assign(flowNetwork, S, T, splitEdge, INF);
        int maxFlow = initialFlow + flowNetwork.maxFlow(S, T, INF - initialFlow);

        // Remove the four-edge circulation on each bidirectional physical link.
        // Its net connection flow is zero, but both endpoint split edges carried it.
        for (int j = 0; j < connEdges.size(); j++) {
            int edgeAB = connectionEdgeStart + 4 * j, edgeBA = edgeAB + 2;
            int both = Math.min(flowNetwork.residual(edgeAB ^ 1), flowNetwork.residual(edgeBA ^ 1));
            if (both > 0) {
                flowNetwork.cancelFlow(edgeAB, both);
                flowNetwork.cancelFlow(edgeBA, both);
                flowNetwork.cancelFlow(splitEdge[flowNetwork.to[edgeAB ^ 1] / 2], both);
                flowNetwork.cancelFlow(splitEdge[flowNetwork.to[edgeAB] / 2], both);
            }
        }

        LOG.debug("maxFlow={}, network={}, sinks={}, capacitySources={}, supply/ctrl={}",
                maxFlow, network.size(), sinkNodes.size(), capacitySources.size(), supply);

        // Collect winning devices
        Set<GridNode> winners = new ReferenceOpenHashSet<>(sinkNodes.size());
        for (int j = 0; j < sinkNodes.size(); j++) {
            int edgeIdx = sinkEdgeStart + 2 * j;
            int assigned = flowNetwork.residual(edgeIdx ^ 1);
            if (flowNetwork.residual(edgeIdx) == 0) {
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
            int flowThrough = flowNetwork.residual(splitEdge[ci] ^ 1);
            if (flowThrough > 0) {
                nodeFlow.put(nodes.get(ci), flowThrough);
            }
        }

        // Collect exact flow on each GridConnection from residual edges
        Reference2IntOpenHashMap<GridConnection> connectionFlow =
                new Reference2IntOpenHashMap<>(connEdges.size() + faceEdges.size());
        connectionFlow.defaultReturnValue(0);
        for (int j = 0; j < connEdges.size(); j++) {
            int edgeAB = connectionEdgeStart + 4 * j;
            int flowAB = flowNetwork.residual(edgeAB ^ 1);
            int flowBA = flowNetwork.residual((edgeAB + 2) ^ 1);
            int netFlow = Math.abs(flowAB - flowBA);
            if (netFlow > 0) {
                connectionFlow.put(connEdges.get(j), netFlow);
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
