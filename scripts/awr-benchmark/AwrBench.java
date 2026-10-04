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
    record InputGraph(int nReal, int nItems, List<CraftPattern<Integer>> recipes) {}
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
        Files.createDirectories(output.getParent());
        try (var out = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
            out.println(JSON.toJson(config));
            for (int pass = -warmup; pass < repeats; pass++) {
                int done = 0;
                for (var group : graphs.entrySet()) for (Query query : queries) {
                    Map<String, Object> row = run(group.getValue(), stocks.get(group.getKey()), query, policy, limitNanos);
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
            int real = in.readInt(), items = in.readInt(), count = in.readInt();
            if (real <= 0 || items < real || count < 0) throw new IOException("invalid graph counts");
            var recipes = new ArrayList<CraftPattern<Integer>>(count);
            for (int r = 0; r < count; r++) {
                int output = in.readInt(); long amount = in.readLong(); int n = in.readInt();
                if (output < 0 || output >= items || amount <= 0 || n < 0 || n > items)
                    throw new IOException("invalid recipe " + r);
                var inputs = new ArrayList<CraftInput<Integer>>(n);
                var seen = new HashSet<Integer>();
                for (int j = 0; j < n; j++) {
                    int item = in.readInt(); long quantity = in.readLong();
                    if (item < 0 || item >= items || quantity <= 0 || !seen.add(item))
                        throw new IOException("invalid input in recipe " + r);
                    inputs.add(CraftInput.of(item, quantity));
                }
                if (output >= real) {
                    if (amount != 1 || n != 1 || inputs.getFirst().amount() != 1
                            || inputs.getFirst().key() >= real)
                        throw new IOException("recipe " + r + " is not a pure member-to-tag edge");
                    recipes.add(CraftPattern.tagConversion(inputs.getFirst().key(), output, r));
                } else recipes.add(new CraftPattern<>(output, amount, inputs, r));
            }
            if (in.read() != -1) throw new IOException("trailing normalized bytes");
            return new InputGraph(real, items, List.copyOf(recipes));
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

    static Map<String, Object> run(CraftGraph<Integer> graph, Map<Integer, Long> stock,
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
                    e.getValue() > 0 && graph.patternsFor(e.getKey().output()).stream().anyMatch(p -> p == e.getKey()));
            row.put("stock_within_inventory", inventoryValid);
            row.put("firings_valid", firingsValid);
            BigInteger tagExec = BigInteger.ZERO;
            var net = new HashMap<Integer, BigInteger>();
            var firings = new ArrayList<long[]>();
            for (var entry : plan.firings().entrySet()) {
                var pattern = entry.getKey(); BigInteger times = BigInteger.valueOf(entry.getValue());
                if (pattern.executionCost() == 0) tagExec = tagExec.add(times);
                net.merge(pattern.output(), pattern.exactOutputAmount().multiply(times), BigInteger::add);
                for (var ingredient : pattern.inputs())
                    net.merge(ingredient.key(), ingredient.exactAmount().multiply(times).negate(), BigInteger::add);
                firings.add(new long[] { ((Number) pattern.source()).longValue(), entry.getValue() });
            }
            firings.sort(Comparator.comparingLong(value -> value[0]));
            row.put("cost", plan.executionCount()); row.put("tag_exec", tagExec); row.put("firings", firings);
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
            String replay = !inventoryValid || !firingsValid ? "invalid_plan"
                    : plan.feasible() ? replay(plan, query.target, effectiveAmount) : "not_feasible";
            row.put("replay", replay); row.put("replay_ms", millis(validationStart));
            if (!inventoryValid || !firingsValid
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

    // Independent enabled-firing replay using only the plan's actually consumed stock.
    // Greedy failure is inconclusive: another schedule can avoid the observed contention.
    static String replay(CraftPlan<Integer> plan, int target, long effectiveAmount) {
        var pool = new HashMap<Integer, BigInteger>();
        plan.usedStock().forEach((key, amount) -> pool.put(key, BigInteger.valueOf(amount)));
        var entries = new ArrayList<>(plan.firings().entrySet());
        entries.sort(Comparator.comparingInt(entry -> ((Number) entry.getKey().source()).intValue()));
        var pending = new LinkedHashMap<CraftPattern<Integer>, Long>();
        entries.forEach(entry -> pending.put(entry.getKey(), entry.getValue()));
        long visits = 0;
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (var it = pending.entrySet().iterator(); it.hasNext();) {
                if (++visits > 10_000_000L) return "replay_budget";
                var entry = it.next(); var pattern = entry.getKey(); long enabled = entry.getValue();
                for (var ingredient : pattern.inputs()) enabled = Math.min(enabled,
                        pool.getOrDefault(ingredient.key(), BigInteger.ZERO).divide(ingredient.exactAmount())
                                .min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact());
                if (enabled <= 0) continue;
                BigInteger times = BigInteger.valueOf(enabled);
                for (var ingredient : pattern.inputs())
                    pool.merge(ingredient.key(), ingredient.exactAmount().multiply(times).negate(), BigInteger::add);
                pool.merge(pattern.output(), pattern.exactOutputAmount().multiply(times), BigInteger::add);
                if (enabled == entry.getValue()) it.remove(); else entry.setValue(entry.getValue() - enabled);
                progress = true;
            }
            if (!progress) return "stuck_inconclusive";
        }
        return pool.getOrDefault(target, BigInteger.ZERO).compareTo(BigInteger.valueOf(effectiveAmount)) >= 0
                ? "pass" : "wrong_final_balance";
    }

    static double millis(long begin) { return (System.nanoTime() - begin) / 1_000_000.0; }
}
