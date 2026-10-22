import com.moakiee.thunderbolt.core.crafting.big.BigExecutionProgram;
import com.moakiee.thunderbolt.core.crafting.big.BigCraftingPlanner;
import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftOutput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPattern;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Measures the actual exact execution certificate, including its final independent verification. */
public final class BigExecutionBenchmark {
    private static final BigInteger N = BigInteger.TEN.pow(100);
    private static final long WARMUP_NS = Long.getLong("benchmark.warmupMillis", 1_000L) * 1_000_000L;
    private static final long SAMPLE_NS = Long.getLong("benchmark.sampleMillis", 100L) * 1_000_000L;
    private static volatile long sink;

    private record Scenario(String name, Map<CraftPattern<String>, BigInteger> counts,
                            Map<String, BigInteger> stock, boolean supported,
                            CraftGraph<String> graph, String target) {
        Scenario(String name, Map<CraftPattern<String>, BigInteger> counts,
                 Map<String, BigInteger> stock, boolean supported) {
            this(name, counts, stock, supported, null, null);
        }
    }

    public static void main(String[] args) throws Exception {
        var scenarios = new ArrayList<Scenario>();
        scenarios.add(new Scenario("empty", Map.of(), Map.of(), true));
        scenarios.add(new Scenario("input-free",
                Map.of(new CraftPattern<>("T", 1, List.of(), "free"), N), Map.of(), true));
        for (int length : new int[]{1, 32, 128}) scenarios.add(chain(length, false));
        for (int length : new int[]{32, 128, 512, 640}) scenarios.add(chain(length, true));
        scenarios.add(cycle(true));
        scenarios.add(cycle(false));
        scenarios.add(catalysts());
        for (int length : new int[]{32, 128}) {
            var scenario = chain(length, false);
            var builder = CraftGraph.<String>builder().stockExact("p0", N);
            scenario.counts().keySet().forEach(builder::pattern);
            scenarios.add(new Scenario("plan-chain-" + length, scenario.counts(), scenario.stock(), true,
                    builder.build(), "p" + length));
        }
        for (var scenario : scenarios) measure(scenario);
    }

    private static Scenario chain(int length, boolean reverse) {
        var recipes = new ArrayList<CraftPattern<String>>();
        for (int i = 1; i <= length; i++) {
            recipes.add(new CraftPattern<>("p" + i, 1,
                    List.of(CraftInput.of("p" + (i - 1), 1)), "chain" + i));
        }
        if (reverse) Collections.reverse(recipes);
        var counts = new LinkedHashMap<CraftPattern<String>, BigInteger>();
        for (var recipe : recipes) counts.put(recipe, N);
        return new Scenario("chain-" + length + (reverse ? "-reverse" : "-forward"),
                counts, Map.of("p0", N), !reverse || length <= 512);
    }

    private static Scenario cycle(boolean seeded) {
        var counts = new LinkedHashMap<CraftPattern<String>, BigInteger>();
        counts.put(new CraftPattern<>("B", 1,
                List.of(CraftInput.of("A", 1), CraftInput.of("raw", 1)), "cycle-a"), N);
        counts.put(new CraftPattern<>("T", 1, List.of(CraftInput.of("B", 1)),
                List.of(CraftOutput.of("A", 1)), "cycle-b"), N);
        return new Scenario("cycle-" + (seeded ? "seeded" : "unseeded"), counts,
                seeded ? Map.of("A", BigInteger.ONE, "raw", N) : Map.of("raw", N), seeded);
    }

    private static Scenario catalysts() {
        var counts = new LinkedHashMap<CraftPattern<String>, BigInteger>();
        var stock = new LinkedHashMap<String, BigInteger>();
        stock.put("tool", BigInteger.ONE);
        for (int i = 0; i < 32; i++) {
            stock.put("full" + i, N);
            counts.put(new CraftPattern<>("out" + i, 1,
                    List.of(CraftInput.returned("tool", 1),
                            CraftInput.consumedReturning("full" + i, 1, "empty" + i)),
                    "catalyst" + i), N);
        }
        return new Scenario("catalysts-containers-32", counts, stock, true);
    }

