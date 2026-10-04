package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Mix producers, then trim no-longer-needed upstream batches in an ordinary material DAG. */
final class OrdinaryBatchOptimizer {
    private static final int MAX_PAIRS = 256;
    private static final int MAX_TRIPLES = 16;
    private static final int MAX_ROUTES = 16;

    private OrdinaryBatchOptimizer() {}

    static <K> CraftPlan<K> tryImprove(CraftGraph<K> graph, K target, long amount, CraftPlan<K> incumbent, List<K> keyOrder,
            IntPredicate spendWork, BooleanSupplier reserveProbe) {
        var search = startSearch(graph, target, amount, incumbent, spendWork, reserveProbe);
        var candidate = search.tryWhole(keyOrder);
        return candidate != null ? candidate : search.tryAdditional();
    }

    static <K> BatchSearch<K> startSearch(CraftGraph<K> graph, K target, long amount, CraftPlan<K> incumbent,
            IntPredicate spendWork, BooleanSupplier reserveProbe) {
        return new BatchSearch<>(graph, target, amount, incumbent, spendWork, reserveProbe);
    }

    /** Whole-output replacements retain priority; smaller withdrawals share their remaining budget. */
    static final class BatchSearch<K> {
        private final CraftGraph<K> graph;
        private final K target;
        private final long amount;
        private final CraftPlan<K> incumbent;
        private final IntPredicate spendWork;
        private final BooleanSupplier reserveProbe;
        private final Map<K, List<CraftPattern<K>>> byOutput = new HashMap<>();
        private final Map<K, BigInteger> demand = new HashMap<>();
        private final Map<K, BigInteger> produced = new HashMap<>();
        private final Map<CraftPattern<K>, Map<K, Long>> inputs = new IdentityHashMap<>();
        private final List<BatchRoutes<K>> routes = new ArrayList<>();
        private int pairs, triples;
        private boolean stopped;

        BatchSearch(CraftGraph<K> graph, K target, long amount, CraftPlan<K> incumbent,
                IntPredicate spendWork, BooleanSupplier reserveProbe) {
            this.graph = graph;
            this.target = target;
            this.amount = amount;
            this.incumbent = incumbent;
            this.spendWork = spendWork;
            this.reserveProbe = reserveProbe;
        }

        private boolean spend(long work) {
            if (stopped) return false;
            if (!spendWork.test((int) Math.min(Integer.MAX_VALUE, Math.max(1L, work)))) {
                stopped = true;
                return false;
            }
            return true;
        }

        /** The established strict-firing/full-inventory and equal-firing/capped-inventory phases. */
        CraftPlan<K> tryWhole(List<K> keyOrder) {
            demand.put(target, BigInteger.valueOf(amount));
            int inputIndex = 0;
            for (var entry : incumbent.firings().entrySet()) {
                PlanningCancellation.check();
                var pattern = entry.getKey();
                if (!spend(1L + pattern.inputs().size()) || !ordinary(pattern)) return null;
                byOutput.computeIfAbsent(pattern.output(), ignored -> new ArrayList<>()).add(pattern);
                var count = BigInteger.valueOf(entry.getValue());
                produced.merge(pattern.output(), pattern.exactOutputAmount().multiply(count), BigInteger::add);
                for (var input : pattern.inputs()) {
                    if ((++inputIndex & 255) == 0) PlanningCancellation.check();
                    demand.merge(input.key(), input.exactAmount().multiply(count), BigInteger::add);
                }
            }
            // Graph order is stable; plan firing maps use pattern identity hashes.
            var outputs = new ArrayList<K>();
            for (K key : keyOrder) if (byOutput.containsKey(key)) outputs.add(key);
            outputs.sort((a, b) -> Long.compare(executions(byOutput.get(b), incumbent), executions(byOutput.get(a), incumbent)));
            for (K output : outputs) {
                PlanningCancellation.check();
                var old = byOutput.get(output);
                var needed = demand.getOrDefault(output, BigInteger.ZERO)
                        .subtract(BigInteger.valueOf(incumbent.usedStock().getOrDefault(output, 0L)));
                long executionLimit = executions(old, incumbent);
                if (needed.signum() <= 0 || needed.compareTo(BigInteger.valueOf(Sat.SAT)) >= 0
                        || executionLimit <= 0 || executionLimit >= Sat.SAT) continue;
                var alternatives = new ArrayList<CraftPattern<K>>();
                var seen = new IdentityHashMap<CraftPattern<K>, Boolean>();
                long largestOutput = 0;
                boolean upstreamAlternative = false;
                for (var pattern : graph.patternsFor(output)) {
                    if (!spend(1L + pattern.inputs().size())) return null;
                    if (seen.put(pattern, Boolean.TRUE) != null) continue;
                    var amounts = ordinaryInputs(pattern, output);
                    if (amounts != null) {
                        alternatives.add(pattern);
                        inputs.put(pattern, amounts);
                        largestOutput = Math.max(largestOutput, pattern.outputAmount());
                        for (K key : amounts.keySet()) {
                            if ((++inputIndex & 255) == 0) PlanningCancellation.check();
                            upstreamAlternative |= byOutput.containsKey(key);
                        }
                    }
                    if (alternatives.size() == MAX_ROUTES) break;
                }
                if (alternatives.size() < 2) continue;
                var route = new BatchRoutes<>(output, old, alternatives, largestOutput, upstreamAlternative);
                routes.add(route);
                var candidate = replace(route, old, true, false);
                if (candidate != null || stopped) return candidate;
            }
            return null;
        }

