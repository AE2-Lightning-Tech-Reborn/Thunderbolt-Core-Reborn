package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A material certificate for exporting ordinary byproduct plans without an execution schedule.
 * A successful recursive replay proves one order only; AE2 receives firing counts, not that order.
 *
 * <p>Self returns become net consumption plus a retained seed. Other feedback outputs are excluded
 * from the certificate's supply (they still physically exist). The remaining production net is a
 * DAG. Its final material balance, including the retained seeds, proves that every enabled firing
 * order can finish: a minimal remaining producer always has its net inputs and its seed available.
 * Work depends on graph size and integer bit length, never the requested firing count.
 */
final class UnorderedByproductSafety {
    private UnorderedByproductSafety() {}

    static <K> CraftPlan<K> protect(CraftGraph<K> graph, CraftPlan<K> plan, K target, long amount) {
        if (!plan.supported() || !plan.usedReusableStock().isEmpty() || plan.firings().isEmpty()) return plan;
        boolean hasByproducts = false;
        for (var entry : plan.firings().entrySet()) {
            if (entry.getValue() <= 0) continue;
            hasByproducts |= !entry.getKey().byproducts().isEmpty();
            for (var input : entry.getKey().inputs()) {
                // Tools, containers and private reusable pools have separate runtime contracts.
                if (input.returned() || input.remainder() != null || input.reusableStockSource() != null)
                    return plan;
            }
        }
        if (!hasByproducts) return plan;
        var nodes = new LinkedHashSet<Object>();
        var edges = new HashMap<Object, LinkedHashSet<Object>>();
        var patterns = new ArrayList<MaterialPattern<K>>();
        var reserve = new HashMap<K, BigInteger>();
        for (var entry : plan.firings().entrySet()) {
            PlanningCancellation.check();
            if (entry.getValue() <= 0) continue;
            var pattern = entry.getKey();
            var inputs = new HashMap<K, BigInteger>();
            var outputs = new HashMap<K, BigInteger>();
            for (var input : pattern.inputs()) add(inputs, input.key(), BigInteger.valueOf(input.amount()));
            for (var output : pattern.byproducts()) add(outputs, output.key(), BigInteger.valueOf(output.amount()));
            for (var input : inputs.entrySet()) {
                BigInteger returned = input.getValue().min(outputs.getOrDefault(input.getKey(), BigInteger.ZERO));
                if (returned.signum() > 0) {
                    reserve.merge(input.getKey(), returned, BigInteger::max);
                    input.setValue(input.getValue().subtract(returned));
                    // Positive self gain cannot fund the reserve that must exist before that gain.
                    // Ignore any excess in this certificate; physical surplus can only help.
                    outputs.put(input.getKey(), BigInteger.ZERO);
                }
            }
            var node = new PatternNode(patterns.size());
            nodes.add(node);
            // A fully returned input still has to exist before this producer can fire. Keep
            // its enabling arc in the dependency graph even though its net consumption is zero.
            // Otherwise a later, seed-dependent producer could incorrectly fund that same seed.
            for (var input : pattern.inputs())
                edge(nodes, edges, new ItemNode<>(input.key()), node);
            // Keep primary and side output arcs separate when deciding which feedback credits to cut.
            edge(nodes, edges, node, new ItemNode<>(pattern.output()));
            for (var output : outputs.entrySet()) if (output.getValue().signum() > 0)
                edge(nodes, edges, node, new ItemNode<>(output.getKey()));
            patterns.add(new MaterialPattern<>(pattern, entry.getValue(), node, inputs, outputs));
        }
        var adjacency = new HashMap<Object, List<Object>>();
        for (Object node : nodes) adjacency.put(node, List.copyOf(edges.getOrDefault(node, new LinkedHashSet<>())));
        var components = CraftPlannerV2.stronglyConnectedComponents(List.copyOf(nodes), adjacency);
        var balance = new HashMap<K, BigInteger>();
        add(balance, target, BigInteger.valueOf(amount));
        reserve.forEach((key, value) -> add(balance, key, value));
        for (var material : patterns) {
            PlanningCancellation.check();
            BigInteger times = BigInteger.valueOf(material.times());
            material.inputs().forEach((key, value) -> add(balance, key, value.multiply(times)));
            var pattern = material.pattern();
            if (!components.get(material.node()).equals(components.get(new ItemNode<>(pattern.output()))))
                add(balance, pattern.output(), BigInteger.valueOf(pattern.outputAmount()).multiply(times).negate());
            for (var output : material.outputs().entrySet()) {
                if (output.getValue().signum() > 0
                        && !components.get(material.node()).equals(components.get(new ItemNode<>(output.getKey()))))
                    add(balance, output.getKey(), output.getValue().multiply(times).negate());
            }
        }
        var additional = new HashMap<K, Long>();
        for (var entry : balance.entrySet()) {
            BigInteger initial = BigInteger.valueOf(plan.usedStock().getOrDefault(entry.getKey(), 0L))
                    .add(BigInteger.valueOf(plan.missing().getOrDefault(entry.getKey(), 0L)));
            BigInteger deficit = entry.getValue().subtract(initial);
            if (deficit.signum() > 0)
                additional.put(entry.getKey(), deficit.min(BigInteger.valueOf(Sat.SAT)).longValueExact());
        }
        if (additional.isEmpty() || allOrdersFinishSmall(plan, target, amount)) return plan;
        Map<K, Long> persistentReserve = conflictFreeFeedbackReserve(plan);
        if (persistentReserve != null) {
            additional.clear();
            additional.putAll(persistentReserve);
            if (additional.isEmpty()) return plan;
        }
        var used = new HashMap<>(plan.usedStock());
        var missing = new HashMap<>(plan.missing());
        var gross = new HashMap<>(plan.grossDemand());
        additional.forEach((key, extra) -> {
            long available = Math.max(0L, graph.stock(key) - used.getOrDefault(key, 0L));
            long draw = Math.min(available, extra);
            if (draw > 0) used.merge(key, draw, Sat::add);
            if (draw < extra) missing.merge(key, extra - draw, Sat::add);
            gross.merge(key, extra, Sat::add);
        });
        return new CraftPlan<>(plan.supported(), plan.feasible() && missing.isEmpty(), plan.firings(),
                Map.copyOf(used), plan.usedReusableStock(), Map.copyOf(missing), Map.copyOf(gross),
                plan.itemsProcessed(), plan.budgetExhausted());
    }