    private static Optional<BigExecutionProgram<String>> execute(Scenario scenario) {
        Optional<BigExecutionProgram<String>> result;
        if (scenario.graph() == null) {
            result = BigExecutionProgram.certify(scenario.counts(), scenario.stock());
        } else {
            var planned = BigCraftingPlanner.plan(scenario.graph(), scenario.target(), N, scenario.stock());
            if (!planned.executable()) throw new AssertionError("Planning failed: " + scenario.name());
            result = Optional.of(planned.program());
        }
        if (result.isPresent() != scenario.supported()) throw new AssertionError(scenario.name());
        if (result.isPresent()) {
            var program = result.orElseThrow();
            sink = program.blocks().size() + program.required().hashCode() + program.delta().hashCode();
        } else sink = -1;
        return result;
    }

    private static void measure(Scenario scenario) throws Exception {
        String proof = signature(scenario, execute(scenario));
        long warmupEnd = System.nanoTime() + WARMUP_NS;
        while (System.nanoTime() < warmupEnd) execute(scenario);
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int sample = 0; sample < 7; sample++) {
            long beforeBytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            long operations = 0;
            do {
                execute(scenario);
                operations++;
            } while (System.nanoTime() - start < SAMPLE_NS);
            long elapsed = System.nanoTime() - start;
            long allocated = bean.getThreadAllocatedBytes(thread) - beforeBytes;
            System.out.printf(Locale.ROOT,
                    "{\"scenario\":\"%s\",\"sample\":%d,\"operations\":%d,\"ns_per_op\":%.3f,\"bytes_per_op\":%.3f,\"signature\":\"%s\"}%n",
                    scenario.name(), sample, operations, (double) elapsed / operations,
                    (double) allocated / operations, proof);
        }
        if (!proof.equals(signature(scenario, execute(scenario)))) throw new AssertionError("Non-deterministic certificate");
    }

    /** Ordered blocks, exact counts and all material summaries must agree across both versions. */
    private static String signature(Scenario scenario, Optional<BigExecutionProgram<String>> result) throws Exception {
        if (result.isEmpty()) return "declined";
        var program = result.orElseThrow();
        var text = new StringBuilder();
        text.append(new TreeMap<>(program.required())).append(new TreeMap<>(program.delta()));
        if (scenario.graph() != null) {
            // ExactCraftPlan copies an identity-keyed map, so JVMs may partition the same chain
            // into different blocks. Independently check every ordered firing and normalize only
            // those block boundaries for whole-planner comparisons.
            if (!program.required().equals(Map.of("p0", N))
                    || !program.delta().equals(Map.of("p0", N.negate(), scenario.target(), N))) {
                throw new AssertionError("Wrong chain material balance");
            }
            String previous = "p0";
            int count = 0;
            for (var block : program.blocks()) {
                if (!block.repetitions().equals(BigInteger.ONE)) throw new AssertionError("Repeated chain block");
                for (var step : block.steps()) {
                    var recipe = step.recipe();
                    String output = "p" + (++count);
                    if (!step.copies().equals(N) || !recipe.output().equals(output)
                            || !recipe.exactOutputAmount().equals(BigInteger.ONE)
                            || !recipe.byproducts().isEmpty() || recipe.inputs().size() != 1
                            || !recipe.inputs().getFirst().key().equals(previous)
                            || !recipe.inputs().getFirst().exactAmount().equals(BigInteger.ONE)
                            || recipe.inputs().getFirst().returned()) {
                        throw new AssertionError("Wrong chain firing at " + output);
                    }
                    text.append(';').append(previous).append("->").append(output).append(':').append(step.copies());
                    previous = output;
                }
            }
            if (!previous.equals(scenario.target())) throw new AssertionError("Incomplete chain");
            return digest(text);
        }
        for (var block : program.blocks()) {
            text.append('|').append(block.repetitions()).append(new TreeMap<>(block.required()))
                    .append(new TreeMap<>(block.delta()));
            for (var step : block.steps()) text.append(';').append(step.recipe().source()).append(':').append(step.copies());
        }
        return digest(text);
    }

    private static String digest(StringBuilder text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