        /** Continue only after the existing whole-output and upstream searches both failed. */
        CraftPlan<K> tryAdditional() {
            if (stopped) return null;
            // Preserve both established whole-output phases before trying equal local counts
            // with additional intermediate stock. An early inventory exchange can otherwise
            // change the stock caps of the existing search before it finds its better route.
            for (var route : routes) {
                var candidate = replace(route, route.old(), true, true);
                if (candidate != null || stopped) return candidate;
            }
            // Keeping one producer lets an existing two/three-route plan acquire a fourth route.
            // Only run after every whole-output attempt, with the very same pair/triple/probe caps.
            for (var route : routes) {
                if (route.old().size() < 2) continue;
                var withdrawn = new ArrayList<>(route.old());
                var preference = graph.patternsFor(route.output());
                withdrawn.sort((a, b) -> {
                    int byCount = Long.compare(incumbent.firings().get(b), incumbent.firings().get(a));
                    return byCount != 0 ? byCount : Integer.compare(preference.indexOf(a), preference.indexOf(b));
                });
                for (var pattern : withdrawn) {
                    PlanningCancellation.check();
                    if (!spend(1)) return null;
                    var candidate = replace(route, List.of(pattern), false, false);
                    if (candidate != null || stopped) return candidate;
                }
            }
            return null;
        }

