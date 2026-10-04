package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Bounded pair proposals that may grow upstream batches in a small ordinary material DAG. */
final class UpstreamBatchOptimizer {
    private static final int MAX_PATTERNS = 16;
    private static final int MAX_CANDIDATES = 64;
    private static final BigInteger MAX_AMOUNT = BigInteger.valueOf(Sat.SAT - 1);
    private static final int EXTEND_ROUTES = 0;
    private static final int REPLACE_SECOND_OUTPUT = 1;
    private static final int EXCHANGE_INCUMBENT_PAIR = 2;
    private static final int KEEP_NEW_BATCH = 3;

    private UpstreamBatchOptimizer() {}

    static <K> CraftPlan<K> tryImprove(CraftGraph<K> graph, K target, long amount, CraftPlan<K> incumbent,
            List<K> keyOrder, IntPredicate spendWork, BooleanSupplier reserveProbe) {
        var search = startSearch(graph, target, amount, incumbent, keyOrder, spendWork, reserveProbe);
        return search == null ? null : search.tryEstablished();
    }

    static <K> Search<K> startSearch(CraftGraph<K> graph, K target, long amount, CraftPlan<K> incumbent,
            List<K> keyOrder, IntPredicate spendWork, BooleanSupplier reserveProbe) {
        var budget = new Budget(spendWork);
        if (!budget.charge(1) || !incumbent.feasible() || amount <= 0 || amount >= Sat.SAT
                || !incumbent.usedReusableStock().isEmpty()) return null;
        long executions = 0;
        for (var entry : incumbent.firings().entrySet()) {
            if (!budget.charge(1 + entry.getKey().inputs().size()) || entry.getValue() <= 0
                    || !OrdinaryBatchOptimizer.ordinary(entry.getKey())) return null;
            executions = Sat.add(executions, entry.getValue());
        }
        if (executions <= 0 || executions >= Sat.SAT) return null;
        var model = Model.build(graph, target, executions, budget);
        if (model == null) return null;
        for (var pattern : incumbent.firings().keySet())
            if (!model.inputs.containsKey(pattern)) return null;
        var outputs = new LinkedHashSet<K>();
        for (K key : keyOrder) {
            if (!budget.charge(1)) return null;
            if (model.routes.containsKey(key)) outputs.add(key);
        }
        if (!budget.charge(model.order.size())) return null;
        int visits = 0;
        for (K key : model.order) {
            checkpoint(visits++);
            outputs.add(key);
        }
        return new Search<>(graph, target, amount, incumbent, executions, model, outputs, budget, reserveProbe);
    }

    static final class Search<K> {
        final CraftGraph<K> graph;
        final K target;
        final long amount;
        final CraftPlan<K> incumbent;
        final long executions;
        final Model<K> model;
        final LinkedHashSet<K> outputs;
        final Budget budget;
        final BooleanSupplier reserveProbe;
        final HashSet<List<Long>> tried = new HashSet<>();
        final PairCache<K> pairs = new PairCache<>();
        int candidates;
        int replayProbes;
        boolean probeExhausted;
        boolean establishedTried;
        boolean additionalTried;

        Search(CraftGraph<K> graph, K target, long amount, CraftPlan<K> incumbent, long executions,
                Model<K> model, LinkedHashSet<K> outputs, Budget budget, BooleanSupplier reserveProbe) {
            this.graph = graph; this.target = target; this.amount = amount; this.incumbent = incumbent;
            this.executions = executions; this.model = model; this.outputs = outputs; this.budget = budget;
            this.reserveProbe = reserveProbe;
        }

        boolean exhausted() {
            return budget.exhausted || probeExhausted || candidates >= MAX_CANDIDATES;
        }

        CraftPlan<K> tryEstablished() {
            PlanningCancellation.check();
            if (establishedTried || exhausted()) return null;
            establishedTried = true;
            for (int phase = EXTEND_ROUTES; phase <= REPLACE_SECOND_OUTPUT; phase++) {
                for (K output : outputs) {
                    if (!budget.charge(1)) return null;
                    var routes = model.routes.get(output);
                    if (routes.size() < 2) continue;
                    for (int a = 0; a < routes.size(); a++) for (int b = a + 1; b < routes.size(); b++) {
                        for (int variant = phase == EXTEND_ROUTES ? -1 : 0; variant < model.patterns.size(); variant++) {
                            var expansion = variant < 0 ? null : model.patterns.get(variant);
                            if (expansion != null && (expansion.output().equals(output)
                                    || model.routes.get(expansion.output()).size() < 2)) continue;
                            for (int exchange = 0; exchange < 3; exchange++) {
                                var result = attempt(output, routes.get(a), routes.get(b), expansion, phase, exchange);
                                if (result != null) return result;
                                if (exhausted()) return null;
                            }
                        }
                    }
                }
            }
            return null;
        }

