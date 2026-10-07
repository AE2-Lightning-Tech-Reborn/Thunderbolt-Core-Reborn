package com.moakiee.thunderbolt.core.crafting.planner;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.moakiee.thunderbolt.api.crafting.PlanningAttemptContext;
import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.api.crafting.PlanningExitException;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Offline AWR adapter. Compile against the current checkout; no native solver or game startup. */
public final class AwrBench {
    record Query(int target, String name, long amount) {}
    record PhysicalRecipe(int id, List<CraftInput<Integer>> inputs,
                          List<CraftOutput<Integer>> outputs, int executionCost) {
        PhysicalRecipe {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }
    record InputGraph(int nReal, int nItems, List<PhysicalRecipe> originals,
                      List<CraftPattern<Integer>> recipes) {}
    static final int NORMALIZED_MAGIC = -0x415752;
    static final int NORMALIZED_VERSION = 2;
    static final Gson JSON = new Gson();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: AwrBench config.json");
        JsonObject config = JSON.fromJson(Files.readString(Path.of(args[0])), JsonObject.class);
        Path output = Path.of(config.get("output_path").getAsString());
        String policy = config.get("target_stock_policy").getAsString();
        if (!policy.equals("additional") && !policy.equals("available"))
            throw new IllegalArgumentException("invalid target_stock_policy");
        int warmup = config.get("warmup_passes").getAsInt(), repeats = config.get("repeats").getAsInt();
        double seconds = config.get("time_limit_seconds").getAsDouble();
        if (warmup < 0 || repeats < 1 || !Double.isFinite(seconds) || seconds <= 0 || seconds > 3600)
            throw new IllegalArgumentException("invalid benchmark limits");
        long limitNanos = (long) (seconds * 1_000_000_000.0);
        long loadStart = System.nanoTime();
        InputGraph input = readGraph(Path.of(config.get("input_path").getAsString()));
        List<Query> queries = readQueries(Path.of(config.get("targets_path").getAsString()), input.nItems);
        var stocks = new LinkedHashMap<String, Map<Integer, Long>>();
        var graphs = new LinkedHashMap<String, CraftGraph<Integer>>();
        for (var entry : config.getAsJsonObject("stock_paths").entrySet()) {
            var stock = readStock(Path.of(entry.getValue().getAsString()), input.nItems);
            var builder = CraftGraph.<Integer>builder();
            input.recipes.forEach(builder::pattern);
            stock.forEach(builder::stock);
            stocks.put(entry.getKey(), stock);
            graphs.put(entry.getKey(), builder.build());
        }
        config.addProperty("load_ms", millis(loadStart));
        config.addProperty("java", System.getProperty("java.runtime.version"));
        config.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        var properties = new TreeMap<String, String>();
        System.getProperties().forEach((key, value) -> {
            if (key.toString().startsWith("thunderbolt.")) properties.put(key.toString(), value.toString());
        });
        config.add("overrides", JSON.toJsonTree(properties));
        config.addProperty("query_count_per_pass", queries.size() * stocks.size());
        config.addProperty("physical_recipe_count", input.originals.size());
        config.addProperty("projected_pattern_count", input.recipes.size());
        Files.createDirectories(output.getParent());
        try (var out = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
            out.println(JSON.toJson(config));
            for (int pass = -warmup; pass < repeats; pass++) {
                int done = 0;
                for (var group : graphs.entrySet()) for (Query query : queries) {
                    Map<String, Object> row = run(input, group.getValue(), stocks.get(group.getKey()), query, policy, limitNanos);
                    row.put("type", pass < 0 ? "warmup" : "query");
                    row.put("dataset", config.get("dataset").getAsString());
                    row.put("profile", "tb-v2"); row.put("config", "tb-v2");
                    row.put("stage", "-");
                    row.put("stock", group.getKey()); row.put("target", query.target + 1);
                    row.put("name", query.name); row.put("target_name", query.name); row.put("amount", query.amount);
                    row.put("repeat", pass); row.put("warmup", pass < 0);
                    out.println(JSON.toJson(row)); out.flush();
                    if (out.checkError()) throw new IOException("failed writing " + output);
                    if (++done % 8 == 0) System.out.printf("PROGRESS %s pass=%d %d/%d%n",
                            config.get("dataset").getAsString(), pass, done, queries.size() * stocks.size());
                }
            }
        }
        System.out.println("DONE " + output);
    }

