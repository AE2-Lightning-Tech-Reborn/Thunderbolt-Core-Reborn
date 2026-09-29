package com.moakiee.thunderbolt.core.channel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ChannelFlowNetworkTest {
    private static final int INF = Integer.MAX_VALUE / 2;
    private record Arc(int id, int from, int to, int capacity) {}

    private static final class Network {
        final ChannelFlowNetwork graph;
        final List<Arc> arcs = new ArrayList<>();
        final int[] splits;
        final int source, sink;

        Network(int... capacities) {
            source = capacities.length * 2;
            sink = source + 1;
            graph = new ChannelFlowNetwork(sink + 1);
            splits = new int[capacities.length];
            for (int v = 0; v < capacities.length; v++) splits[v] = edge(v * 2, v * 2 + 1, capacities[v]);
        }

        int edge(int from, int to, int capacity) {
            int id = graph.edgeCount();
            graph.addEdge(from, to, capacity);
            arcs.add(new Arc(id, from, to, capacity));
            return id;
        }

        void connect(int a, int b, int capacity) {
            edge(a * 2 + 1, b * 2, capacity);
            edge(b * 2 + 1, a * 2, capacity);
        }

        void supply(int v, int capacity) { edge(source, v * 2, capacity); }
        void demand(int v, int capacity) { edge(v * 2 + 1, sink, capacity); }

        int check(int limit, boolean forcePush) {
            long expected = Math.min(limit, oracle(sink + 1, source, sink, arcs));
            int initial = BidirectionalFlowSeed.assign(graph, source, sink, splits, limit);
            certificate(graph, arcs, source, sink, initial, false);
            int additional = forcePush ? graph.pushRemaining(source, sink, limit - initial)
                    : graph.maxFlow(source, sink, limit - initial);
            assertEquals(expected, initial + additional);
            certificate(graph, arcs, source, sink, initial + additional, initial + additional < limit);
            return initial + additional;
        }
    }

    @Test
    void independentPathsAddButSharedNodeRemainsABottleneck() {
        for (int paths = 1; paths <= 2; paths++) {
            for (boolean shared : new boolean[]{false, true}) {
                var n = new Network(INF, shared ? 8 : INF, 8, 8, INF);
                n.connect(0, 1, INF);
                n.connect(1, 2, INF);
                n.connect(2, 4, INF);
                if (paths == 2) {
                    n.connect(1, 3, INF);
                    n.connect(3, 4, INF);
                }
                n.supply(0, 32);
                n.demand(4, 32);
                assertEquals(shared ? 8 : paths * 8, n.check(INF, false));
            }
        }
    }

    @Test
    void actualParallelConnectionsAreKeptAndSourceFacesSumIndividually() {
        var n = new Network(32, 32);
        n.connect(0, 1, 8);
        n.connect(0, 1, 8);
        n.supply(0, 8);
        n.supply(0, 8);
        n.demand(1, 24);
        assertEquals(16, n.check(INF, false));
    }

    @Test
    void residualCompletionMustUndoAnEarlierRoute() {
        // Initial matching s-a-x-t blocks s-b-x-t. Completion must reroute a to y.
        var n = new Network(INF, 1, 1, 1, 1);
        n.connect(0, 1, INF);
        n.connect(0, 2, INF);
        n.connect(1, 3, INF);
        n.connect(1, 4, INF);
        n.connect(2, 3, INF);
        n.supply(0, 2);
        n.demand(3, 1);
        n.demand(4, 1);
        // Explicit feasible warm start so coverage does not depend on traversal order.
        int[][] route = {{n.source, 0}, {0, 1}, {1, 2}, {2, 3}, {3, 6}, {6, 7}, {7, n.sink}};
        for (var step : route) {
            var arc = n.arcs.stream().filter(a -> a.from == step[0] && a.to == step[1]).findFirst().orElseThrow();
            n.graph.push(arc.id, 1);
        }
        certificate(n.graph, n.arcs, n.source, n.sink, 1, false);
        assertEquals(1, n.graph.pushRemaining(n.source, n.sink, 1));
        certificate(n.graph, n.arcs, n.source, n.sink, 2, true);
    }

    @Test
    void multipleSourcesAndDemandsShareANarrowBridge() {
        var n = new Network(INF, INF, 8, INF, INF);
        n.connect(0, 2, INF);
        n.connect(1, 2, INF);
        n.connect(2, 3, INF);
        n.connect(2, 4, INF);
        n.supply(0, 8);
        n.supply(1, 8);
        n.demand(3, 8);
        n.demand(4, 8);
        assertEquals(8, n.check(INF, true));
    }

    @Test
    void asymmetricPlacementAndDisconnectedDemandsReturnUnusedExcess() {
        var n = new Network(INF, 8, INF, 0, INF);
        n.connect(0, 1, INF);
        n.connect(1, 2, INF);
        n.connect(2, 3, INF);
        n.connect(3, 4, INF);
        n.supply(0, 512);
        n.demand(2, 32);
        n.demand(4, 32);
        assertEquals(8, n.check(INF, true));
    }

    @Test
    void excessAndDemandSumsDoNotOverflowAndGlobalLimitIsRespected() {
        var n = new Network(INF, INF, INF, INF);
        n.connect(0, 1, INF);
        n.connect(1, 2, INF);
        n.connect(2, 3, INF);
        for (int i = 0; i < 4; i++) {
            n.supply(i, INF);
            n.demand(i, INF);
        }
        assertEquals(INF, n.check(INF, false));
        var graph = new ChannelFlowNetwork(4);
        var arcs = new ArrayList<Arc>();
        for (int i = 1; i <= 2; i++) {
            arcs.add(new Arc(graph.edgeCount(), 0, i, INF)); graph.addEdge(0, i, INF);
            arcs.add(new Arc(graph.edgeCount(), i, 3, INF)); graph.addEdge(i, 3, INF);
        }
        assertEquals(17, graph.pushRemaining(0, 3, 17));
        certificate(graph, arcs, 0, 3, 17, false);
    }

    @Test
    void bidirectionalCirculationCancellationAlsoUpdatesBothNodeGates() {
        var n = new Network(8, 8);
        n.connect(0, 1, 8);
        for (int e : new int[]{0, 2, 4, 6}) n.graph.push(e, 5);
        certificate(n.graph, n.arcs, n.source, n.sink, 0, true);
        for (int e : new int[]{4, 6, 0, 2}) n.graph.cancelFlow(e, 5);
        for (var arc : n.arcs) assertEquals(arc.capacity, n.graph.residual(arc.id));
        certificate(n.graph, n.arcs, n.source, n.sink, 0, true);
    }

    @Test
    void longChainAndCombNeedNoRecursivePathWalk() {
        int size = 20_000;
        int[] capacities = new int[size];
        Arrays.fill(capacities, INF);
        var n = new Network(capacities);
        for (int i = 1; i < size; i++) n.connect(i - 1, i, INF);
        n.supply(0, size);
        for (int i = 0; i < size; i++) n.demand(i, 1);
        int initial = BidirectionalFlowSeed.assign(n.graph, n.source, n.sink, n.splits, size);
        assertEquals(size, initial);
        certificate(n.graph, n.arcs, n.source, n.sink, size, true);
    }

    @Test
    void sameCapacityRingBalancesDistantSupplyInOneSeedPass() {
        var n = new Network(128, 128, 128, 128, 128, 128);
        for (int i = 0; i < 6; i++) n.connect(i, (i + 1) % 6, 128);
        n.supply(0, 32);
        n.supply(1, 32);
        n.demand(3, 32);
        n.demand(4, 32);
        assertEquals(64, BidirectionalFlowSeed.assign(n.graph, n.source, n.sink, n.splits, INF));
        certificate(n.graph, n.arcs, n.source, n.sink, 64, true);
    }

    @Test
    void thousandsOfRandomPhysicalNetworksMatchIndependentOracleAndCertificates() {
        int[] choices = {0, 1, 8, 32, 128, INF};
        for (int seed = 0; seed < 3_000; seed++) {
            Random random = new Random(seed);
            int count = 2 + random.nextInt(35);
            int[] capacities = new int[count];
            for (int i = 0; i < count; i++) capacities[i] = choices[random.nextInt(choices.length)];
            var n = new Network(capacities);
            for (int i = 0; i < count * 3; i++) {
                int a = random.nextInt(count), b = random.nextInt(count);
                if (a != b) n.connect(a, b, choices[random.nextInt(choices.length)]);
            }
            for (int i = 0; i < count; i++) {
                if (random.nextInt(3) == 0) n.supply(i, 1 + random.nextInt(512));
                if (random.nextBoolean()) n.demand(i, 1 + random.nextInt(40));
            }
            try {
                n.check(seed % 3 == 0 ? random.nextInt(100) : INF, seed % 2 == 0);
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError("physical seed=" + seed, e);
            }
        }
    }

    @Test
    void directedResidualKernelMatchesOracleIncludingOpposingAndParallelArcs() {
        for (int seed = 0; seed < 2_000; seed++) {
            Random random = new Random(seed);
            int count = 3 + random.nextInt(25);
            var graph = new ChannelFlowNetwork(count);
            var arcs = new ArrayList<Arc>();
            for (int i = 0; i < count * 4; i++) {
                int a = random.nextInt(count), b = random.nextInt(count), cap = random.nextInt(50);
                if (a == b) continue;
                arcs.add(new Arc(graph.edgeCount(), a, b, cap));
                graph.addEdge(a, b, cap);
            }
            int limit = seed % 3 == 0 ? random.nextInt(50) : INF;
            int actual = seed % 2 == 0 ? graph.maxFlow(0, count - 1, limit)
                    : graph.pushRemaining(0, count - 1, limit);
            assertEquals(Math.min(limit, oracle(count, 0, count - 1, arcs)), actual, "seed=" + seed);
            certificate(graph, arcs, 0, count - 1, actual, actual < limit);
        }
    }

    private static void certificate(ChannelFlowNetwork graph, List<Arc> arcs,
                                    int source, int sink, int flow, boolean maximum) {
        long[] balance = new long[graph.head.length];
        for (var arc : arcs) {
            int f = arc.capacity - graph.residual(arc.id);
            assertTrue(f >= 0 && f <= arc.capacity, "capacity at edge " + arc.id);
            assertEquals(f, graph.residual(arc.id ^ 1), "residual pair " + arc.id);
            balance[arc.from] -= f;
            balance[arc.to] += f;
        }
        for (int v = 0; v < balance.length; v++) {
            assertEquals(v == source ? -flow : v == sink ? flow : 0, balance[v], "conservation at " + v);
        }
        if (!maximum) return;
        boolean[] reachable = new boolean[balance.length];
        var queue = new ArrayDeque<Integer>();
        reachable[source] = true;
        queue.add(source);
        while (!queue.isEmpty()) {
            int v = queue.remove();
            for (int e = graph.head[v]; e != -1; e = graph.next[e]) {
                int u = graph.to[e];
                if (graph.residual(e) > 0 && !reachable[u]) {
                    reachable[u] = true;
                    queue.add(u);
                }
            }
        }
        assertFalse(reachable[sink], "residual augmenting path remains");
        long cut = 0;
        for (var arc : arcs) if (reachable[arc.from] && !reachable[arc.to]) cut += arc.capacity;
        assertEquals(flow, cut, "max-flow/min-cut certificate");
    }

    /** Matrix Edmonds-Karp deliberately independent of the production representation. */
    private static long oracle(int vertices, int source, int sink, List<Arc> arcs) {
        long[][] residual = new long[vertices][vertices];
        for (var arc : arcs) residual[arc.from][arc.to] += arc.capacity;
        long result = 0;
        int[] parent = new int[vertices];
        while (true) {
            Arrays.fill(parent, -1);
            parent[source] = source;
            var queue = new ArrayDeque<Integer>();
            queue.add(source);
            while (!queue.isEmpty() && parent[sink] < 0) {
                int v = queue.remove();
                for (int u = 0; u < vertices; u++) {
                    if (parent[u] < 0 && residual[v][u] > 0) {
                        parent[u] = v;
                        queue.add(u);
                    }
                }
            }
            if (parent[sink] < 0) return result;
            long amount = Long.MAX_VALUE;
            for (int v = sink; v != source; v = parent[v]) amount = Math.min(amount, residual[parent[v]][v]);
            for (int v = sink; v != source; v = parent[v]) {
                residual[parent[v]][v] -= amount;
                residual[v][parent[v]] += amount;
            }
            result += amount;
        }
    }
}
