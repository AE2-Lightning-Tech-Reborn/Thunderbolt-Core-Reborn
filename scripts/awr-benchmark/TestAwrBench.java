package com.moakiee.thunderbolt.core.crafting.planner;

import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Standalone small adapter regressions; run with the same classpath as AwrBench. */
public final class TestAwrBench {
    record Recipe(int output, long amount, int cost, long[][] byproducts, long[][] inputs) {}

    public static void main(String[] args) throws Exception {
        var joint = read(false,
                new Recipe(1, 3, 7, new long[][]{{2, 5}}, new long[][]{{0, 2}}),
                new Recipe(3, 1, 1, new long[0][], new long[][]{{1, 3}, {2, 5}}),
                new Recipe(4, 1, 0, new long[0][], new long[][]{{0, 1}}));
        check(joint.originals().size() == 3 && joint.recipes().size() == 4, "physical/projection counts");
        var a = joint.recipes().get(0);
        var b = joint.recipes().get(1);
        check(a.output() == 1 && b.output() == 2 && a.source() == b.source(), "shared source identity");
        check(a.inputs().equals(b.inputs()) && a.executionCost() == 7 && b.executionCost() == 7,
                "full inputs and cost on both anchors");
        check(a.byproducts().equals(List.of(CraftOutput.of(2, 5)))
                && b.byproducts().equals(List.of(CraftOutput.of(1, 3))), "complete alternate outputs");

        var bothAnchors = new CraftPlan<>(true, true, Map.of(a, 1L, b, 2L), Map.of(0, 6L),
                Map.<ReusableStockUsageKey<Integer>, Long>of(), Map.<Integer, Long>of(),
                Map.of(2, 15L), 1, false);
        var aggregated = AwrBench.physicalFirings(bothAnchors);
        check(aggregated.equals(Map.of(0, BigInteger.valueOf(3))), "sum both anchor counts");
        check(bothAnchors.executionCount().equals(BigInteger.valueOf(21)), "one weighted cost per physical execution");
        check(AwrBench.replay(joint, aggregated, Map.of(0, 6L), 2, 15).equals("pass"), "joint-output replay");
        check(AwrBench.replay(joint, aggregated, Map.of(0, 6L), 2, 16).equals("wrong_final_balance"),
                "no duplicate free byproducts");
        checkRow(joint, Map.of(0, 2L), 2, 5, 7, 1, 0); // Only the nonoriginal anchor is requested.
        checkRow(joint, Map.of(0, 2L), 3, 1, 8, 2, 0); // Both outputs fund one downstream recipe.
        checkRow(joint, Map.of(0, 1L), 4, 1, 0, 0, 1); // Explicit tag, no real execution.

        var seed = read(false, new Recipe(1, 2, 4, new long[][]{{2, 1}}, new long[][]{{1, 1}}));
        check(AwrBench.replay(seed, Map.of(0, BigInteger.TWO), Map.of(1, 1L), 2, 2).equals("pass"),
                "returned/growing seed replay includes all outputs");
        check(AwrBench.replay(seed, Map.of(0, BigInteger.TWO), Map.of(), 2, 2).equals("stuck_inconclusive"),
                "a balance-positive cycle cannot start without its seed");
        var freeInput = read(false, new Recipe(1, 2, 3, new long[][]{{2, 1}}, new long[0][]));
        check(AwrBench.replay(freeInput, Map.of(0, BigInteger.ONE), Map.of(), 2, 1).equals("pass"),
                "zero-input multi-output recipe");
        var legacy = read(true, new Recipe(1, 1, 1, new long[0][], new long[][]{{0, 1}}),
                new Recipe(4, 1, 0, new long[0][], new long[][]{{0, 1}}));
        check(legacy.recipes().get(0).executionCost() == 1 && legacy.recipes().get(1).executionCost() == 0,
                "legacy binary retains real versus tag cost");
        reject(new Recipe(1, 1, 0, new long[0][], new long[][]{{0, 1}}));
        reject(new Recipe(1, 1, 1, new long[][]{{1, 2}}, new long[][]{{0, 1}}));
        reject(new Recipe(4, 1, 0, new long[][]{{2, 1}}, new long[][]{{0, 1}}));
        System.out.println("PASS AWR adapter: legacy, weighted multi-output anchors, summed physical firings, tags, replay, malformed input");
    }

    static void checkRow(AwrBench.InputGraph input, Map<Integer, Long> stock, int target, long amount,
                         long cost, long executions, long tags) {
        var builder = CraftGraph.<Integer>builder();
        input.recipes().forEach(builder::pattern);
        stock.forEach(builder::stock);
        var row = AwrBench.run(input, builder.build(), stock,
                new AwrBench.Query(target, "fixture", amount), "additional", 10_000_000_000L);
        check(row.get("status").equals("ok") && Boolean.TRUE.equals(row.get("balance_ok"))
                && row.get("replay").equals("pass") && Boolean.TRUE.equals(row.get("cost_consistent")),
                "certified fixture target " + target + ": " + row);
        check(row.get("cost").equals(BigInteger.valueOf(cost))
                && row.get("recipe_exec").equals(BigInteger.valueOf(executions))
                && row.get("tag_exec").equals(BigInteger.valueOf(tags)), "cost breakdown: " + row);
    }

    static AwrBench.InputGraph read(boolean legacy, Recipe... recipes) throws IOException {
        var path = Files.createTempFile("awr-adapter-test-", ".bin");
        try {
            try (var out = new DataOutputStream(Files.newOutputStream(path))) {
                if (!legacy) { out.writeInt(AwrBench.NORMALIZED_MAGIC); out.writeInt(AwrBench.NORMALIZED_VERSION); }
                out.writeInt(4); out.writeInt(5); out.writeInt(recipes.length);
                for (var recipe : recipes) {
                    out.writeInt(recipe.output); out.writeLong(recipe.amount);
                    if (!legacy) {
                        out.writeInt(recipe.cost); out.writeInt(recipe.byproducts.length);
                        for (var extra : recipe.byproducts) { out.writeInt((int) extra[0]); out.writeLong(extra[1]); }
                    }
                    out.writeInt(recipe.inputs.length);
                    for (var item : recipe.inputs) { out.writeInt((int) item[0]); out.writeLong(item[1]); }
                }
            }
            return AwrBench.readGraph(path);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    static void reject(Recipe recipe) throws IOException {
        try { read(false, recipe); }
        catch (IOException expected) { return; }
        throw new AssertionError("malformed recipe accepted: " + recipe);
    }

    static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