        private CraftPlan<K> replace(BatchRoutes<K> route, List<CraftPattern<K>> removed, boolean wholeOutput,
                                    boolean equalStockOnly) {
            var keptOutput = produced.getOrDefault(route.output(), BigInteger.ZERO);
            long executionLimit = 0, inputWork = 0;
            for (var pattern : removed) {
                long count = incumbent.firings().get(pattern);
                keptOutput = keptOutput.subtract(pattern.exactOutputAmount().multiply(BigInteger.valueOf(count)));
                executionLimit = Sat.add(executionLimit, count);
                inputWork += 1L + pattern.inputs().size();
            }
            var needed = demand.getOrDefault(route.output(), BigInteger.ZERO)
                    .subtract(BigInteger.valueOf(incumbent.usedStock().getOrDefault(route.output(), 0L)))
                    .subtract(keptOutput);
            if (needed.signum() <= 0 || needed.compareTo(BigInteger.valueOf(Sat.SAT)) >= 0
                    || executionLimit <= 0 || executionLimit >= Sat.SAT) return null;
            if (!spend(inputWork)) return null;
            var restored = new HashMap<K, BigInteger>();
            int inputIndex = 0;
            boolean removedUsesUpstream = false;
            for (var pattern : removed) for (var input : pattern.inputs()) {
                if ((++inputIndex & 255) == 0) PlanningCancellation.check();
                restored.merge(input.key(), input.exactAmount().multiply(BigInteger.valueOf(incumbent.firings().get(pattern))), BigInteger::add);
                removedUsesUpstream |= byOutput.containsKey(input.key());
            }
            var oldConsumption = restored.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
            var alternatives = route.alternatives();
            long requested = needed.longValueExact();
            if (equalStockOnly && !removedUsesUpstream && !route.upstreamAlternative()) return null;
            // Fewer firings may use any ordinary inventory. Keep the original per-item caps
            // for the equal-execution pass so extra stock cannot hide a resource-only saving.
            for (int phase = 0; phase < (equalStockOnly ? 1 : 2); phase++) {
                boolean availableStock = phase == 0;
                long phaseLimit = availableStock && !equalStockOnly ? executionLimit - 1 : executionLimit;
                if (phaseLimit <= 0) continue;
                // No mixture under this firing cap can reach the demand. Preserve the shared
                // pair/triple allowance for phases that can actually propose an improvement.
                if (BigInteger.valueOf(route.largestOutput()).multiply(BigInteger.valueOf(phaseLimit)).compareTo(needed) < 0)
                    continue;
                for (int a = 0; a < alternatives.size(); a++) for (int b = a + 1; b < alternatives.size(); b++) {
                    if (pairs++ >= MAX_PAIRS) { stopped = true; return null; }
                    if (!spend(1)) return null;
                    var first = alternatives.get(a);
                    var second = alternatives.get(b);
                    var matrix = matrix(first, second, null, availableStock, restored);
                    if (stopped) return null;
                    if (matrix == null) continue;
                    if (equalStockOnly && !removedUsesUpstream && matrix.terminal()) continue;
                    var allocation = PairBatchAllocation.solve(requested, first.outputAmount(), second.outputAmount(),
                            matrix.first(), matrix.second(), matrix.capacity(), phaseLimit, () -> spend(1L + matrix.capacity().length));
                    if (stopped) return null;
                    if (allocation == null) continue;
                    if (wholeOutput && !equalStockOnly && matrix.terminal() && allocation.executions() == executionLimit
                            && allocation.consumed().compareTo(oldConsumption) >= 0) continue;
                    var counts = new IdentityHashMap<>(incumbent.firings());
                    removed.forEach(counts::remove);
                    if (allocation.first() > 0) counts.merge(first, allocation.first(), Long::sum);
                    if (allocation.second() > 0) counts.merge(second, allocation.second(), Long::sum);
                    var candidate = certifyCounts(counts, availableStock);
                    if (candidate != null || stopped) return candidate;
                }
                // All three counts must be positive: fewer than three firings cannot propose one.
                if (phaseLimit < 3 || equalStockOnly && !removedUsesUpstream) continue;
                for (int a = 0; a < alternatives.size() && triples < MAX_TRIPLES; a++)
                    for (int b = a + 1; b < alternatives.size() && triples < MAX_TRIPLES; b++)
                        for (int c = b + 1; c < alternatives.size() && triples < MAX_TRIPLES; c++) {
                            if (!spend(1)) return null;
                            var first = alternatives.get(a);
                            var second = alternatives.get(b);
                            var third = alternatives.get(c);
                            // One mandatory firing per route leaves at most limit-3 maximum batches.
                            long maximum = Math.max(first.outputAmount(), Math.max(second.outputAmount(), third.outputAmount()));
                            long possible = Sat.add(Sat.add(first.outputAmount(), second.outputAmount()), third.outputAmount());
                            possible = Sat.add(possible, Sat.mul(phaseLimit - 3, maximum));
                            if (possible < requested) continue;
                            var matrix = matrix(first, second, third, availableStock, restored);
                            if (stopped) return null;
                            if (matrix == null || !positiveTripleFits(matrix)) continue;
                            // Only genuine three-route candidates spend this shared allowance.
                            triples++;
                            var allocation = TripleBatchAllocation.solve(requested, first.outputAmount(),
                                    second.outputAmount(), third.outputAmount(), matrix.first(), matrix.second(), matrix.third(),
                                    matrix.capacity(), phaseLimit, () -> spend(1L + matrix.capacity().length));
                            if (stopped) return null;
                            if (allocation == null || allocation.first() == 0 || allocation.second() == 0
                                    || allocation.third() == 0 || wholeOutput && !equalStockOnly && allocation.executions() == executionLimit
                                    && allocation.consumed().compareTo(oldConsumption) >= 0) continue;
                            var counts = new IdentityHashMap<>(incumbent.firings());
                            removed.forEach(counts::remove);
                            counts.merge(first, allocation.first(), Long::sum);
                            counts.merge(second, allocation.second(), Long::sum);
                            counts.merge(third, allocation.third(), Long::sum);
                            var candidate = certifyCounts(counts, availableStock);
                            if (candidate != null || stopped) return candidate;
                        }
            }
            return null;
        }

