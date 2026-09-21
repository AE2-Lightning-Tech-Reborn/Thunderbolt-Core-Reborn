package com.moakiee.thunderbolt.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.api.networking.pathing.IPathingService;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import net.minecraft.core.Direction;
import sun.misc.Unsafe;

import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.channel.ChannelRequestProvider;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.api.channel.ConnectionChannelCapacityProvider;
import com.moakiee.thunderbolt.api.channel.HighCapacityChannelOwner;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;

/**
 * Production {@link BorrowedCapacityCalculator#assignChannels} against real AE2
 * {@link GridNode}/{@link GridConnection} objects. The residual kernel is covered
 * by {@link ChannelFlowNetworkTest}; this suite covers discovery, wireless caps,
 * multiblock sinks, and connection readback.
 */
class BorrowedCapacityCalculatorAssignChannelsTest {
    private static final int INF = Integer.MAX_VALUE / 2;
    private static final Constructor<GridConnection> CONNECTION;
    private static ChannelSourceRegistry.Registration sourceRegistration;
    private static int previousSupply;

    static {
        MinecraftTestBootstrap.ensureInitialized();
        try {
            CONNECTION = GridConnection.class.getDeclaredConstructor(
                    GridNode.class, GridNode.class, Direction.class);
            CONNECTION.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    static void registerHighCapacitySource() {
        previousSupply = CoreConfig.channelsPerController();
        CoreConfig.setChannelsPerController(128);
        sourceRegistration = ChannelSourceRegistry.registerController(
                "assign-channels-test:source", Source.class);
    }

    @AfterAll
    static void restoreHostConfig() {
        CoreConfig.setChannelsPerController(previousSupply);
        if (sourceRegistration != null) {
            sourceRegistration.close();
        }
    }

    @Test
    void infiniteChannelModeFallsThrough() {
        Graph graph = new Graph(ChannelMode.INFINITE);
        graph.source();
        assertNull(BorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources));
    }

    @Test
    void virtualWirelessLinkIsCappedWhilePhysicalBypassIsNot() {
        for (boolean virtual : new boolean[] {false, true}) {
            Graph graph = new Graph(ChannelMode.DEFAULT);
            Node source = graph.source();
            Node a = graph.node(new Wireless(16), INF);
            Node b = graph.node(new Wireless(8), INF);
            graph.link(source, a);
            graph.link(a, b, virtual);
            branch(graph, b, 24);
            graph.expected = virtual ? 8 : 24;
            verify(graph, BorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources));
        }
    }