        // Defer these proposals until the established ordinary/upstream neighborhoods stop.
        // Both stages retain this incumbent and share candidates, signatures, work and probes.
        CraftPlan<K> tryAdditional() {
            PlanningCancellation.check();
            if (!establishedTried || additionalTried || exhausted()) return null;
            additionalTried = true;
            for (K output : outputs) {
                var routes = model.routes.get(output);
                if (!budget.charge(1 + routes.size())) return null;
                var active = new ArrayList<CraftPattern<K>>(3);
                for (var route : routes) {
                    if (incumbent.firings().getOrDefault(route, 0L) > 0) active.add(route);
                    if (active.size() > 2) break;
                }
                if (active.size() != 2) continue;
                for (int exchange = 1; exchange <= 2; exchange++) {
                    var result = attempt(output, active.get(0), active.get(1), null, EXCHANGE_INCUMBENT_PAIR, exchange);
                    if (result != null) return result;
                    if (exhausted()) return null;
                }
            }
            for (K output : outputs) {
                if (!budget.charge(1)) return null;
                var routes = model.routes.get(output);
                if (routes.size() < 2) continue;
                for (int a = 0; a < routes.size(); a++) for (int b = a + 1; b < routes.size(); b++) {
                    for (var expansion : model.patterns) {
                        if (expansion.output().equals(output)
                                || incumbent.firings().getOrDefault(expansion, 0L) != 0) continue;
                        var alternatives = model.routes.get(expansion.output());
                        if (!budget.charge(1 + alternatives.size())) return null;
                        boolean hasOther = false;
                        for (var route : alternatives)
                            if (route != expansion && incumbent.firings().getOrDefault(route, 0L) > 0) hasOther = true;
                        if (!hasOther) continue;
                        for (int exchange = 0; exchange < 3; exchange++) {
                            var result = attempt(output, routes.get(a), routes.get(b), expansion, KEEP_NEW_BATCH, exchange);
                            if (result != null) return result;
                            if (exhausted()) return null;
                        }
                    }
                }
            }
            return null;
        }

        private CraftPlan<K> attempt(K output, CraftPattern<K> first, CraftPattern<K> second,
                CraftPattern<K> expansion, int mode, int exchange) {
            if (exhausted()) return null;
            candidates++;
            if (!budget.charge(1)) return null;
            var counts = propose(graph, target, amount, incumbent, model, output,
                    first, second, expansion, mode, exchange, executions, pairs, budget);
            if (budget.exhausted || counts == null || sameCounts(counts, incumbent.firings())) return null;
            if (!budget.charge(model.patterns.size())) return null;
            var signature = new ArrayList<Long>(model.patterns.size());
            for (var pattern : model.patterns) signature.add(counts.getOrDefault(pattern, 0L));
            if (!tried.add(signature)) return null;
            long work = counts.size();
            for (var pattern : counts.keySet()) work += 2L * pattern.inputs().size();
            if (!budget.charge((int) Math.max(1L, Math.min(Integer.MAX_VALUE, work)))) return null;
            if (!reserveProbe.getAsBoolean()) { probeExhausted = true; return null; }
            replayProbes++;
            var result = MaterialDagReplay.tryPlan(graph, counts, target, amount);
            return result != null && FeasibleConsumptionOptimizer.improves(incumbent, result) ? result : null;
        }
    }