        private CraftPlan<K> certifyCounts(Map<CraftPattern<K>, Long> counts, boolean availableStock) {
            if (sameFirings(counts, incumbent.firings())) return null;
            var stockLimits = availableStock ? inventoryFor(graph, counts, target) : incumbent.usedStock();
            return certify(graph, counts, incumbent, target, amount, stockLimits, this::spend, () -> {
                if (!reserveProbe.getAsBoolean()) {
                    stopped = true;
                    return false;
                }
                return true;
            });
        }

        private ResourceMatrix matrix(CraftPattern<K> first, CraftPattern<K> second, CraftPattern<K> third,
                boolean availableStock, Map<K, BigInteger> restored) {
            var firstInputs = inputs.get(first);
            var secondInputs = inputs.get(second);
            var thirdInputs = third == null ? Map.<K, Long>of() : inputs.get(third);
            // Prepay linear resource-table construction; inputs were aggregated once per pattern.
            if (!spend(1L + firstInputs.size() + secondInputs.size() + thirdInputs.size())) return null;
            var keys = new LinkedHashSet<K>();
            int inputIndex = 0;
            for (var amounts : List.of(firstInputs, secondInputs, thirdInputs)) for (K key : amounts.keySet()) {
                if ((++inputIndex & 255) == 0) PlanningCancellation.check();
                keys.add(key);
            }
            boolean terminal = true;
            for (K key : keys) {
                if ((++inputIndex & 255) == 0) PlanningCancellation.check();
                if (byOutput.containsKey(key)) {
                    if (third != null) return null;
                    terminal = false;
                }
            }
            long[] useA = new long[keys.size()], useB = new long[keys.size()];
            long[] useC = third == null ? null : new long[keys.size()], capacity = new long[keys.size()];
            int k = 0;
            for (K key : keys) {
                if ((k & 255) == 0) PlanningCancellation.check();
                var free = BigInteger.valueOf(availableStock ? graph.stock(key) : incumbent.usedStock().getOrDefault(key, 0L))
                        .add(produced.getOrDefault(key, BigInteger.ZERO))
                        .subtract(demand.getOrDefault(key, BigInteger.ZERO)).add(restored.getOrDefault(key, BigInteger.ZERO));
                if (free.signum() < 0 || free.compareTo(BigInteger.valueOf(Sat.SAT)) >= 0) return null;
                capacity[k] = free.longValueExact();
                useA[k] = firstInputs.getOrDefault(key, 0L);
                useB[k] = secondInputs.getOrDefault(key, 0L);
                if (useC != null) useC[k] = thirdInputs.getOrDefault(key, 0L);
                k++;
            }
            return new ResourceMatrix(useA, useB, useC, capacity, terminal);
        }
    }

    private record BatchRoutes<K>(K output, List<CraftPattern<K>> old, List<CraftPattern<K>> alternatives,
                                  long largestOutput, boolean upstreamAlternative) {}
    private record ResourceMatrix(long[] first, long[] second, long[] third, long[] capacity, boolean terminal) {}

    /** A real three-route mix consumes at least one complete batch of every route. */
    private static boolean positiveTripleFits(ResourceMatrix matrix) {
        for (int i = 0; i < matrix.capacity().length; i++) {
            if ((i & 255) == 0) PlanningCancellation.check();
            long remaining = matrix.capacity()[i];
            if (matrix.first()[i] > remaining) return false;
            remaining -= matrix.first()[i];
            if (matrix.second()[i] > remaining) return false;
            remaining -= matrix.second()[i];
            if (matrix.third()[i] > remaining) return false;
        }
        return true;
    }

    private static <K> Map<K, Long> ordinaryInputs(CraftPattern<K> pattern, K output) {
        if (!pattern.byproducts().isEmpty() || pattern.exactOutputAmount().compareTo(BigInteger.valueOf(Sat.SAT)) >= 0)
            return null;
        var amounts = new java.util.LinkedHashMap<K, Long>();
        int index = 0;
        for (var input : pattern.inputs()) {
            if ((index++ & 255) == 0) PlanningCancellation.check();
            if (!ordinaryInput(input) || input.key().equals(output)) return null;
            amounts.merge(input.key(), input.exactAmount().longValueExact(), Sat::add);
        }
        return amounts;
    }