    @Test
    void multiblockSiblingsShareASingleSink() {
        Graph graph = new Graph(ChannelMode.DEFAULT);
        Node source = graph.source();
        List<IGridNode> siblings = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Node device = graph.node(new Object(), 1,
                    GridFlags.REQUIRE_CHANNEL, GridFlags.CANNOT_CARRY, GridFlags.MULTIBLOCK);
            siblings.add(device);
            graph.link(source, device);
        }
        for (IGridNode node : siblings) {
            ((GridNode) node).addService(IGridMultiblock.class, siblings::iterator);
        }
        graph.expected = 1;
        verify(graph, BorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources));
    }

    @Test
    void cannotCarryBarrierDoesNotDiscoverDevicesBehindIt() {
        Graph graph = new Graph(ChannelMode.DEFAULT);
        Node source = graph.source();
        Node stop = graph.terminal();
        graph.link(source, stop);
        branch(graph, stop, 4);
        graph.excluded = graph.nodes.size() - 2;
        graph.expected = 1;
        verify(graph, BorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources));
    }

    @Test
    void vanillaControllerFacesSeedAdjacentCables() {
        Graph graph = new Graph(ChannelMode.DEFAULT);
        Node face = graph.vanillaController();
        Node cable = graph.relay();
        Node first = graph.terminal();
        Node second = graph.terminal();
        graph.link(face, cable);
        graph.link(cable, first);
        graph.link(cable, second);
        graph.expected = 2;
        BorrowedCapacityCalculator.Result result =
                BorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources);
        verify(graph, result);
        assertEquals(2, result.channelNodes().size());
        assertEquals(2, result.connectionFlow().getInt(graph.edges.get(0).connection()),
                "vanilla controller face flow is read back from S → cable_in");
    }

    @Test
    void parallelVirtualConnectionsAddTheirIndividualCaps() {
        Graph graph = new Graph(ChannelMode.DEFAULT);
        Node source = graph.source();
        Node a = graph.node(new Wireless(4), INF);
        Node b = graph.node(new Wireless(4), INF);
        graph.link(source, a);
        graph.link(a, b, true);
        graph.link(a, b, true);
        branch(graph, b, 24);
        graph.expected = 8;
        verify(graph, BorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources));
    }

    @Test
    void weightedRequestConsumesTheAskedNumberOfChannels() {
        Graph graph = new Graph(ChannelMode.DEFAULT);
        Node source = graph.source();
        Node device = graph.node(new Weighted(5), INF, GridFlags.REQUIRE_CHANNEL);
        graph.link(source, device);
        graph.expected = 5;
        verify(graph, BorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources));
    }

    @Test
    void randomPhysicalNetworksMatchAnIndependentOracle() {
        for (int seed = 0; seed < 80; seed++) {
            Graph graph = random(seed);
            graph.expected = oracle(graph);
            try {
                verify(graph, BorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources));
            } catch (AssertionError e) {
                throw new AssertionError("assignChannels seed=" + seed, e);
            }
        }
    }

    private static void branch(Graph graph, Node parent, int leaves) {
        if (leaves <= 0) {
            return;
        }
        if (leaves == 1) {
            graph.link(parent, graph.terminal());
            return;
        }
        Node relay = graph.relay();
        graph.link(parent, relay);
        for (int i = 0; i < 4; i++) {
            branch(graph, relay, leaves / 4 + (i < leaves % 4 ? 1 : 0));
        }
    }

    private static Graph random(int seed) {
        Random random = new Random(seed);
        Graph graph = new Graph(ChannelMode.DEFAULT);
        graph.source();
        for (int i = 0; i < 4 + random.nextInt(8); i++) {
            Node node = graph.node(random.nextBoolean() ? new High() : new Object(),
                    random.nextBoolean() ? 8 : 32);
            graph.link(node, graph.nodes.get(random.nextInt(graph.nodes.size() - 1)));
        }
        int relays = graph.nodes.size();
        for (int i = 0; i < relays; i++) {
            int a = random.nextInt(relays);
            int b = random.nextInt(relays);
            if (a != b) {
                graph.link(graph.nodes.get(a), graph.nodes.get(b));
            }
        }
        for (int i = 0; i < 4 + random.nextInt(10); i++) {
            Node node = random.nextBoolean()
                    ? graph.terminal()
                    : graph.node(new Weighted(1 + random.nextInt(6)), INF, GridFlags.REQUIRE_CHANNEL);
            graph.link(graph.nodes.get(random.nextInt(relays)), node);
        }
        return graph;
    }

    private static int request(Node node) {
        return node.getOwner() instanceof ChannelRequestProvider provider
                ? Math.max(1, provider.thunderbolt$getRequestedChannels())
                : 1;
    }

    private static int capacity(Node node) {
        if (node.getOwner() instanceof HighCapacityChannelOwner) {
            return INF;
        }
        if (node.hasFlag(GridFlags.CANNOT_CARRY)) {
            return node.hasFlag(GridFlags.REQUIRE_CHANNEL) ? 1 : 0;
        }
        return node.cap;
    }

    private static int oracle(Graph graph) {
        int n = graph.nodes.size() * 2 + 2;
        int source = n - 2;
        int sink = n - 1;
        long[][] residual = new long[n][n];
        for (Node node : graph.nodes) {
            residual[2 * node.id][2 * node.id + 1] += capacity(node);
            if (node.hasFlag(GridFlags.REQUIRE_CHANNEL) && !node.hasFlag(GridFlags.MULTIBLOCK)) {
                residual[2 * node.id + 1][sink] += request(node);
            }
        }
        for (Edge edge : graph.edges) {
            residual[2 * edge.a.id + 1][2 * edge.b.id] += edge.capacity;
            residual[2 * edge.b.id + 1][2 * edge.a.id] += edge.capacity;
        }
        int supply = HighCapacityChannelSupport.supplyPerController(
                graph.mode.getCableCapacityFactor());
        for (IGridNode node : graph.sources) {
            residual[source][2 * ((Node) node).id] += supply;
        }
        int faceCap = 32 * graph.mode.getCableCapacityFactor();
        for (Node node : graph.nodes) {
            if (node.getOwner() instanceof ControllerBlockEntity) {
                for (var connection : node.getConnections()) {
                    if (!(connection instanceof GridConnection gc)) {
                        continue;
                    }
                    var other = gc.getOtherSide(node);
                    if (other instanceof Node cable && !(cable.getOwner() instanceof ControllerBlockEntity)) {
                        residual[source][2 * cable.id] += faceCap;
                    }
                }
            }
        }
        int flow = 0;
        while (true) {
            int[] parent = new int[n];
            Arrays.fill(parent, -1);
            parent[source] = source;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty() && parent[sink] < 0) {
                int from = queue.remove();
                for (int to = 0; to < n; to++) {
                    if (parent[to] < 0 && residual[from][to] > 0) {
                        parent[to] = from;
                        queue.add(to);
                    }
                }
            }
            if (parent[sink] < 0) {
                return flow;
            }
            long amount = INF;
            for (int v = sink; v != source; v = parent[v]) {
                amount = Math.min(amount, residual[parent[v]][v]);
            }
            for (int v = sink; v != source; v = parent[v]) {
                residual[parent[v]][v] -= amount;
                residual[v][parent[v]] += amount;
            }
            flow += (int) amount;
        }
    }

    private static int verify(Graph graph, BorrowedCapacityCalculator.Result result) {
        assertEquals(graph.nodes.size() - graph.excluded, result.networkNodes().size(),
                "discovery " + result.networkNodes().size() + "/" + graph.nodes.size());
        int total = 0;
        for (Node node : graph.nodes) {
            int flow = result.nodeFlow().getInt(node);
            assertTrue(flow >= 0 && flow <= capacity(node), "node capacity " + node.id);
            if (node.hasFlag(GridFlags.REQUIRE_CHANNEL) && result.networkNodes().contains(node)) {
                int used = node.getOwner() instanceof Weighted weighted
                        ? weighted.used
                        : (result.channelNodes().contains(node) ? 1 : 0);
                assertTrue(used >= 0 && used <= request(node), "sink capacity");
                if (!node.hasFlag(GridFlags.MULTIBLOCK)) {
                    assertEquals(used == request(node), result.channelNodes().contains(node), "winner");
                }
                if (node.getConnections().size() == 1 && !node.hasFlag(GridFlags.MULTIBLOCK)) {
                    assertEquals(used, flow, "leaf flow");
                }
                total += used;
            }
        }
        for (Node node : graph.nodes) {
            if (!node.hasFlag(GridFlags.CANNOT_CARRY) || node.hasFlag(GridFlags.MULTIBLOCK)
                    || !result.networkNodes().contains(node)) {
                continue;
            }
            int incoming = 0;
            for (Edge edge : graph.edges) {
                if (edge.a == node || edge.b == node) {
                    incoming += result.connectionFlow().getInt(edge.connection);
                }
            }
            assertEquals(result.channelNodes().contains(node) ? 1 : 0, incoming,
                    "terminal connection total");
        }
        for (Edge edge : graph.edges) {
            int flow = result.connectionFlow().getInt(edge.connection);
            assertTrue(flow >= 0 && flow <= edge.capacity, "edge cap");
        }
        if (graph.expected >= 0) {
            assertEquals(graph.expected, total, "flow " + total + " != " + graph.expected);
        }
        return total;
    }

    private static class High implements HighCapacityChannelOwner {
    }

    private static final class Source extends High {
    }

    private static final class Weighted extends High implements ChannelRequestProvider {
        final int request;
        int used;

        Weighted(int request) {
            this.request = request;
        }

        @Override
        public int thunderbolt$getRequestedChannels() {
            return request;
        }

        @Override
        public void thunderbolt$setUsedChannels(int channels) {
            used = channels;
        }
    }

    private static final class Wireless extends High implements ConnectionChannelCapacityProvider {
        final int cap;

        Wireless(int cap) {
            this.cap = cap;
        }

        @Override
        public int getConnectionChannelCapacity(ChannelMode mode) {
            return cap;
        }
    }

    private static final class Node extends GridNode {
        final int id;
        final int cap;

        Node(int id, Object owner, int cap, GridFlags... flags) {
            super(null, owner, (ignoredOwner, ignoredNode) -> {}, Set.of(flags));
            this.id = id;
            this.cap = cap;
        }

        @Override
        public int getMaxChannels() {
            return cap;
        }

        void attach(GridConnection connection) {
            connections.add(connection);
        }
    }

    private record Edge(Node a, Node b, GridConnection connection, int capacity) {
    }

    private static final class Graph {
        final List<Node> nodes = new ArrayList<>();
        final List<IGridNode> sources = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();
        final Map<Class<?>, List<IGridNode>> machines = new HashMap<>();
        final ChannelMode mode;
        final IGrid grid;
        int expected = -1;
        int excluded;

        Graph(ChannelMode mode) {
            this.mode = mode;
            IPathingService path = (IPathingService) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {IPathingService.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getChannelMode" -> mode;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            grid = (IGrid) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {IGrid.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPathingService", "getService" -> path;
                        case "getMachineClasses" -> machines.keySet();
                        case "getMachineNodes" -> machines.getOrDefault(args[0], List.of());
                        case "getNodes" -> nodes;
                        case "size" -> nodes.size();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        Node node(Object owner, int cap, GridFlags... flags) {
            Node node = new Node(nodes.size(), owner, cap, flags);
            nodes.add(node);
            machines.computeIfAbsent(owner.getClass(), ignored -> new ArrayList<>()).add(node);
            return node;
        }

        Node relay() {
            return node(new High(), INF);
        }

        Node terminal() {
            return node(new Object(), 1, GridFlags.REQUIRE_CHANNEL, GridFlags.CANNOT_CARRY);
        }

        Node source() {
            Node node = node(new Source(), INF);
            sources.add(node);
            return node;
        }

        Node vanillaController() {
            Node node = node(vanillaOwner(), INF);
            excluded++;
            return node;
        }

        void link(Node a, Node b) {
            link(a, b, false);
        }

        void link(Node a, Node b, boolean virtual) {
            try {
                GridConnection connection = CONNECTION.newInstance(a, b, virtual ? null : Direction.EAST);
                a.attach(connection);
                b.attach(connection);
                int cap = INF;
                if (virtual) {
                    if (a.getOwner() instanceof ConnectionChannelCapacityProvider provider) {
                        cap = Math.min(cap, provider.getConnectionChannelCapacity(mode));
                    }
                    if (b.getOwner() instanceof ConnectionChannelCapacityProvider provider) {
                        cap = Math.min(cap, provider.getConnectionChannelCapacity(mode));
                    }
                }
                edges.add(new Edge(a, b, connection, cap));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Object vanillaOwner() {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return ((Unsafe) field.get(null)).allocateInstance(ControllerBlockEntity.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