    static InputGraph readGraph(Path path) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int first = in.readInt();
            boolean extended = first == NORMALIZED_MAGIC;
            if (extended && in.readInt() != NORMALIZED_VERSION)
                throw new IOException("unsupported normalized schema");
            // Read the original unversioned v1 intermediate for saved benchmark compatibility.
            int real = extended ? in.readInt() : first, items = in.readInt(), count = in.readInt();
            if (real <= 0 || items < real || count < 0) throw new IOException("invalid graph counts");
            var originals = new ArrayList<PhysicalRecipe>(count);
            var recipes = new ArrayList<CraftPattern<Integer>>(count);
            for (int r = 0; r < count; r++) {
                int output = in.readInt(); long amount = in.readLong();
                int cost = extended ? in.readInt() : output >= real ? 0 : 1;
                if (output < 0 || output >= items || amount <= 0 || cost < 0)
                    throw new IOException("invalid recipe " + r);
                var outputs = new ArrayList<CraftOutput<Integer>>();
                outputs.add(CraftOutput.of(output, amount));
                var outputKeys = new HashSet<Integer>();
                outputKeys.add(output);
                int byproductCount = extended ? in.readInt() : 0;
                if (byproductCount < 0 || byproductCount >= items)
                    throw new IOException("invalid byproduct count in recipe " + r);
                for (int j = 0; j < byproductCount; j++) {
                    int item = in.readInt(); long quantity = in.readLong();
                    if (item < 0 || item >= real || quantity <= 0 || !outputKeys.add(item))
                        throw new IOException("invalid byproduct in recipe " + r);
                    outputs.add(CraftOutput.of(item, quantity));
                }
                int n = in.readInt();
                if (n < 0 || n > items) throw new IOException("invalid input count in recipe " + r);
                var inputs = new ArrayList<CraftInput<Integer>>(n);
                var seen = new HashSet<Integer>();
                for (int j = 0; j < n; j++) {
                    int item = in.readInt(); long quantity = in.readLong();
                    if (item < 0 || item >= items || quantity <= 0 || !seen.add(item))
                        throw new IOException("invalid input in recipe " + r);
                    inputs.add(CraftInput.of(item, quantity));
                }
                var original = new PhysicalRecipe(r, inputs, outputs, cost);
                originals.add(original);
                if (output >= real) {
                    if (amount != 1 || n != 1 || inputs.get(0).amount() != 1
                            || inputs.get(0).key() >= real || byproductCount != 0 || cost != 0)
                        throw new IOException("recipe " + r + " is not a pure member-to-tag edge");
                    recipes.add(CraftPattern.tagConversion(inputs.get(0).key(), output, original));
                } else {
                    if (cost == 0) throw new IOException("real recipe " + r + " has zero cost");
                    // AW indexes every physical output as a producer. Each alternate anchor is
                    // one complete execution: same inputs, every other output, and one cost.
                    // Reuse this exact source object; Integer boxing does not preserve identity.
                    for (int anchor = 0; anchor < outputs.size(); anchor++) {
                        var primary = outputs.get(anchor);
                        var byproducts = new ArrayList<>(outputs);
                        byproducts.remove(anchor);
                        recipes.add(CraftPattern.weighted(primary.key(), primary.exactAmount(),
                                inputs, byproducts, original, cost));
                    }
                }
            }
            if (in.read() != -1) throw new IOException("trailing normalized bytes");
            return new InputGraph(real, items, List.copyOf(originals), List.copyOf(recipes));
        }
    }

    static List<Query> readQueries(Path path, int items) throws IOException {
        var result = new ArrayList<Query>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length < 3) throw new IOException("invalid query row");
            int target = Integer.parseInt(fields[0]) - 1;
            if (target < 0 || target >= items) throw new IOException("target outside item domain");
            for (String text : fields[2].split(",")) {
                long amount = Long.parseLong(text);
                if (amount <= 0) throw new IOException("nonpositive request");
                result.add(new Query(target, fields[1], amount));
            }
        }
        if (result.isEmpty()) throw new IOException("empty targets manifest");
        return result;
    }

    static Map<Integer, Long> readStock(Path path, int items) throws IOException {
        var result = new LinkedHashMap<Integer, Long>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length < 2) throw new IOException("invalid stock row");
            int item = Integer.parseInt(fields[0]) - 1; long amount = Long.parseLong(fields[1]);
            if (item < 0 || item >= items || amount < 0) throw new IOException("invalid stock entry");
            result.merge(item, amount, Math::addExact);
        }
        return result;
    }

    static Map<String, Object> run(InputGraph input, CraftGraph<Integer> graph, Map<Integer, Long> stock,
            Query query, String policy, long limitNanos) {
        var row = new LinkedHashMap<String, Object>();
        row.put("timeout", false);
        long targetStock = stock.getOrDefault(query.target, 0L);
        row.put("target_stock", targetStock);
        row.put("target_stock_policy", policy);
        long begin = System.nanoTime(), deadline = begin + limitNanos;
        PlanningAttemptContext context = new PlanningAttemptContext() {
            public long deadlineNanos() { return deadline; }
            public void checkpoint() {
                if (System.nanoTime() - deadline >= 0) throw new PlanningExitException("benchmark deadline");
            }
            public void report(PlanningDiagnosticSnapshot snapshot) {}
        };
        try {
            long effectiveAmount = policy.equals("additional") ? Math.addExact(query.amount, targetStock) : query.amount;
            row.put("effective_amount", effectiveAmount);
            PlanningResult<Integer> result;
            try (var ignored = PlanningCancellation.bind(context)) {
                result = CraftPlannerV2.planDetailed(graph, query.target, effectiveAmount);
            }
            row.put("plan_ms", millis(begin));
            var plan = result.plan();
            row.put("status", plan.feasible() ? "ok" : "missing");
            row.put("planner_feasible", plan.feasible());
            row.put("feasible", plan.feasible()); row.put("supported", plan.supported());
            row.put("budget_exhausted", plan.budgetExhausted());
            row.put("missing", handles(plan.missing())); row.put("used_stock", handles(plan.usedStock()));
            row.put("diagnostics", result.diagnostics());
            row.put("items_processed", plan.itemsProcessed());
            boolean inventoryValid = plan.usedReusableStock().isEmpty()
                    && plan.usedStock().entrySet().stream().allMatch(e ->
                            e.getValue() >= 0 && e.getValue() <= stock.getOrDefault(e.getKey(), 0L));
            boolean firingsValid = plan.firings().entrySet().stream().allMatch(e ->
                    e.getValue() > 0 && graph.patternsFor(e.getKey().output()).stream().anyMatch(p -> p == e.getKey())
                            && e.getKey().source() instanceof PhysicalRecipe original
                            && original.id >= 0 && original.id < input.originals.size()
                            && input.originals.get(original.id) == original);
            row.put("stock_within_inventory", inventoryValid);
            row.put("firings_valid", firingsValid);
            if (!firingsValid) {
                row.put("status", "invalid"); row.put("feasible", false); row.put("replay", "invalid_plan");
                return row;
            }
            // Sum, never deduplicate/max, the independent executions selected through different
            // anchors. Reconstruct cost and material flow from the original physical columns.
            var physicalFirings = physicalFirings(plan);
            BigInteger tagExec = BigInteger.ZERO, recipeExec = BigInteger.ZERO, cost = BigInteger.ZERO;
            var net = new HashMap<Integer, BigInteger>();
            var firings = new ArrayList<Object[]>();
            for (var entry : physicalFirings.entrySet()) {
                var original = input.originals.get(entry.getKey()); BigInteger times = entry.getValue();
                if (original.executionCost == 0) tagExec = tagExec.add(times);
                else recipeExec = recipeExec.add(times);
                cost = cost.add(times.multiply(BigInteger.valueOf(original.executionCost)));
                for (var output : original.outputs)
                    net.merge(output.key(), output.exactAmount().multiply(times), BigInteger::add);
                for (var ingredient : original.inputs)
                    net.merge(ingredient.key(), ingredient.exactAmount().multiply(times).negate(), BigInteger::add);
                firings.add(new Object[] {original.id, times});
            }
            boolean costValid = cost.equals(plan.executionCount());
            row.put("cost", cost); row.put("recipe_exec", recipeExec); row.put("tag_exec", tagExec);
            row.put("firings", firings); row.put("cost_consistent", costValid);
            var shortfalls = new TreeMap<Integer, BigInteger>();
            var keys = new HashSet<>(net.keySet()); keys.add(query.target);
            for (int key : keys) {
                BigInteger demand = key == query.target ? BigInteger.valueOf(effectiveAmount) : BigInteger.ZERO;
                BigInteger deficit = demand.subtract(net.getOrDefault(key, BigInteger.ZERO))
                        .subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L)));
                if (deficit.signum() > 0) shortfalls.put(key + 1, deficit);
            }
            row.put("balance_ok", shortfalls.isEmpty()); row.put("shortfalls", shortfalls);
            long validationStart = System.nanoTime();
            String replay = !inventoryValid || !costValid ? "invalid_plan"
                    : plan.feasible() ? replay(input, physicalFirings, plan.usedStock(), query.target, effectiveAmount)
                    : "not_feasible";
            row.put("replay", replay); row.put("replay_ms", millis(validationStart));
            if (!inventoryValid || !costValid
                    || plan.feasible() && (!shortfalls.isEmpty() || replay.equals("wrong_final_balance"))) {
                row.put("status", "invalid");
                row.put("feasible", false);
            }
        } catch (PlanningExitException timeout) {
            row.put("status", "timeout"); row.put("timeout", true);
            row.put("feasible", false); row.put("plan_ms", millis(begin));
        } catch (RuntimeException error) {
            row.put("status", "error"); row.put("feasible", false); row.put("plan_ms", millis(begin));
            row.put("error", error.toString());
            error.printStackTrace(System.err);
        }
        return row;
    }

    static <T> Map<Integer, T> handles(Map<Integer, T> values) {
        var result = new TreeMap<Integer, T>();
        values.forEach((key, value) -> result.put(key + 1, value));
        return result;
    }

    static Map<Integer, BigInteger> physicalFirings(CraftPlan<Integer> plan) {
        var result = new TreeMap<Integer, BigInteger>();
        for (var entry : plan.firings().entrySet()) {
            int id = ((PhysicalRecipe) entry.getKey().source()).id;
            result.merge(id, BigInteger.valueOf(entry.getValue()), BigInteger::add);
        }
        return result;
    }

    // Independent enabled-firing replay using only the plan's actually consumed stock.
    // Greedy failure is inconclusive: another schedule can avoid the observed contention.
    static String replay(InputGraph input, Map<Integer, BigInteger> firings,
                         Map<Integer, Long> usedStock, int target, long effectiveAmount) {
        var pool = new HashMap<Integer, BigInteger>();
        usedStock.forEach((key, amount) -> pool.put(key, BigInteger.valueOf(amount)));
        var pending = new TreeMap<>(firings);
        long visits = 0;
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (var it = pending.entrySet().iterator(); it.hasNext();) {
                if (++visits > 10_000_000L) return "replay_budget";
                var entry = it.next(); var original = input.originals.get(entry.getKey());
                BigInteger enabled = entry.getValue();
                for (var ingredient : original.inputs) enabled = enabled.min(
                        pool.getOrDefault(ingredient.key(), BigInteger.ZERO).divide(ingredient.exactAmount()));
                if (enabled.signum() <= 0) continue;
                for (var ingredient : original.inputs)
                    pool.merge(ingredient.key(), ingredient.exactAmount().multiply(enabled).negate(), BigInteger::add);
                for (var output : original.outputs)
                    pool.merge(output.key(), output.exactAmount().multiply(enabled), BigInteger::add);
                if (enabled.equals(entry.getValue())) it.remove(); else entry.setValue(entry.getValue().subtract(enabled));
                progress = true;
            }
            if (!progress) return "stuck_inconclusive";
        }
        return pool.getOrDefault(target, BigInteger.ZERO).compareTo(BigInteger.valueOf(effectiveAmount)) >= 0
                ? "pass" : "wrong_final_balance";
    }

    static double millis(long begin) { return (System.nanoTime() - begin) / 1_000_000.0; }
}