    /**
     * Remove stock draws replaced by batch surplus only after route search has finished. Earlier
     * normalization would tighten the incumbent's stock caps and change the remaining proposals.
     */
    static <K> CraftPlan<K> normalizeStock(CraftGraph<K> graph, K target, long amount, CraftPlan<K> incumbent,
            IntPredicate spendWork, BooleanSupplier reserveProbe) {
        var demand = new HashMap<K, BigInteger>();
        var produced = new HashMap<K, BigInteger>();
        demand.put(target, BigInteger.valueOf(amount));
        int inputsProcessed = 0;
        for (var entry : incumbent.firings().entrySet()) {
            PlanningCancellation.check();
            var pattern = entry.getKey();
            if (!spendWork.test(1 + pattern.inputs().size())) return null;
            if (!ordinary(pattern)) return null;
            var count = BigInteger.valueOf(entry.getValue());
            produced.merge(pattern.output(), pattern.exactOutputAmount().multiply(count), BigInteger::add);
            for (var input : pattern.inputs()) {
                if ((++inputsProcessed & 255) == 0) PlanningCancellation.check();
                demand.merge(input.key(), input.exactAmount().multiply(count), BigInteger::add);
            }
        }
        for (var entry : incumbent.usedStock().entrySet()) {
            PlanningCancellation.check();
            if (!spendWork.test(1)) return null;
            var netDemand = demand.getOrDefault(entry.getKey(), BigInteger.ZERO)
                    .subtract(produced.getOrDefault(entry.getKey(), BigInteger.ZERO)).max(BigInteger.ZERO);
            if (BigInteger.valueOf(entry.getValue()).compareTo(netDemand) > 0)
                return certify(graph, incumbent.firings(), incumbent, target, amount, spendWork, reserveProbe);
        }
        return null;
    }

    /** Pattern keys retain identity, but reboxed firing counts must compare by value. */
    private static <K> boolean sameFirings(Map<CraftPattern<K>, Long> first, Map<CraftPattern<K>, Long> second) {
        if (first.size() != second.size()) return false;
        for (var entry : first.entrySet())
            if (!entry.getValue().equals(second.get(entry.getKey()))) return false;
        return true;
    }

    /** Only the proposed DAG's keys can be drawn; reusable stock remains inaccessible. */
    private static <K> Map<K, Long> inventoryFor(CraftGraph<K> graph,
            Map<CraftPattern<K>, Long> counts, K target) {
        var stock = new HashMap<K, Long>();
        stock.put(target, graph.stock(target));
        int inputIndex = 0;
        for (var pattern : counts.keySet()) {
            PlanningCancellation.check();
            stock.put(pattern.output(), graph.stock(pattern.output()));
            for (var input : pattern.inputs()) {
                if ((++inputIndex & 255) == 0) PlanningCancellation.check();
                stock.put(input.key(), graph.stock(input.key()));
            }
        }
        return stock;
    }

    static <K> CraftPlan<K> certify(CraftGraph<K> graph, Map<CraftPattern<K>, Long> proposed,
            CraftPlan<K> incumbent, K target, long amount, IntPredicate spendWork, BooleanSupplier reserveProbe) {
        return certify(graph, proposed, incumbent, target, amount, incumbent.usedStock(), spendWork, reserveProbe);
    }

    static <K> CraftPlan<K> certify(CraftGraph<K> graph, Map<CraftPattern<K>, Long> proposed,
            CraftPlan<K> incumbent, K target, long amount, Map<K, Long> stockLimits,
            IntPredicate spendWork, BooleanSupplier reserveProbe) {
        long work = proposed.size();
        for (var pattern : proposed.keySet()) work += 2L * pattern.inputs().size();
        if (!spendWork.test((int) Math.min(Integer.MAX_VALUE, work)) || !reserveProbe.getAsBoolean()) return null;
        var counts = trim(proposed, stockLimits, target, amount, graph);
        if (counts == null) return null;
        // Integer balance alone is insufficient. The full selected material DAG and primary-demand
        // support certify every enabled CPU firing order before acceptance.
        var candidate = MaterialDagReplay.tryPlan(graph.withStockLimits(stockLimits), counts, target, amount);
        return candidate != null && FeasibleConsumptionOptimizer.improves(incumbent, candidate) ? candidate : null;
    }