    private static <K> IdentityHashMap<CraftPattern<K>, Long> propose(CraftGraph<K> graph, K target,
            long amount, CraftPlan<K> incumbent, Model<K> model, K mixedOutput, CraftPattern<K> first,
            CraftPattern<K> second, CraftPattern<K> expansion, int mode, int exchange,
            long executionLimit, PairCache<K> pairs, Budget budget) {
        var need = new HashMap<K, BigInteger>();
        need.put(target, BigInteger.valueOf(amount));
        var counts = new IdentityHashMap<CraftPattern<K>, Long>();
        long executions = 0;
        for (K key : model.order) {
            if (!budget.charge(1)) return null;
            var required = need.getOrDefault(key, BigInteger.ZERO)
                    .subtract(BigInteger.valueOf(graph.stock(key))).max(BigInteger.ZERO);
            if (required.signum() == 0) continue;
            if (required.compareTo(MAX_AMOUNT) > 0) return null;
            var routes = model.routes.get(key);
            if (routes.isEmpty()) return null;
            if (key.equals(mixedOutput)) {
                if (!budget.charge(1 + first.inputs().size() + second.inputs().size())) return null;
                var keys = new LinkedHashSet<K>();
                int visits = 0;
                for (K input : model.inputs.get(first).keySet()) {
                    checkpoint(visits++);
                    keys.add(input);
                }
                for (K input : model.inputs.get(second).keySet()) {
                    checkpoint(visits++);
                    keys.add(input);
                }
                long[] useA = new long[keys.size()], useB = new long[keys.size()], capacity = new long[keys.size()];
                int i = 0;
                for (K input : keys) {
                    checkpoint(i);
                    useA[i] = model.inputs.get(first).getOrDefault(input, BigInteger.ZERO).longValueExact();
                    useB[i] = model.inputs.get(second).getOrDefault(input, BigInteger.ZERO).longValueExact();
                    capacity[i++] = model.capacity.get(input).longValueExact();
                }
                PairBatchAllocation.Allocation allocation;
                if (mode == EXCHANGE_INCUMBENT_PAIR) {
                    long seedA = incumbent.firings().getOrDefault(first, 0L);
                    long seedB = incumbent.firings().getOrDefault(second, 0L);
                    if (seedA <= 0 || seedB <= 0 || seedA > executionLimit - executions - seedB
                            || first.exactOutputAmount().multiply(BigInteger.valueOf(seedA))
                                    .add(second.exactOutputAmount().multiply(BigInteger.valueOf(seedB)))
                                    .compareTo(required) < 0) return null;
                    allocation = new PairBatchAllocation.Allocation(seedA, seedB, BigInteger.ZERO);
                } else {
                    allocation = pairs.solve(first, second, required.longValueExact(), useA, useB,
                            capacity, executionLimit - executions, budget);
                }
                if (allocation == null || budget.exhausted) return null;
                if (exchange > 0) {
                    if (!budget.charge(1 + capacity.length)) return null;
                    allocation = exchangeOne(allocation, exchange, required, first.outputAmount(),
                            second.outputAmount(), useA, useB, capacity, executionLimit - executions);
                    if (allocation == null) return null;
                }
                if (allocation.first() > 0) counts.put(first, allocation.first());
                if (allocation.second() > 0) counts.put(second, allocation.second());
            } else {
                BigInteger supplied = BigInteger.ZERO;
                boolean replace = mode == REPLACE_SECOND_OUTPUT && expansion != null && expansion.output().equals(key);
                boolean protectOne = mode == KEEP_NEW_BATCH && expansion != null && expansion.output().equals(key);
                for (var route : routes) {
                    if (!budget.charge(1)) return null;
                    long count = replace ? 0L : incumbent.firings().getOrDefault(route, 0L);
                    if (protectOne && route == expansion) count = 1L;
                    if (count > 0) counts.put(route, count);
                    supplied = supplied.add(route.exactOutputAmount().multiply(BigInteger.valueOf(count)));
                }
                if (supplied.compareTo(required) < 0) {
                    var route = !protectOne && expansion != null && expansion.output().equals(key) ? expansion : null;
                    if (route == null) {
                        for (var option : routes) if (counts.containsKey(option) && (!protectOne || option != expansion)) {
                            route = option; break;
                        }
                        if (route == null) route = routes.get(0);
                    }
                    var extra = ceilDivide(required.subtract(supplied), route.exactOutputAmount());
                    var total = extra.add(BigInteger.valueOf(counts.getOrDefault(route, 0L)));
                    if (total.compareTo(BigInteger.valueOf(executionLimit - executions)) > 0) return null;
                    counts.put(route, total.longValueExact());
                    supplied = supplied.add(extra.multiply(route.exactOutputAmount()));
                }
                // Remove newly redundant old batches before propagating input demand.
                var surplus = supplied.subtract(required);
                for (var route : routes) {
                    if (protectOne && route == expansion) continue;
                    long count = counts.getOrDefault(route, 0L);
                    long removed = surplus.divide(route.exactOutputAmount()).min(BigInteger.valueOf(count)).longValueExact();
                    surplus = surplus.subtract(route.exactOutputAmount().multiply(BigInteger.valueOf(removed)));
                    if (count == removed) counts.remove(route);
                    else counts.put(route, count - removed);
                }
            }
            for (var route : routes) {
                long count = counts.getOrDefault(route, 0L);
                if (count <= 0) continue;
                if (!budget.charge(1 + route.inputs().size()) || count > executionLimit - executions) return null;
                executions += count;
                var times = BigInteger.valueOf(count);
                int visits = 0;
                for (var input : model.inputs.get(route).entrySet()) {
                    checkpoint(visits++);
                    need.merge(input.getKey(), input.getValue().multiply(times), BigInteger::add);
                }
            }
        }
        return counts;
    }

