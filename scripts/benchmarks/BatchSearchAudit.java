package com.moakiee.thunderbolt.core.crafting.planner;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.*;
import java.util.function.IntPredicate;

/** Fixed small-route workloads; compare compiled baseline and candidate in separate JVMs. */
public final class BatchSearchAudit {
    static final boolean TWO_ROUTES = Boolean.getBoolean("audit.twoRoutes");
    record Sample(CraftGraph<String> graph, long amount, CraftPlan<String> initial) {}

    static List<Sample> samples() {
        var result = new ArrayList<Sample>();
        var random = new Random(2026101501);
        for (int id = 0; id < Integer.getInteger("audit.samples", 512); id++) {
            int size = 3 + id % 4, resources = 2 + id % 3, count = 12 + random.nextInt(53);
            long scale = id % 8 == 0 ? 1_000_000_000_000L : 1;
            var graph = CraftGraph.<String>builder();
            var used = new LinkedHashMap<String, Long>();
            for (int r = 0; r < resources; r++) {
                used.put("R" + r, 3 * count * scale);
                graph.stock("R" + r, 3 * count * scale);
            }
            for (int p = 0; p < size; p++) {
                var inputs = new ArrayList<CraftInput<String>>();
                for (int r = 0; r < resources; r++)
                    inputs.add(CraftInput.of("R" + r, (p == 0 ? 3 : random.nextInt(36) + 1) * scale));
                graph.pattern("T", (p == 0 ? 1 : random.nextInt(12) + 1) * scale, inputs);
            }
            var built = graph.build();
            if (TWO_ROUTES) built = built.withPatterns(Map.of("T", built.patternsFor("T").subList(0, 2)));
            long amount = count * scale;
            var initial = new CraftPlan<String>(true, true, Map.of(built.patternsFor("T").getFirst(), (long) count),
                    Map.copyOf(used), Map.of(), Map.of(), Map.of("T", amount), resources + 1, false);
            result.add(new Sample(built, amount, initial));
        }
        return result;
    }

    static String quantities(Map<String, Long> values) { return new TreeMap<>(values).toString().replace(',', ';'); }

    static String counts(Sample sample, CraftPlan<String> plan) {
        return sample.graph.patternsFor("T").stream().map(p -> plan.firings().getOrDefault(p, 0L).toString())
                .reduce((a, b) -> a + ";" + b).orElse("");
    }

    static void verify(Sample sample, CraftPlan<String> plan) {
        var used = new HashMap<String, BigInteger>();
        var produced = BigInteger.ZERO;
        for (var e : plan.firings().entrySet()) {
            if (e.getValue() <= 0 || sample.graph.patternsFor("T").stream().noneMatch(p -> p == e.getKey()))
                throw new AssertionError("foreign pattern or nonpositive firing");
            var n = BigInteger.valueOf(e.getValue());
            produced = produced.add(e.getKey().exactOutputAmount().multiply(n));
            for (var input : e.getKey().inputs()) used.merge(input.key(), input.exactAmount().multiply(n), BigInteger::add);
        }
        if (!plan.feasible() || !plan.missing().isEmpty() || produced.compareTo(BigInteger.valueOf(sample.amount)) < 0)
            throw new AssertionError("invalid output");
        for (var e : used.entrySet()) if (e.getValue().compareTo(BigInteger.valueOf(sample.initial.usedStock().get(e.getKey()))) > 0
                || !e.getValue().equals(BigInteger.valueOf(plan.usedStock().getOrDefault(e.getKey(), 0L))))
            throw new AssertionError("invalid stock");
        // All routes consume only raw stock. Reconstruct a second executable certificate too.
        var replay = MaterialDagReplay.tryPlan(sample.graph, plan.firings(), "T", sample.amount);
        if (replay == null || !replay.feasible() || !replay.usedStock().equals(plan.usedStock()))
            throw new AssertionError("invalid replay");
    }

    public static void main(String[] args) {
        var samples = samples();
        int warmup = Integer.getInteger("audit.warmupRounds", 20);
        int measurements = Integer.getInteger("audit.measurementRounds", 10);
        for (int pass = 0; pass < warmup; pass++) for (var s : samples)
            solve(s, n -> true);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        System.out.println("case,status,executions,stock,counts,work,ns,allocated");
        for (int pass = 0; pass < measurements; pass++) for (int id = 0; id < samples.size(); id++) {
            var s = samples.get(id);
            int[] work = {0};
            long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            var result = solve(s, n -> { work[0] += n; return true; });
            long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(thread) - before;
            verify(s, result.plan());
            System.out.printf(Locale.ROOT, "%d-%d,%s,%d,%s,%s,%d,%d,%d%n", pass, id, result.status(),
                    result.plan().executionCount(), quantities(result.plan().usedStock()), counts(s, result.plan()), work[0], elapsed, allocated);
        }
    }

    static BatchOptimizationResult<String> solve(Sample sample, IntPredicate work) {
        return TWO_ROUTES ? TwoRouteBatchOptimizer.solve(sample.graph, "T", sample.amount, sample.initial, work)
                : MultiRouteBatchOptimizer.solve(sample.graph, "T", sample.amount, sample.initial, work);
    }
}