    /**
     * Replay demand from the target toward its inputs, using the proposed counts as caps. Every
     * upstream count can only decrease. This removes surplus batches that would otherwise fail
     * the primary-demand certificate after a downstream substitution saves an intermediate.
     */
    private static <K> IdentityHashMap<CraftPattern<K>, Long> trim(Map<CraftPattern<K>, Long> proposed,
            Map<K, Long> stock, K target, long amount, CraftGraph<K> graph) {
        var byOutput = new HashMap<K, List<CraftPattern<K>>>();
        var edges = new HashMap<K, LinkedHashSet<K>>();
        var degree = new HashMap<K, Integer>();
        int inputIndex = 0;
        for (var pattern : proposed.keySet())
            byOutput.computeIfAbsent(pattern.output(), ignored -> new ArrayList<>()).add(pattern);
        for (var entry : byOutput.entrySet()) {
            PlanningCancellation.check();
            K output = entry.getKey();
            var preference = graph.patternsFor(output);
            entry.getValue().sort((a, b) -> Integer.compare(preference.indexOf(a), preference.indexOf(b)));
            for (var pattern : entry.getValue()) {
                degree.putIfAbsent(output, 0);
                var inputs = edges.computeIfAbsent(output, ignored -> new LinkedHashSet<>());
                for (var input : pattern.inputs()) {
                    if ((++inputIndex & 255) == 0) PlanningCancellation.check();
                    degree.putIfAbsent(input.key(), 0);
                    if (inputs.add(input.key())) degree.merge(input.key(), 1, Integer::sum);
                }
            }
        }
        degree.putIfAbsent(target, 0);
        var ready = new ArrayDeque<K>();
        degree.forEach((key, count) -> { if (count == 0) ready.addLast(key); });
        var need = new HashMap<K, BigInteger>();
        need.put(target, BigInteger.valueOf(amount));
        var trimmed = new IdentityHashMap<CraftPattern<K>, Long>();
        int visited = 0;
        while (!ready.isEmpty()) {
            PlanningCancellation.check();
            K key = ready.removeFirst();
            visited++;
            var required = need.getOrDefault(key, BigInteger.ZERO)
                    .subtract(BigInteger.valueOf(stock.getOrDefault(key, 0L))).max(BigInteger.ZERO);
            var routes = byOutput.getOrDefault(key, List.of());
            BigInteger supplied = BigInteger.ZERO;
            for (var pattern : routes)
                supplied = supplied.add(pattern.exactOutputAmount().multiply(BigInteger.valueOf(proposed.get(pattern))));
            if (supplied.compareTo(required) < 0) return null;
            var surplus = supplied.subtract(required);
            for (var pattern : routes) {
                long count = proposed.get(pattern);
                long removed = surplus.divide(pattern.exactOutputAmount()).min(BigInteger.valueOf(count)).longValueExact();
                count -= removed;
                surplus = surplus.subtract(pattern.exactOutputAmount().multiply(BigInteger.valueOf(removed)));
                if (count == 0) continue;
                trimmed.put(pattern, count);
                for (var input : pattern.inputs()) {
                    if ((++inputIndex & 255) == 0) PlanningCancellation.check();
                    need.merge(input.key(), input.exactAmount().multiply(BigInteger.valueOf(count)), BigInteger::add);
                }
            }
            for (K input : edges.getOrDefault(key, new LinkedHashSet<>()))
                if (degree.merge(input, -1, Integer::sum) == 0) ready.addLast(input);
        }
        return visited == degree.size() ? trimmed : null;
    }

    static boolean ordinary(CraftPattern<?> pattern) {
        if (!pattern.byproducts().isEmpty() || pattern.exactOutputAmount().compareTo(BigInteger.valueOf(Sat.SAT)) >= 0)
            return false;
        int index = 0;
        for (var input : pattern.inputs()) {
            if ((index++ & 255) == 0) PlanningCancellation.check();
            if (!ordinaryInput(input)) return false;
        }
        return true;
    }

    private static boolean ordinaryInput(CraftInput<?> input) {
        return !input.returned() && input.remainder() == null && input.reusableStockSource() == null
                && input.exactAmount().compareTo(BigInteger.valueOf(Sat.SAT)) < 0;
    }

    private static <K> long executions(List<CraftPattern<K>> patterns, CraftPlan<K> plan) {
        long count = 0;
        for (var pattern : patterns) count = Sat.add(count, plan.firings().get(pattern));
        return count;
    }
}