    /** Only complete base solves are reusable within one search's fixed model and capacities. */
    private static final class PairCache<K> {
        private final IdentityHashMap<CraftPattern<K>, IdentityHashMap<CraftPattern<K>,
                Map<PairDemand, PairBatchAllocation.Allocation>>> values = new IdentityHashMap<>();

        PairBatchAllocation.Allocation solve(CraftPattern<K> first, CraftPattern<K> second, long required,
                long[] useA, long[] useB, long[] capacity, long executionLimit, Budget budget) {
            PlanningCancellation.check();
            if (budget.exhausted) return null;
            var demand = new PairDemand(required, executionLimit);
            var firstValues = values.get(first);
            var secondValues = firstValues == null ? null : firstValues.get(second);
            // A complete null means the same bounded solver has already rejected this demand.
            if (secondValues != null && secondValues.containsKey(demand)) return secondValues.get(demand);
            var result = PairBatchAllocation.solve(required, first.outputAmount(), second.outputAmount(),
                    useA, useB, capacity, executionLimit, () -> budget.charge(1 + capacity.length));
            // A denied work callback may return a partial best; never reuse that interrupted solve.
            if (!budget.exhausted) values.computeIfAbsent(first, ignored -> new IdentityHashMap<>())
                    .computeIfAbsent(second, ignored -> new HashMap<>()).put(demand, result);
            return result;
        }
    }

    private record PairDemand(long required, long executionLimit) {}

    private static BigInteger ceilDivide(BigInteger value, BigInteger divisor) {
        return value.subtract(BigInteger.ONE).divide(divisor).add(BigInteger.ONE);
    }

    /** A locally minimal downstream count can cost more upstream; inspect two one-batch neighbors. */
    private static PairBatchAllocation.Allocation exchangeOne(PairBatchAllocation.Allocation allocation,
            int exchange, BigInteger required, long a, long b, long[] useA, long[] useB, long[] capacity,
            long executionLimit) {
        long x = allocation.first(), y = allocation.second();
        if (exchange == 1) { if (x == 0) return null; x--; }
        else { if (y == 0) return null; y--; }
        var missing = required.subtract(BigInteger.valueOf(a).multiply(BigInteger.valueOf(x)))
                .subtract(BigInteger.valueOf(b).multiply(BigInteger.valueOf(y)));
        if (missing.signum() > 0) {
            var extra = ceilDivide(missing, BigInteger.valueOf(exchange == 1 ? b : a));
            if (extra.compareTo(BigInteger.valueOf(executionLimit - x - y)) > 0) return null;
            if (exchange == 1) y += extra.longValueExact();
            else x += extra.longValueExact();
        }
        BigInteger consumed = BigInteger.ZERO;
        for (int i = 0; i < capacity.length; i++) {
            checkpoint(i);
            var used = BigInteger.valueOf(useA[i]).multiply(BigInteger.valueOf(x))
                    .add(BigInteger.valueOf(useB[i]).multiply(BigInteger.valueOf(y)));
            if (used.compareTo(BigInteger.valueOf(capacity[i])) > 0) return null;
            consumed = consumed.add(used);
        }
        return new PairBatchAllocation.Allocation(x, y, consumed);
    }

    /** Bulk work charging must not hide cancellation inside a single unusually wide recipe. */
    private static void checkpoint(int visits) {
        if ((visits & 255) == 0) PlanningCancellation.check();
    }

    private static <K> boolean sameCounts(Map<CraftPattern<K>, Long> first, Map<CraftPattern<K>, Long> second) {
        if (first.size() != second.size()) return false;
        for (var entry : first.entrySet())
            if (!entry.getValue().equals(second.get(entry.getKey()))) return false;
        return true;
    }

