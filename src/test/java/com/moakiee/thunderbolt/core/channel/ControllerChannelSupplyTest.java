package com.moakiee.thunderbolt.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.api.networking.pathing.IPathingService;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

import com.moakiee.thunderbolt.api.channel.ConnectionChannelCapacityProvider;
import com.moakiee.thunderbolt.api.channel.HighCapacityChannelOwner;

/** Exercises production discovery, source construction, allocation and flow readback on real AE2 nodes. */
class ControllerChannelSupplyTest {
    private static final int INF = Integer.MAX_VALUE / 2;

    @BeforeAll
    static void bootstrap() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void virtualControllerConnectionsHonorAbsoluteReceiverQuotasAcrossChannelModes() {
        for (var mode : List.of(ChannelMode.DEFAULT, ChannelMode.X2, ChannelMode.X4)) {
            for (int quota : new int[]{0, 1, 8, 31, 32, 33, 64, 128}) {
                var graph = new Graph(mode);
                var receiver = graph.node(new Object(), quota, GridFlags.DENSE_CAPACITY);
                var input = graph.link(graph.controller(), receiver, null);
                graph.devices(receiver, 160);

                var result = graph.solve();
                assertEquals(quota, result.channelNodes().size(), mode + "/" + quota);
                assertEquals(quota, result.nodeFlow().getInt(receiver));
                assertEquals(quota, result.connectionFlow().getInt(input));
                graph.assertTerminalFlows(result);
            }
        }
    }

    @Test
    void physicalControllerFacesKeepTheirLimitAndIndependentFacesAdd() {
        for (var mode : List.of(ChannelMode.DEFAULT, ChannelMode.X2, ChannelMode.X4)) {
            var graph = new Graph(mode);
            var receiver = graph.node(new LimitedLink(1), 512, GridFlags.DENSE_CAPACITY);
            var first = graph.link(graph.controller(), receiver, Direction.EAST);
            graph.devices(receiver, 512);
            int faceCapacity = 32 * mode.getCableCapacityFactor();
            var result = graph.solve();
            assertEquals(faceCapacity, result.channelNodes().size());
            assertEquals(faceCapacity, result.connectionFlow().getInt(first));

            var second = graph.link(graph.controller(), receiver, Direction.WEST);
            result = graph.solve();
            assertEquals(2 * faceCapacity, result.channelNodes().size());
            assertEquals(faceCapacity, result.connectionFlow().getInt(first));
            assertEquals(faceCapacity, result.connectionFlow().getInt(second));
            graph.assertTerminalFlows(result);
        }
    }

    @Test
    void unlimitedRelayMarkerDoesNotCreateUnlimitedControllerSupply() {
        var graph = new Graph(ChannelMode.DEFAULT);
        var receiver = graph.node(new HighCapacityRelay(), 32, GridFlags.DENSE_CAPACITY);
        var input = graph.link(graph.controller(), receiver, null);
        graph.devices(receiver, 128);
        var result = graph.solve();
        assertEquals(32, result.channelNodes().size());
        assertEquals(32, result.connectionFlow().getInt(input));
    }

    @Test
    void virtualControllerConnectionsCanDirectlySupplyCannotCarryDevices() {
        var graph = new Graph(ChannelMode.DEFAULT);
        var controller = graph.controller();
        graph.devices(controller, 5);
        var result = graph.solve();
        assertEquals(5, result.channelNodes().size());
        graph.assertTerminalFlows(result);
    }

    @Test
    void virtualControllerConnectionsAlsoHonorPerLinkCapacityProviders() {
        for (int linkLimit : new int[]{0, 8, 48, 96}) {
            var graph = new Graph(ChannelMode.DEFAULT);
            var receiver = graph.node(new LimitedLink(linkLimit), 64, GridFlags.DENSE_CAPACITY);
            var input = graph.link(graph.controller(), receiver, null);
            graph.devices(receiver, 96);
            var result = graph.solve();
            assertEquals(Math.min(64, linkLimit), result.channelNodes().size());
            assertEquals(Math.min(64, linkLimit), result.connectionFlow().getInt(input));
            graph.assertTerminalFlows(result);
        }
    }

    @Test
    void multipleVirtualSourceLinksShareOneReceiverQuota() {
        var graph = new Graph(ChannelMode.DEFAULT);
        var receiver = graph.node(new Object(), 64, GridFlags.DENSE_CAPACITY);
        var first = graph.link(graph.controller(), receiver, null);
        var second = graph.link(graph.controller(), receiver, null);
        graph.devices(receiver, 128);
        var result = graph.solve();
        assertEquals(64, result.channelNodes().size());
        assertEquals(64, result.nodeFlow().getInt(receiver));
        assertEquals(64, result.connectionFlow().getInt(first) + result.connectionFlow().getInt(second));
        graph.assertTerminalFlows(result);
    }

    @Test
    void separateReceiversKeepIndependentQuotas() {
        var graph = new Graph(ChannelMode.DEFAULT);
        var controller = graph.controller();
        for (int quota : new int[]{40, 24, 0}) {
            var receiver = graph.node(new Object(), quota, GridFlags.DENSE_CAPACITY);
            graph.link(controller, receiver, null);
            graph.devices(receiver, 80);
        }
        var result = graph.solve();
        assertEquals(64, result.channelNodes().size());
        graph.assertTerminalFlows(result);
    }

