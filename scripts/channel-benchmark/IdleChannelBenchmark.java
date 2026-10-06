import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import appeng.api.networking.pathing.ChannelMode;
import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.core.channel.BaselineBorrowedCapacityCalculator;
import com.moakiee.thunderbolt.core.channel.BorrowedCapacityCalculator;

/** Real AE2 fixtures for zero-demand discovery, with an independently compiled baseline. */
public class IdleChannelBenchmark {
    static BorrowedCapacityCalculator.Result solve(String variant, ChannelStress.Graph graph) {
        if (variant.equals("before")) {
            var result = BaselineBorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources);
            return new BorrowedCapacityCalculator.Result(result.channelNodes(), result.networkNodes(),
                    result.nodeFlow(), result.connectionFlow());
        }
        return BorrowedCapacityCalculator.assignChannels(graph.grid, graph.sources);
    }

    static ChannelStress.Graph graph(String scenario, int count) {
        var graph = new ChannelStress.Graph(scenario, ChannelMode.X4);
        var relays = new ArrayList<ChannelStress.Node>();
        int width = (int) Math.ceil(Math.sqrt(count));
        for (int i = 0; i < count; i++) {
            var relay = graph.relay();
            relays.add(relay);
            if (scenario.equals("idle-mesh")) {
                if (i % width > 0) graph.link(relay, relays.get(i - 1));
                if (i >= width) graph.link(relay, relays.get(i - width));
            } else if (i > 0) {
                graph.link(relays.get(i - 1), relay);
            }
        }
        ChannelStress.Node previous = null;
        for (int i = 0; i < 266; i++) {
            ChannelStress.Node source;
            if (scenario.equals("idle-vanilla")) {
                source = graph.node(ChannelStress.vanillaOwner(), ChannelStress.INF);
                graph.excluded++;
            } else {
                source = graph.source();
            }
            if (previous != null) graph.link(previous, source);
            previous = source;
            graph.link(source, relays.get((int) ((long) i * count / 266)));
        }
        graph.expected = 0;
        return graph;
    }

    static void verifyIdle(ChannelStress.Graph graph, BorrowedCapacityCalculator.Result result) {
        ChannelStress.verify(graph, result);
        if (!result.channelNodes().isEmpty() || !result.nodeFlow().isEmpty()
                || !result.connectionFlow().isEmpty()) {
            throw new AssertionError("nonzero idle result");
        }
        for (var node : graph.nodes) {
            if (result.nodeFlow().getInt(node) != 0) throw new AssertionError("node default");
            if (result.networkNodes().contains(node)
                    == (node.getOwner() instanceof appeng.blockentity.networking.ControllerBlockEntity)) {
                throw new AssertionError("idle membership");
            }
        }
        for (var edge : graph.edges) {
            if (result.connectionFlow().getInt(edge.gc()) != 0) throw new AssertionError("edge default");
        }
    }

    static void compare(ChannelStress.Graph graph, boolean idle) {
        var before = solve("before", graph);
        var after = solve("after", graph);
        if (!before.channelNodes().equals(after.channelNodes())
                || !before.networkNodes().equals(after.networkNodes())
                || !before.nodeFlow().equals(after.nodeFlow())
                || !before.connectionFlow().equals(after.connectionFlow())) {
            throw new AssertionError("exact result mismatch: " + graph.name);
        }
        if (idle) {
            verifyIdle(graph, before);
            verifyIdle(graph, after);
        }
    }

    static void exact(int count) {
        for (int seed = 0; seed < count; seed++) compare(ChannelStress.random(seed), false);
        for (int seed = 0; seed < 500; seed++) {
            var random = new Random(seed);
            var graph = new ChannelStress.Graph("idle-random-" + seed, ChannelMode.DEFAULT);
            var root = graph.source();
            for (int i = 0; i < 1 + seed % 40; i++) {
                var relay = graph.relay();
                graph.link(relay, graph.nodes.get(random.nextInt(graph.nodes.size() - 1)));
            }
            for (int i = 0; i < 50; i++) {
                var a = graph.nodes.get(random.nextInt(graph.nodes.size()));
                var b = graph.nodes.get(random.nextInt(graph.nodes.size()));
                if (a != b) graph.link(a, b);
            }
            if (seed % 2 == 0) {
                var face = graph.node(ChannelStress.vanillaOwner(), ChannelStress.INF);
                graph.excluded++;
                graph.link(face, root);
            }
            graph.expected = 0;
            compare(graph, true);
        }
        System.out.println("EXACT DEMAND RESULTS PASS " + count);
        System.out.println("EXACT IDLE RESULTS PASS 500");
    }

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        CoreConfig.setChannelsPerController(128);
        ChannelSourceRegistry.registerController("idle-benchmark", ChannelStress.Source.class);
        if (args[0].equals("exact")) {
            exact(Integer.parseInt(args[1]));
            return;
        }
        String mode = args[0], scenario = args[1];
        var graph = graph(scenario, Integer.parseInt(args[2]));
        int warmup = Integer.parseInt(args[3]), samples = Integer.parseInt(args[4]);
        compare(graph, true);
        List<String> variants = mode.equals("paired") ? List.of("before", "after") : List.of(mode);
        for (int i = 0; i < warmup; i++) {
            for (String variant : variants) verifyIdle(graph, solve(variant, graph));
        }
        double[][] time = new double[variants.size()][samples];
        double[][] allocated = new double[variants.size()][samples];
        System.gc();
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < samples; i++) {
            for (int offset = 0; offset < variants.size(); offset++) {
                int index = i % 2 == 0 ? offset : variants.size() - 1 - offset;
                long bytes = ChannelStress.TM.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                var result = solve(variants.get(index), graph);
                time[index][i] = (System.nanoTime() - start) / 1e6;
                allocated[index][i] = (ChannelStress.TM.getThreadAllocatedBytes(thread) - bytes) / 1048576.0;
                verifyIdle(graph, result);
            }
        }
        for (int index = 0; index < variants.size(); index++) {
            System.out.printf(Locale.ROOT,
                    "RESULT {\"scenario\":\"%s\",\"nodes\":%d,\"edges\":%d,\"network_nodes\":%d,"
                            + "\"demand\":0,\"flow\":0,\"variant\":\"%s\",\"p50_ms\":%.3f,\"p95_ms\":%.3f,"
                            + "\"alloc_mib\":%.3f,\"samples_ms\":%s,\"status\":\"PASS\"}%n",
                    scenario, graph.nodes.size(), graph.edges.size(), graph.nodes.size() - graph.excluded,
                    variants.get(index), ChannelStress.pct(time[index], .5), ChannelStress.pct(time[index], .95),
                    ChannelStress.pct(allocated[index], .5), Arrays.toString(time[index]));
        }
    }
}