    /** With one internal consumer per cyclic place, enabled internal firings cannot disable each
     * other. Reserve every external sink before checking the existing compressed prefix witness. */
    private static <K> Map<K, Long> conflictFreeFeedbackReserve(CraftPlan<K> plan) {
        var additional = new HashMap<K, Long>();
        var active = plan.firings().entrySet().stream().filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey).toList();
        if (active.size() > 64) return null; // Optional sharper certificate; retain the linear DAG bound.
        for (Set<K> states : ConservativeFeedbackAnalysis.cyclicComponents(active)) {
            PlanningCancellation.check();
            var internal = new ArrayList<CraftPattern<K>>();
            var consumers = new HashMap<K, CraftPattern<K>>();
            for (var pattern : active) {
                boolean consumes = pattern.inputs().stream().anyMatch(i -> states.contains(i.key()));
                boolean produces = states.contains(pattern.output()) || pattern.byproducts().stream()
                        .anyMatch(o -> states.contains(o.key()));
                if (!consumes || !produces) continue;
                internal.add(pattern);
                for (var input : pattern.inputs()) if (states.contains(input.key())) {
                    var prior = consumers.putIfAbsent(input.key(), pattern);
                    if (prior != null && prior != pattern) return null;
                }
            }
            var demand = new HashMap<K, BigInteger>();
            var supply = new HashMap<K, BigInteger>();
            for (var pattern : active) {
                if (internal.contains(pattern)) continue;
                BigInteger times = BigInteger.valueOf(plan.firings().get(pattern));
                for (var input : pattern.inputs()) if (states.contains(input.key()))
                    add(demand, input.key(), BigInteger.valueOf(input.amount()).multiply(times));
                if (states.contains(pattern.output()))
                    add(supply, pattern.output(), BigInteger.valueOf(pattern.outputAmount()).multiply(times));
                for (var output : pattern.byproducts()) if (states.contains(output.key()))
                    add(supply, output.key(), BigInteger.valueOf(output.amount()).multiply(times));
            }
            // Final delivery occurs after firing completion, so it cannot steal the live seed.
            if (demand.isEmpty()) continue;
            Map<K, Long> best = null;
            BigInteger bestCost = null;
            for (var option : ConservativeFeedbackAnalysis.fixedCountSchedules(states, internal, plan.firings())) {
                var extra = new HashMap<K, Long>();
                BigInteger cost = BigInteger.ZERO;
                for (K key : states) {
                    BigInteger available = BigInteger.valueOf(plan.usedStock().getOrDefault(key, 0L))
                            .add(BigInteger.valueOf(plan.missing().getOrDefault(key, 0L)))
                            .add(supply.getOrDefault(key, BigInteger.ZERO));
                    BigInteger needed = option.required().getOrDefault(key, BigInteger.ZERO)
                            .add(demand.getOrDefault(key, BigInteger.ZERO));
                    BigInteger deficit = needed.subtract(available).max(BigInteger.ZERO);
                    if (deficit.signum() > 0) {
                        extra.put(key, deficit.min(BigInteger.valueOf(Sat.SAT)).longValueExact());
                        cost = cost.add(deficit);
                    }
                }
                if (best == null || cost.compareTo(bestCost) < 0) { best = extra; bestCost = cost; }
            }
            if (best == null) return null;
            additional.putAll(best);
        }
        return additional;
    }

    private static <K> void add(Map<K, BigInteger> map, K key, BigInteger amount) {
        if (amount.signum() != 0) map.merge(key, amount, BigInteger::add);
    }

    private static void edge(Set<Object> nodes, Map<Object, LinkedHashSet<Object>> edges,
                             Object from, Object to) {
        nodes.add(from); nodes.add(to);
        edges.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(to);
    }

    /** Optional exact proof for tiny plans; exceeding either fixed bound retains the DAG certificate. */
    static <K> boolean allOrdersFinishSmall(CraftPlan<K> plan, K target, long amount) {
        return allOrdersFinishSmall(plan, target, amount, false);
    }

    /** Retain the ordinary executor's stronger whole-pattern-batch contract during cycle recovery. */
    static <K> boolean allBatchOrdersFinishSmall(CraftPlan<K> plan, K target, long amount) {
        return allOrdersFinishSmall(plan, target, amount, true);
    }

    private static <K> boolean allOrdersFinishSmall(CraftPlan<K> plan, K target, long amount,
            boolean wholeBatches) {
        var patterns = plan.firings().entrySet().stream().filter(e -> e.getValue() > 0).toList();
        if (patterns.size() > 16) return false;
        long total = 0;
        for (var entry : patterns) {
            if (entry.getValue() > 64 - total) return false;
            total += entry.getValue();
        }
        var keys = new LinkedHashSet<K>();
        keys.add(target); keys.addAll(plan.usedStock().keySet()); keys.addAll(plan.missing().keySet());
        for (var entry : patterns) {
            keys.add(entry.getKey().output());
            entry.getKey().inputs().forEach(input -> keys.add(input.key()));
            entry.getKey().byproducts().forEach(output -> keys.add(output.key()));
        }
        var indices = new HashMap<K, Integer>();
        for (K key : keys) indices.put(key, indices.size());
        long[] inventory = new long[keys.size()];
        long[][] inputs = new long[patterns.size()][keys.size()], outputs = new long[patterns.size()][keys.size()];
        int[] remaining = new int[patterns.size()];
        try {
            for (K key : keys) inventory[indices.get(key)] = Math.addExact(
                    plan.usedStock().getOrDefault(key, 0L), plan.missing().getOrDefault(key, 0L));
            for (int r = 0; r < patterns.size(); r++) {
                var entry = patterns.get(r); var pattern = entry.getKey();
                if (!pattern.exactOutputAmount().equals(BigInteger.valueOf(pattern.outputAmount()))) return false;
                remaining[r] = entry.getValue().intValue();
                for (var input : pattern.inputs()) {
                    if (input.returned() || input.remainder() != null || input.reusableStockSource() != null
                            || !input.exactAmount().equals(BigInteger.valueOf(input.amount()))) return false;
                    int k = indices.get(input.key()); inputs[r][k] = Math.addExact(inputs[r][k], input.amount());
                }
                outputs[r][indices.get(pattern.output())] = pattern.outputAmount();
                for (var output : pattern.byproducts()) {
                    if (!output.exactAmount().equals(BigInteger.valueOf(output.amount()))) return false;
                    int k = indices.get(output.key()); outputs[r][k] = Math.addExact(outputs[r][k], output.amount());
                }
            }
            return everyOrder(remaining, inventory, inputs, outputs, indices.get(target), amount,
                    new HashSet<>(), new int[] {4096}, wholeBatches);
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    private static boolean everyOrder(int[] remaining, long[] inventory, long[][] inputs,
                                      long[][] outputs, int target, long amount,
                                      Set<List<Integer>> proved, int[] work, boolean wholeBatches) {
        PlanningCancellation.check();
        List<Integer> state = Arrays.stream(remaining).boxed().toList();
        if (proved.contains(state)) return true;
        if (--work[0] < 0) return false;
        boolean pending = false, enabled = false;
        for (int r = 0; r < remaining.length; r++) {
            if (remaining[r] == 0) continue;
            pending = true;
            int times = wholeBatches ? remaining[r] : 1;
            boolean ready = true;
            for (int k = 0; k < inventory.length; k++)
                if (inventory[k] < Math.multiplyExact(inputs[r][k], times)) { ready = false; break; }
            if (!ready) continue;
            enabled = true;
            long[] next = inventory.clone();
            for (int k = 0; k < next.length; k++) next[k] = Math.addExact(
                    Math.subtractExact(next[k], Math.multiplyExact(inputs[r][k], times)),
                    Math.multiplyExact(outputs[r][k], times));
            remaining[r] -= times;
            boolean safe = everyOrder(remaining, next, inputs, outputs, target, amount, proved, work, wholeBatches);
            remaining[r] += times;
            if (!safe) return false;
        }
        if (pending ? !enabled : inventory[target] < amount) return false;
        proved.add(state);
        return true;
    }

    private record ItemNode<K>(K key) {}
    private record PatternNode(int index) {}
    private record MaterialPattern<K>(CraftPattern<K> pattern, long times, PatternNode node,
                                      Map<K, BigInteger> inputs, Map<K, BigInteger> outputs) {}
}