    @Test
    void downstreamCableAndWirelessLimitsRemainBottlenecks() {
        for (boolean wireless : new boolean[]{false, true}) {
            var graph = new Graph(ChannelMode.DEFAULT);
            var receiver = graph.node(new Object(), 96, GridFlags.DENSE_CAPACITY);
            graph.link(graph.controller(), receiver, null);
            var relay = graph.node(wireless ? new LimitedLink(12) : new Object(), wireless ? 96 : 8);
            graph.link(receiver, relay, wireless ? null : Direction.EAST);
            graph.devices(relay, 96);
            var result = graph.solve();
            assertEquals(wireless ? 12 : 8, result.channelNodes().size());
            graph.assertTerminalFlows(result);
        }
    }

    @Test
    void quotaChangesAndDisconnectReconnectLeaveNoStaleAssignments() {
        var graph = new Graph(ChannelMode.DEFAULT);
        var controller = graph.controller();
        var receiver = graph.node(new Object(), 64, GridFlags.DENSE_CAPACITY);
        var input = graph.link(controller, receiver, null);
        graph.devices(receiver, 128);
        for (int quota : new int[]{64, 8, 0, 96, 33}) {
            receiver.capacity = quota;
            var result = graph.solve();
            assertEquals(quota, result.channelNodes().size());
            assertEquals(quota, result.connectionFlow().getInt(input));
            graph.assertTerminalFlows(result);
        }

        graph.unlink(input);
        var disconnected = graph.solve();
        assertTrue(disconnected.channelNodes().isEmpty());
        assertEquals(0, disconnected.nodeFlow().getInt(receiver));
        assertEquals(0, disconnected.connectionFlow().getInt(input));
        assertFalse(disconnected.networkNodes().contains(receiver));

        var replacement = graph.link(controller, receiver, null);
        var reconnected = graph.solve();
        assertEquals(33, reconnected.channelNodes().size());
        assertEquals(0, reconnected.connectionFlow().getInt(input));
        assertEquals(33, reconnected.connectionFlow().getInt(replacement));
        graph.assertTerminalFlows(reconnected);
    }

    @Test
    void infiniteModeKeepsNativeAllocation() {
        var graph = new Graph(ChannelMode.INFINITE);
        assertNull(graph.solve());
    }

    private static final class HighCapacityRelay implements HighCapacityChannelOwner {}

    private record LimitedLink(int limit) implements ConnectionChannelCapacityProvider {
        @Override
        public int getConnectionChannelCapacity(ChannelMode mode) {
            return limit;
        }
    }

    private static final class Node extends GridNode {
        int capacity;

        Node(Object owner, int capacity, GridFlags... flags) {
            super(null, owner, (host, node) -> {}, Set.of(flags));
            this.capacity = capacity;
        }

        @Override
        public int getMaxChannels() {
            return hasFlag(GridFlags.CANNOT_CARRY) ? 0 : capacity;
        }

        void attach(GridConnection connection) {
            connections.add(connection);
        }

        void detach(GridConnection connection) {
            connections.remove(connection);
        }
    }

    private static final class Graph {
        private static final Constructor<GridConnection> CONNECTION = connectionConstructor();
        private final List<Node> nodes = new ArrayList<>();
        private final Map<Class<?>, List<IGridNode>> machines = new HashMap<>();
        private final IGrid grid;

        Graph(ChannelMode mode) {
            var loader = getClass().getClassLoader();
            var pathing = (IPathingService) Proxy.newProxyInstance(loader, new Class<?>[]{IPathingService.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getChannelMode" -> mode;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            grid = (IGrid) Proxy.newProxyInstance(loader, new Class<?>[]{IGrid.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPathingService" -> pathing;
                        case "getMachineClasses" -> machines.keySet();
                        case "getMachineNodes" -> machines.getOrDefault(args[0], List.of());
                        case "getNodes" -> nodes;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        Node node(Object owner, int capacity, GridFlags... flags) {
            var node = new Node(owner, capacity, flags);
            nodes.add(node);
            machines.computeIfAbsent(owner.getClass(), type -> new ArrayList<>()).add(node);
            return node;
        }

        Node controller() {
            try {
                // Only the controller's owner type is needed by discovery; no world is created.
                var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                var unsafe = (sun.misc.Unsafe) field.get(null);
                return node(unsafe.allocateInstance(ControllerBlockEntity.class), INF);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        GridConnection link(Node a, Node b, Direction direction) {
            try {
                var connection = CONNECTION.newInstance(a, b, direction);
                a.attach(connection);
                b.attach(connection);
                return connection;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        void unlink(GridConnection connection) {
            ((Node) connection.a()).detach(connection);
            ((Node) connection.b()).detach(connection);
        }

        void devices(Node parent, int count) {
            for (int i = 0; i < count; i++) {
                link(parent, node(new Object(), 1, GridFlags.REQUIRE_CHANNEL, GridFlags.CANNOT_CARRY), null);
            }
        }

        BorrowedCapacityCalculator.Result solve() {
            return BorrowedCapacityCalculator.assignChannels(grid, List.of());
        }

        void assertTerminalFlows(BorrowedCapacityCalculator.Result result) {
            int total = 0;
            for (var node : nodes) {
                if (!node.hasFlag(GridFlags.REQUIRE_CHANNEL)) continue;
                int expected = result.channelNodes().contains(node) ? 1 : 0;
                assertEquals(expected, result.nodeFlow().getInt(node));
                assertEquals(expected, result.connectionFlow().getInt(node.getConnections().get(0)));
                total += expected;
            }
            assertEquals(result.channelNodes().size(), total);
        }

        private static Constructor<GridConnection> connectionConstructor() {
            try {
                var constructor = GridConnection.class.getDeclaredConstructor(GridNode.class, GridNode.class, Direction.class);
                constructor.setAccessible(true);
                return constructor;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