    private static final class Model<K> {
        final Map<K, List<CraftPattern<K>>> routes = new LinkedHashMap<>();
        final List<CraftPattern<K>> patterns = new ArrayList<>();
        final Map<CraftPattern<K>, Map<K, BigInteger>> inputs = new IdentityHashMap<>();
        final List<K> order = new ArrayList<>();
        final Map<K, BigInteger> capacity = new HashMap<>();

        static <K> Model<K> build(CraftGraph<K> graph, K target, long executionLimit, Budget budget) {
            var model = new Model<K>();
            var pending = new ArrayDeque<K>();
            var discovered = new LinkedHashSet<K>();
            var edges = new LinkedHashMap<K, LinkedHashSet<K>>();
            var degree = new HashMap<K, Integer>();
            pending.add(target);
            discovered.add(target);
            while (!pending.isEmpty()) {
                K key = pending.removeFirst();
                if (!budget.charge(1) || graph.stock(key) >= Sat.SAT) return null;
                degree.putIfAbsent(key, 0);
                var dependencies = new LinkedHashSet<K>();
                edges.put(key, dependencies);
                var routes = new ArrayList<CraftPattern<K>>();
                model.routes.put(key, routes);
                for (var pattern : graph.patternsFor(key)) {
                    if (!budget.charge(1) || !key.equals(pattern.output())) return null;
                    // A repeated registration is still the same executable recipe. Distinct
                    // patterns sharing one source retain their independent input/output choices.
                    if (model.inputs.containsKey(pattern)) continue;
                    if (model.patterns.size() >= MAX_PATTERNS || !budget.charge(1 + pattern.inputs().size())
                            || !OrdinaryBatchOptimizer.ordinary(pattern)) return null;
                    routes.add(pattern);
                    model.patterns.add(pattern);
                    var consumed = new LinkedHashMap<K, BigInteger>();
                    int visits = 0;
                    for (var input : pattern.inputs()) {
                        checkpoint(visits++);
                        var amount = consumed.merge(input.key(), input.exactAmount(), BigInteger::add);
                        if (amount.compareTo(MAX_AMOUNT) > 0) return null;
                        degree.putIfAbsent(input.key(), 0);
                        if (dependencies.add(input.key())) degree.merge(input.key(), 1, Integer::sum);
                        if (discovered.add(input.key())) pending.addLast(input.key());
                    }
                    model.inputs.put(pattern, consumed);
                }
            }
            for (K key : discovered) {
                if (!budget.charge(1)) return null;
                if (degree.get(key) == 0) pending.addLast(key);
            }
            while (!pending.isEmpty()) {
                K key = pending.removeFirst();
                if (!budget.charge(1 + edges.get(key).size())) return null;
                model.order.add(key);
                int visits = 0;
                for (K input : edges.get(key)) {
                    checkpoint(visits++);
                    if (degree.merge(input, -1, Integer::sum) == 0) pending.addLast(input);
                }
            }
            if (model.order.size() != discovered.size()) return null;
            // Relax shared resource competition to bound each input independently. These are only
            // proposal capacities: the final replay checks the actual combined inventory draw.
            for (int i = model.order.size() - 1; i >= 0; i--) {
                if (!budget.charge(1)) return null;
                K key = model.order.get(i);
                var available = BigInteger.valueOf(graph.stock(key));
                for (var pattern : model.routes.get(key)) {
                    if (!budget.charge(1 + pattern.inputs().size())) return null;
                    var count = BigInteger.valueOf(executionLimit);
                    int visits = 0;
                    for (var input : model.inputs.get(pattern).entrySet()) {
                        checkpoint(visits++);
                        count = count.min(model.capacity.get(input.getKey()).divide(input.getValue()));
                    }
                    available = available.add(count.multiply(pattern.exactOutputAmount())).min(MAX_AMOUNT);
                }
                model.capacity.put(key, available);
            }
            return model;
        }
    }

    private static final class Budget {
        private final IntPredicate spendWork;
        boolean exhausted;

        Budget(IntPredicate spendWork) { this.spendWork = spendWork; }

        boolean charge(int work) {
            PlanningCancellation.check();
            if (exhausted || !spendWork.test(work)) { exhausted = true; return false; }
            return true;
        }
    }
}
