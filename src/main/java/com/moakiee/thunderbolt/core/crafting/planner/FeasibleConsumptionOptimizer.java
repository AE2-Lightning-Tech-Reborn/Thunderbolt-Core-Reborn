package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntPredicate;

/**
 * Optional anytime improvement of an already certified plan; probes never replace its witness.
 * Fewer executions come first, using any ordinary stock in the inventory snapshot. At equal
 * executions, stock and net resource loss must improve without increasing any material's draw.
 *
 * <p>Candidates are pruned before the planner runs. A forward fixpoint from the available stock
 * keeps only patterns that can ever fire, and a dual-feasible cost potential over them bounds the
 * executions (and stock units) of every plan inside the probed pattern set. A probe whose bound
 * cannot beat the incumbent by 1% executions, or by stock at equal executions, never enters the
 * planner, and once the whole reachable graph is bounded that way the search stops.
 *
 * <p>The first proposal is the cheapest route per item over the whole reachable graph, with scarce
 * stock priced up until its fixed replay fits the inventory snapshot. Subsequent local probes use
 * the incumbent's draws: adding unrelated stocked routes to every withdrawal dilutes the bounded
 * search and can hide the improvements it already finds within the incumbent's materials.
 */
final class FeasibleConsumptionOptimizer {
    static final int MAX_PROBES = 32;
    static final long MAX_NANOS = 2_800_000_000L;
    static final long EXPORT_RESERVE_NANOS = 50_000_000L;
    /**
     * Stop once probes have spent this long without cutting executions by at least 1%; 0 disables.
     * Measured in time, not probes: a probe is a whole planning run, milliseconds on a small graph
     * and over a second on a large pack, and the player waits for every one of them.
     */
    static final long STALL_NANOS = Math.max(0L, Long.getLong("thunderbolt.feasibleOptimizationStallMs", 500L))
            * 1_000_000L;
    static final int MIN_GAIN_PERCENT = 1;
    /** Cheapest fireable alternatives kept per key the incumbent touches. */
    static final int REGION_ALTERNATIVES = 8;
    static final int MAX_REGION_PATTERNS = 2048;
    /** Stock price rounds for the whole-graph policy proposal. */
    static final int POLICY_ROUNDS = 6;
    /** Largest set of same-item producers withdrawn together. */
    static final int MAX_WITHDRAWN = 4;
    /** Larger graphs retain their existing probe allocation and local-search behavior. */
    private static final int MAX_MIXED_PATTERNS = 16;

    // Evaluation-only trace.
    static final boolean TRACE = Boolean.getBoolean("thunderbolt.feasibleOptimizationTrace");
    static final ThreadLocal<String> LAST_TRACE = new ThreadLocal<>();

    record Result<K>(CraftPlan<K> plan, int probes, int improvements, int pruned) {}

    /** A null order leaves the rank fallback's DAG check at its original call site. */
    private record Policy<K>(Map<K, List<CraftPattern<K>>> selected, List<K> order) {}

    private FeasibleConsumptionOptimizer() {}

    static <K> Result<K> optimize(CraftGraph<K> graph, K target, long amount, CraftPlan<K> initial,
            int probeLimit, Function<CraftGraph<K>, CraftPlan<K>> oracle) {
        return optimize(graph, target, amount, initial, probeLimit, oracle, new IndexCache<>(), work -> true);
    }

    static <K> Result<K> optimize(CraftGraph<K> graph, K target, long amount, CraftPlan<K> initial,
            int probeLimit, Function<CraftGraph<K>, CraftPlan<K>> oracle, IndexCache<K> cache) {
        return optimize(graph, target, amount, initial, probeLimit, oracle, cache, work -> true);
    }

    static <K> Result<K> optimize(CraftGraph<K> graph, K target, long amount, CraftPlan<K> initial,
            int probeLimit, Function<CraftGraph<K>, CraftPlan<K>> oracle, IntPredicate spendWork) {
        return optimize(graph, target, amount, initial, probeLimit, oracle, new IndexCache<>(), spendWork);
    }

    static <K> Result<K> optimize(CraftGraph<K> graph, K target, long amount, CraftPlan<K> initial,
            int probeLimit, Function<CraftGraph<K>, CraftPlan<K>> oracle, IndexCache<K> cache,
            IntPredicate spendWork) {
        var state = new Search<>(graph, target, amount, initial, probeLimit, oracle, cache, spendWork);
        try {
            state.run();
            state.mixSmallBatches();
            state.mixTerminalBatches();
            state.probeAvailableAlternatives();
        } catch (PlanningCancellation.OptionalWorkLimit exhausted) {
            // External cancellation and the enclosing router deadline must still propagate.
            state.stop = "time";
        }
        if (TRACE) LAST_TRACE.set(state.trace());
        return new Result<>(state.best, state.probes, state.improvements, state.pruned);
    }

    /** Calculation-owned recipe index. Stock limits and propagation remain private to each probe. */
    static final class IndexCache<K> {
        private CraftGraph<K> graph;
        private K target;
        private Index<K> index;
        private boolean compiled;

        boolean isCompiled() { return compiled; }

        Index<K> get(CraftGraph<K> candidate, K key) {
            PlanningCancellation.check();
            if (!compiled || graph != candidate || !java.util.Objects.equals(target, key)) {
                // Publish only a completed scan, so cancellation cannot leave a partial index.
                var prepared = Index.build(candidate, key);
                graph = candidate;
                target = key;
                index = prepared;
                compiled = true;
            }
            return index;
        }
    }

    /**
     * A target-only plan at the largest possible target yield cannot reserve any firing for
     * upstream production. When every target producer consumes only other ordinary keys, all
     * of those inputs must come from inventory. Matching both lower bounds therefore leaves
     * no strict componentwise stock or net-loss improvement at the same execution count.
     */
    static <K> boolean targetOnlyBound(CraftGraph<K> graph, K target, long amount, CraftPlan<K> best) {
        if (graph.hasByproducts() || graph.hasTagConversions()
                || graph.stock(target) != 0 || best.firings().isEmpty()) return false;
        BigInteger largestOutput = BigInteger.ZERO;
        BigInteger smallestDraw = null;
        for (var pattern : graph.patternsFor(target)) {
            PlanningCancellation.check();
            if (stateful(pattern)) return false;
            BigInteger draw = BigInteger.ZERO;
            for (var input : pattern.inputs()) {
                PlanningCancellation.check();
                if (input.key().equals(target)) return false;
                draw = draw.add(input.exactAmount());
            }
            largestOutput = largestOutput.max(pattern.exactOutputAmount());
            smallestDraw = smallestDraw == null ? draw : smallestDraw.min(draw);
        }
        if (largestOutput.signum() <= 0) return false;
        var needed = BigInteger.valueOf(amount);
        var lowerExecutions = needed.add(largestOutput).subtract(BigInteger.ONE).divide(largestOutput);
        if (!executions(best).equals(lowerExecutions)) return false;
        for (var pattern : best.firings().keySet()) if (!pattern.output().equals(target)) return false;
        BigInteger used = BigInteger.ZERO;
        for (long value : best.usedStock().values()) used = used.add(BigInteger.valueOf(value));
        return smallestDraw.multiply(lowerExecutions).compareTo(used) >= 0;
    }


    private static final class Search<K> {
        private final CraftGraph<K> graph;
        private final K target;
        private final long amount;
        private final int limit;
        private final Function<CraftGraph<K>, CraftPlan<K>> oracle;
        private final IndexCache<K> cache;
        private final IntPredicate spendWork;
        private CraftPlan<K> best;
        private int probes;
        private int mixedProbes;
        private int improvements;
        private int pruned;
        private long lastGain;
        private boolean exhausted;
        private boolean targetOptimal;

        private Index<K> index;
        private long[] limits;
        private int[] limited;
        private boolean availableStock = true;
        private Prop global;
        /** Withdrawal sets extended by the substitutes the planner chose last time. */
        private final ArrayDeque<int[]> chains = new ArrayDeque<>();
        /** The last confirmed candidate within the stock limits, improving or not. */
        private CraftPlan<K> lastCandidate;
        private double[] globalCost;
        private int[] globalArgmin;
        /** First full-inventory choices, kept for the final single-route proposals only. */
        private int[] availableChoices;
        private long availableExecBound;
        private long execBound;
        private long stockBound;
        private int[] region;
        private int regionVersion;
        /** Whether the region also offers the alternative producers of its filled inputs. */
        private boolean extendRegion;
        private boolean regionExtensible;

        // Trace
        private String stop = "none";
        private final long started = System.nanoTime();
        private long indexNanos, propagationNanos;
        private long initialExecutions, initialStock;
        private final List<Long> oracleNanos = new ArrayList<>();
        private int regionSize;

        Search(CraftGraph<K> graph, K target, long amount, CraftPlan<K> initial, int limit,
                Function<CraftGraph<K>, CraftPlan<K>> oracle, IndexCache<K> cache, IntPredicate spendWork) {
            this.graph = graph;
            this.target = target;
            this.amount = amount;
            this.best = initial;
            this.limit = Math.min(MAX_PROBES, limit);
            this.oracle = oracle;
            this.cache = cache;
            this.spendWork = spendWork;
        }

        /** Finish small ordinary DAGs with integer batch mixes under the same budgets. */
        private void mixSmallBatches() {
            if (targetOptimal || index == null || !index.choices || index.patterns.size() > MAX_MIXED_PATTERNS
                    || exhausted || probes >= limit)
                return;
            for (var pattern : index.patterns)
                if (stateful(pattern) || !pattern.byproducts().isEmpty()) return;
            IntPredicate charge = work -> {
                if (exhausted || !spendWork.test(work)) {
                    exhausted = true;
                    stop = "budget";
                    return false;
                }
                return true;
            };
            BooleanSupplier reserve = () -> {
                if (exhausted || probes >= limit) return false;
                probes++;
                mixedProbes++;
                return true;
            };
            while (!exhausted && probes < limit) {
                PlanningCancellation.check();
                var ordinary = OrdinaryBatchOptimizer.startSearch(graph, target, amount, best, charge, reserve);
                var candidate = ordinary.tryWhole(index.keys);
                UpstreamBatchOptimizer.Search<K> upstream = null;
                if (candidate == null && !exhausted && probes < limit) {
                    upstream = UpstreamBatchOptimizer.startSearch(graph, target, amount, best, index.keys,
                            charge, reserve);
                    if (upstream != null) candidate = upstream.tryEstablished();
                }
                // New neighborhoods must not preempt the established upstream replacement path.
                if (candidate == null && !exhausted && probes < limit)
                    candidate = ordinary.tryAdditional();
                // Keep the same upstream allowance after the earlier local neighborhoods finish.
                if (candidate == null && upstream != null && !exhausted && probes < limit)
                    candidate = upstream.tryAdditional();
                if (candidate == null || !improves(best, candidate)) break;
                best = candidate;
                improvements++;
            }
            if (!exhausted && probes < limit) {
                var normalized = OrdinaryBatchOptimizer.normalizeStock(graph, target, amount, best, charge, reserve);
                if (normalized != null && improves(best, normalized)) {
                    best = normalized;
                    improvements++;
                }
            }
        }

        /** Larger terminal portfolios use only the allowance left by the established search. */
        private void mixTerminalBatches() {
            if (targetOptimal || index == null || !TerminalBatchOptimizer.acceptsRouteCount(index.patterns.size())
                    || exhausted || probes >= limit) return;
            var candidate = TerminalBatchOptimizer.tryImprove(graph, target, amount, best, index.patterns,
                    work -> {
                        if (exhausted || !spendWork.test(work)) {
                            exhausted = true;
                            stop = "budget";
                            return false;
                        }
                        return true;
                    }, () -> {
                        if (exhausted || probes >= limit) return false;
                        probes++;
                        mixedProbes++;
                        return true;
                    });
            if (candidate != null && improves(best, candidate)) {
                best = candidate;
                improvements++;
            }
            mixTerminalFixedDepthBatches();
        }

        /** Search the supplemental neighborhood only after accepting the established result. */
        private void mixTerminalFixedDepthBatches() {
            if (exhausted || probes >= limit) return;
            var candidate = TerminalFixedDepthOptimizer.tryImprove(graph, target, amount, best,
                    work -> {
                        if (exhausted || !spendWork.test(work)) {
                            exhausted = true;
                            stop = "budget";
                            return false;
                        }
                        return true;
                    }, () -> {
                        if (exhausted || probes >= limit) return false;
                        probes++;
                        mixedProbes++;
                        return true;
                    });
            if (candidate != null && improves(best, candidate)) {
                best = candidate;
                improvements++;
            }
        }

        void run() {
            if (limit <= 0 || !best.feasible() || amount <= 0 || amount >= Sat.SAT) return;
            // Saturated accounting belongs to the existing exact display continuation. It is not
            // an executable incumbent whose truncated coordinates can safely be optimized.
            if (best.usedStock().values().stream().anyMatch(Sat::isSaturated)
                    || best.firings().values().stream().anyMatch(Sat::isSaturated)
                    || best.grossDemand().values().stream().anyMatch(Sat::isSaturated)) return;
            initialExecutions = executionCount(best);
            initialStock = stockCount(best);
            if (targetOnlyBound()) {
                targetOptimal = true;
                stop = "target-lb";
                return;
            }
            long indexStarted = System.nanoTime();
            index = cache.get(graph, target);
            indexNanos = System.nanoTime() - indexStarted;
            if (index == null) {
                stop = "saturated";
                return;
            }
            if (!index.choices) {
                stop = "fixed"; // Fixed ordinary recipe chains have no allocation to improve.
                return;
            }

            propagateStock();
            if (!graph.hasByproducts() && index.patterns.size() <= MAX_REGION_PATTERNS) {
                availableChoices = global.freeArgmin;
                availableExecBound = execBound;
            }
            if (proven()) {
                stop = "lb";
                return;
            }

            probePolicy();
            if (targetOnlyBound()) {
                targetOptimal = true;
                stop = "target-lb";
                return;
            }
            // Keep the full-inventory proposal separate from the established local search. Its
            // confirmed improvement becomes the new incumbent; rejected proposals cannot divert
            // local withdrawals through unrelated stock. Both phases share the same work limits.
            availableStock = false;
            propagateStock();
            if (!proven()) probePolicy();
            // The stall clock times withdrawals only. Indexing, bounds and the policy proposal
            // are paid once and grow with the pack; a slow machine must still get to reroute.
            lastGain = System.nanoTime();
            var tried = new HashSet<Integer>();
            while (!exhausted && probes < limit && !stalled()) {
                PlanningCancellation.check();
                if (proven()) {
                    stop = "lb";
                    return;
                }
                if (!withdraw(tried)) {
                    // Every route has had its turn in the closed region; widen it once and retry.
                    if (!extendRegion && regionExtensible) {
                        extendRegion = true;
                        region = null;
                        tried.clear();
                        chains.clear();
                        continue;
                    }
                    stop = "done";
                    return;
                }
            }
            stop = exhausted ? "budget" : probes >= limit ? "probes" : stalled() ? "stall" : stop;
        }

        private boolean targetOnlyBound() {
            return FeasibleConsumptionOptimizer.targetOnlyBound(graph, target, amount, best);
        }

        private void propagateStock() {
            long propagationStarted = System.nanoTime();
            limits = new long[index.keys.size()];
            var limitedKeys = new IntBuf();
            for (int id = 0; id < index.keys.size(); id++) {
                K key = index.keys.get(id);
                long value = availableStock ? graph.stock(key) : best.usedStock().getOrDefault(key, 0L);
                if (value > 0) {
                    limits[id] = value;
                    limitedKeys.add(id);
                }
            }
            limited = limitedKeys.toArray();
            int[] all = new int[index.patterns.size()];
            for (int p = 0; p < all.length; p++) all[p] = p;
            global = propagate(all, true);
            globalCost = global.cost;
            globalArgmin = global.argmin;
            execBound = global.execBound;
            stockBound = global.stockBound;
            // The bounds assume every executable plan fires only patterns the fixpoint reaches.
            // The incumbent is executable; if the model disagrees with it, trust no bound.
            for (var pattern : best.firings().keySet()) {
                Integer id = index.patternIds.get(pattern);
                if (id == null || !global.fired[id]) {
                    execBound = 0;
                    stockBound = 0;
                    global.stockBound = 0;
                    global.stockBoundComputed = true;
                    break;
                }
            }
            propagationNanos += System.nanoTime() - propagationStarted;
        }

        private boolean stalled() {
            return STALL_NANOS != 0 && System.nanoTime() - lastGain >= STALL_NANOS;
        }

        private boolean proven() {
            long current = executionCount(best);
            if (execBound <= current - minimumGain(current)) return false;
            if (execBound > current || stockCount(best) == 0) return true;
            stockBound = stockBound(global);
            return stockBound >= stockCount(best);
        }

        private boolean worthwhile(Prop prop) {
            long current = executionCount(best);
            if (prop.execBound <= current - minimumGain(current)) return true;
            if (prop.execBound > current || stockCount(best) == 0) return false;
            return stockBound(prop) < stockCount(best);
        }

        private boolean worthwhile(long executions, long stock) {
            long current = executionCount(best);
            return executions <= current - minimumGain(current)
                    || executions <= current && stock < stockCount(best);
        }

        /**
         * Whole-graph proposals: the cheapest producer per key, with stock free up to a price.
         * A proposal that overdraws some stock raises that item's price (a Lagrangian step on its
         * limit) and is recomputed, so a route that only looked cheap because it spent scarce
         * stock gives way to one that produces it. The fixed DAG's exact replay decides each
         * proposal before the planner is asked to confirm it.
         */
        private void probePolicy() {
            double[] price = new double[index.keys.size()];
            Map<K, List<CraftPattern<K>>> previous = null;
            Set<K> overdrawn = Set.of();
            for (int round = 0; round < POLICY_ROUNDS && !exhausted && probes < limit; round++) {
                var policy = policy(price);
                if (policy == null) return;
                var selected = policy.selected();
                if (selected.equals(previous)) {
                    // The price was too low to move the argmin; raise it again without replanning.
                    if (!raise(price, overdrawn, round)) return;
                    continue;
                }
                previous = selected;
                List<K> order = policy.order();
                if (order == null) order = dagOrder(selected);
                if (order.isEmpty()) return;
                boolean changesRoute = best.firings().keySet().stream().anyMatch(p ->
                        !selected.getOrDefault(p.output(), List.of()).contains(p));
                if (!changesRoute) return;
                var candidate = candidateGraph(selected);
                // The ordinary fixed DAG has a cheap exact shortage test. Do not launch a whole
                // alternative search for a policy already known to exceed the available stock.
                var fixed = ConservativeReplenishment.compileFixed(candidate, order, selected);
                if (fixed == null) return;
                var proposal = fixed.plan(target, amount, Map.of());
                if (proposal == null) return;
                if (proposal.feasible()) {
                    if (worthwhile(executionCount(proposal), stockCount(proposal))) probe(candidate);
                    else pruned++;
                    return;
                }
                overdrawn = proposal.missing().keySet();
                if (!raise(price, overdrawn, round)) return;
            }
        }

        /** Price overdrawn stock higher: 0, 1, 8, 64, ..., and prohibitive on the last round. */
        private boolean raise(double[] price, Set<K> overdrawn, int round) {
            boolean raised = false;
            for (var key : overdrawn) {
                Integer id = index.ids.get(key);
                if (id == null || limits[id] == 0 || Double.isInfinite(price[id])) continue;
                price[id] = round + 2 >= POLICY_ROUNDS ? Double.POSITIVE_INFINITY
                        : price[id] == 0 ? 1 : price[id] * 8;
                raised = true;
            }
            return raised;
        }

        /**
         * Fractional unit costs can select a wasteful batch or overdraw a shared stock. After
         * the established search finishes, change one producer in the free-inventory policy
         * and rebuild its upstream choices. Exact fixed replay decides whether it is worth
         * confirming under the existing 1% gain threshold. These proposals never change the
         * bounds or the earlier search order.
         */
        private void probeAvailableAlternatives() {
            long current = executionCount(best);
            if (targetOptimal || availableChoices == null || exhausted || probes >= limit || best.firings().isEmpty()
                    || availableExecBound > current - minimumGain(current) || !best.usedReusableStock().isEmpty()) return;
            long work = index.keys.size() + (long) index.patterns.size();
            for (int p = 0; p < index.patterns.size(); p++) {
                PlanningCancellation.check();
                if (index.stateful[p]) return;
                work += index.patterns.get(p).inputs().size();
            }
            int charge = (int) Math.min(Integer.MAX_VALUE, work);
            if (!spendWork.test(charge)) { exhausted = true; stop = "budget"; return; }
            availableStock = true;
            for (int k = 0; k < limits.length; k++) {
                if ((k & 255) == 0) PlanningCancellation.check();
                limits[k] = graph.stock(index.keys.get(k));
            }
            int[] choice = availableChoices.clone();
            var base = select(choice);
            if (base == null) return;
            int attempts = 0;
            // Index order visits the target first and preserves the original producer order.
            for (int key = 0; key < index.keys.size(); key++) {
                if ((key & 255) == 0) PlanningCancellation.check();
                if (!base.containsKey(index.keys.get(key)) || choice[key] < 0) continue;
                int original = choice[key];
                for (int j = index.producerStart[key]; j < index.producerStart[key + 1]; j++) {
                    int p = index.producer[j];
                    if (p == original) continue;
                    if (attempts++ >= MAX_PROBES || exhausted || probes >= limit) return;
                    PlanningCancellation.check();
                    if (!spendWork.test(charge)) { exhausted = true; stop = "budget"; return; }
                    choice[key] = p;
                    var selected = select(choice);
                    choice[key] = original;
                    if (selected == null) continue;
                    var order = dagOrder(selected);
                    if (order.isEmpty()) continue;
                    var candidate = candidateGraph(selected);
                    var fixed = ConservativeReplenishment.compileFixed(candidate, order, selected);
                    var proposal = fixed == null ? null : fixed.plan(target, amount, Map.of());
                    if (proposal == null || executionCount(proposal) > current - minimumGain(current)
                            || !improves(best, proposal)) continue;
                    probe(candidate);
                    current = executionCount(best);
                    if (availableExecBound > current - minimumGain(current)) return;
                }
            }
        }

        /**
         * Cheapest producer per key with limited stock at {@code price}. When the argmin is cyclic,
         * only producers whose inputs all became available strictly earlier are admitted.
         */
        private Policy<K> policy(double[] price) {
            if (!global.targetAvailable) return null;
            int n = index.keys.size();
            boolean free = true;
            for (double value : price) free &= value == 0;
            // The zero-price first round has already been relaxed for the execution bound.
            int[] choice;
            if (free) choice = global.freeArgmin.clone();
            else {
                double[] cost = stockCost(price);
                choice = new int[n];
                Arrays.fill(choice, -1);
                relax(global, 1, cost, choice);
            }
            var selected = select(choice);
            if (selected != null) {
                List<K> order = dagOrder(selected);
                if (!order.isEmpty()) return new Policy<>(selected, order);
            }
            Integer[] byRank = new Integer[n];
            int count = 0;
            for (int k = 0; k < n; k++) if (global.rank[k] >= 0) byRank[count++] = k;
            Arrays.sort(byRank, 0, count, (a, b) -> Integer.compare(global.rank[a], global.rank[b]));
            double[] cost = stockCost(price);
            Arrays.fill(choice, -1);
            for (int r = 0; r < count; r++) {
                PlanningCancellation.check();
                int key = byRank[r];
                for (int j = index.producerStart[key]; j < index.producerStart[key + 1]; j++) {
                    int p = index.producer[j];
                    if (!global.fired[p]) continue;
                    boolean earlier = true;
                    for (int i = index.needStart[p]; i < index.needStart[p + 1] && earlier; i++)
                        earlier = global.rank[index.needKey[i]] >= 0
                                && global.rank[index.needKey[i]] < global.rank[key];
                    if (!earlier) continue;
                    double value = index.executionCost[p];
                    for (int i = index.useStart[p]; i < index.useStart[p + 1]; i++)
                        value += cost[index.useKey[i]] * index.useAmount[i];
                    value /= index.outAmount[p];
                    if (value < cost[key]) {
                        cost[key] = value;
                        choice[key] = p;
                    }
                }
            }
            selected = select(choice);
            return selected == null ? null : new Policy<>(selected, null);
        }

        private double[] stockCost(double[] price) {
            double[] cost = new double[index.keys.size()];
            Arrays.fill(cost, Double.POSITIVE_INFINITY);
            for (int key : limited) cost[key] = price[key];
            for (int k = 0; k < cost.length; k++) if (global.side[k]) cost[k] = 0;
            return cost;
        }

        private Map<K, List<CraftPattern<K>>> select(int[] choice) {
            var selected = new HashMap<K, List<CraftPattern<K>>>();
            var pending = new ArrayDeque<Integer>();
            pending.add(index.ids.get(target));
            while (!pending.isEmpty()) {
                PlanningCancellation.check();
                int key = pending.removeFirst();
                K item = index.keys.get(key);
                if (selected.containsKey(item)) continue;
                int p = choice[key];
                if (p < 0) {
                    if (limits[key] == 0 && !global.side[key]) return null;
                    selected.put(item, List.of());
                    continue;
                }
                selected.put(item, List.of(index.patterns.get(p)));
                for (int j = index.needStart[p]; j < index.needStart[p + 1]; j++) pending.add(index.needKey[j]);
            }
            // Held inputs (catalysts, returned containers) come from stock; the replay checks them.
            for (var routes : List.copyOf(selected.values()))
                for (var pattern : routes)
                    for (var input : pattern.inputs()) selected.putIfAbsent(input.key(), List.of());
            return selected;
        }

        /**
         * Withdraw the busiest remaining ordinary route and let the planner reroute it inside the
         * local region. Stateful patterns keep their firings, so they are never withdrawn. A
         * reroute that loses overall may still hold a better route for the withdrawn item alone,
         * so that part is grafted onto the incumbent and confirmed separately. When the planner
         * only swaps in another producer of a withdrawn item, that producer is withdrawn together
         * with it later, so a cheaper route further away gets its turn.
         */
        private boolean withdraw(HashSet<Integer> tried) {
            int busiest = -1;
            long most = 0;
            for (var entry : best.firings().entrySet()) {
                Integer id = index.patternIds.get(entry.getKey());
                if (id == null || index.stateful[id] || tried.contains(id)) continue;
                // Ties go to the lowest id: pattern maps iterate in identity-hash order.
                if (entry.getValue() > most || entry.getValue() == most && id < busiest) {
                    busiest = id;
                    most = entry.getValue();
                }
            }
            int[] withdrawn;
            if (busiest >= 0) {
                tried.add(busiest);
                withdrawn = new int[] {busiest};
            } else {
                // Single withdrawals first; extended ones only once every route has had its turn.
                withdrawn = chains.pollFirst();
                if (withdrawn == null) return false;
            }
            int[] shared = sharedRegion();
            var candidate = new IntBuf();
            outer:
            for (int p : shared) {
                for (int q : withdrawn) if (p == q) continue outer;
                candidate.add(p);
            }
            int[] patterns = candidate.toArray();
            Prop local = propagate(patterns, false);
            if (!local.targetAvailable || !worthwhile(local)) {
                pruned++;
                return true;
            }
            int version = regionVersion;
            boolean improved = probe(regionGraph(patterns));
            var rerouted = lastCandidate;
            if (!improved && rerouted != null) improved = graft(withdrawn, rerouted);
            if (!improved && version == regionVersion && rerouted != null
                    && withdrawn.length < MAX_WITHDRAWN) {
                var outputs = new HashSet<Integer>();
                for (int q : withdrawn) outputs.add(index.out[q]);
                var next = new IntBuf();
                for (int q : withdrawn) next.add(q);
                for (int id : ids(rerouted)) {
                    if (!index.stateful[id] && outputs.contains(index.out[id])
                            && !best.firings().containsKey(index.patterns.get(id)) && next.size < MAX_WITHDRAWN) next.add(id);
                }
                if (next.size > withdrawn.length) chains.addLast(next.toArray());
            }
            if (improved) chains.clear();
            return true;
        }

        /**
         * The planner reroutes a withdrawn item but may also disturb unrelated items. Keep only
         * its new routes for the withdrawn items and whatever those newly need, graft them onto
         * the incumbent's routes, and confirm the single-route result.
         */
        private boolean graft(int[] withdrawn, CraftPlan<K> rerouted) {
            var incumbent = new HashMap<K, List<CraftPattern<K>>>();
            var replacement = new HashMap<K, List<CraftPattern<K>>>();
            for (int id : ids(best))
                incumbent.computeIfAbsent(index.keys.get(index.out[id]), ignored -> new ArrayList<>()).add(index.patterns.get(id));
            for (int id : ids(rerouted))
                replacement.computeIfAbsent(index.keys.get(index.out[id]), ignored -> new ArrayList<>()).add(index.patterns.get(id));
            var selected = new HashMap<K, List<CraftPattern<K>>>();
            var pending = new ArrayDeque<K>();
            for (int q : withdrawn) {
                K key = index.keys.get(index.out[q]);
                if (!selected.containsKey(key)) {
                    selected.put(key, replacement.getOrDefault(key, List.of()));
                    pending.add(key);
                }
            }
            pending.add(target);
            boolean changed = false;
            while (!pending.isEmpty()) {
                PlanningCancellation.check();
                K key = pending.removeFirst();
                List<CraftPattern<K>> routes = selected.get(key);
                if (routes == null) {
                    routes = incumbent.getOrDefault(key, replacement.getOrDefault(key, List.of()));
                    selected.put(key, routes);
                }
                for (var pattern : routes) {
                    changed |= !best.firings().containsKey(pattern);
                    for (var input : pattern.inputs()) if (!selected.containsKey(input.key())) pending.add(input.key());
                }
            }
            if (!changed || selected.equals(replacement)) return false;
            var candidate = candidateGraph(selected);
            List<K> order = dagOrder(selected);
            if (!order.isEmpty()) {
                var fixed = ConservativeReplenishment.compileFixed(candidate, order, selected);
                var proposal = fixed == null ? null : fixed.plan(target, amount, Map.of());
                if (proposal != null && (!proposal.feasible()
                        || !worthwhile(executionCount(proposal), stockCount(proposal)))) {
                    pruned++;
                    return false;
                }
            }
            return probe(candidate);
        }

        /**
         * The incumbent's patterns, the cheapest fireable alternatives for every key it touches,
         * and producers for inputs those alternatives would otherwise lack; once widened, also
         * alternative producers of those inputs.
         */
        private int[] sharedRegion() {
            if (region != null) return region;
            int n = index.keys.size();
            boolean[] in = new boolean[index.patterns.size()];
            boolean[] produced = new boolean[n];
            boolean[] incumbent = new boolean[index.patterns.size()];
            var list = new IntBuf();
            var touched = new LinkedHashSet<Integer>();
            for (int p : ids(best)) {
                incumbent[p] = true;
                in[p] = true;
                produced[index.out[p]] = true;
                list.add(p);
                touched.add(index.out[p]);
                for (int j = index.needStart[p]; j < index.needStart[p + 1]; j++) touched.add(index.needKey[j]);
            }
            touched.add(index.ids.get(target));
            for (int key : touched) {
                PlanningCancellation.check();
                var alternatives = new ArrayList<Integer>();
                for (int j = index.producerStart[key]; j < index.producerStart[key + 1]; j++) {
                    int p = index.producer[j];
                    if (!in[p] && global.fired[p] && !index.stateful[p]) alternatives.add(p);
                }
                alternatives.sort((a, b) -> Double.compare(patternCost(a), patternCost(b)));
                for (int i = 0; i < alternatives.size() && i < REGION_ALTERNATIVES
                        && list.size < MAX_REGION_PATTERNS; i++) {
                    int p = alternatives.get(i);
                    in[p] = true;
                    produced[key] = true;
                    list.add(p);
                }
            }
            var filled = new IntBuf();
            for (int i = 0; i < list.size && list.size < MAX_REGION_PATTERNS; i++) {
                int p = list.data[i];
                for (int j = index.needStart[p]; j < index.needStart[p + 1]; j++) {
                    int key = index.needKey[j];
                    if (produced[key] || limits[key] > 0) continue;
                    produced[key] = true;
                    filled.add(key);
                    for (int extra : new int[] {globalArgmin[key], global.first[key]}) {
                        if (extra >= 0 && !in[extra] && (!index.stateful[extra] || incumbent[extra])
                                && list.size < MAX_REGION_PATTERNS) {
                            in[extra] = true;
                            list.add(extra);
                        }
                    }
                }
            }
            // Equally cheap producers of those inputs can differ in which stock they draw. Once
            // the region is closed, the widened region also offers the cheapest few that need
            // nothing it lacks. They crowd the planner's choices, so they come second.
            int closed = list.size;
            for (int f = 0; f < filled.size && list.size < MAX_REGION_PATTERNS; f++) {
                PlanningCancellation.check();
                int key = filled.data[f];
                var alternatives = new ArrayList<Integer>();
                outer:
                for (int k = index.producerStart[key]; k < index.producerStart[key + 1]; k++) {
                    int q = index.producer[k];
                    if (in[q] || !global.fired[q] || index.stateful[q]) continue;
                    for (int j = index.needStart[q]; j < index.needStart[q + 1]; j++)
                        if (!produced[index.needKey[j]] && limits[index.needKey[j]] == 0) continue outer;
                    alternatives.add(q);
                }
                alternatives.sort((a, b) -> Double.compare(patternCost(a), patternCost(b)));
                for (int i = 0; i < alternatives.size() && i < REGION_ALTERNATIVES
                        && list.size < MAX_REGION_PATTERNS; i++) {
                    in[alternatives.get(i)] = true;
                    list.add(alternatives.get(i));
                }
            }
            regionExtensible = list.size > closed;
            region = extendRegion ? list.toArray() : Arrays.copyOf(list.data, closed);
            regionSize = region.length;
            return region;
        }

        /** A plan's fired pattern ids in index order, independent of map iteration order. */
        private int[] ids(CraftPlan<K> plan) {
            var ids = new IntBuf();
            for (var pattern : plan.firings().keySet()) {
                Integer id = index.patternIds.get(pattern);
                if (id != null) ids.add(id);
            }
            int[] sorted = ids.toArray();
            Arrays.sort(sorted);
            return sorted;
        }

        private double patternCost(int p) {
            double value = index.executionCost[p];
            for (int j = index.useStart[p]; j < index.useStart[p + 1]; j++) {
                double cost = globalCost[index.useKey[j]];
                value += (Double.isInfinite(cost) ? 0 : cost) * index.useAmount[j];
            }
            return value / index.outAmount[p];
        }

        private CraftGraph<K> regionGraph(int[] patterns) {
            int[] sorted = patterns.clone();
            Arrays.sort(sorted); // Index order keeps each key's original route order.
            var selected = new HashMap<K, List<CraftPattern<K>>>();
            // The incumbent's routes lead, so a rerouted item does not disturb the others.
            for (int pass = 0; pass < 2; pass++)
                for (int p : sorted) {
                    var pattern = index.patterns.get(p);
                    if (best.firings().containsKey(pattern) == (pass == 0))
                        selected.computeIfAbsent(index.keys.get(index.out[p]), ignored -> new ArrayList<>()).add(pattern);
                }
            return candidateGraph(selected);
        }

        private CraftGraph<K> candidateGraph(Map<K, List<CraftPattern<K>>> selected) {
            var candidate = graph.withPatterns(selected);
            return availableStock ? candidate : candidate.withStockLimits(best.usedStock());
        }

        private boolean probe(CraftGraph<K> candidateGraph) {
            PlanningCancellation.check();
            if (exhausted || probes >= limit) return false;
            probes++;
            long probeStarted = System.nanoTime();
            CraftPlan<K> candidate = oracle.apply(candidateGraph);
            oracleNanos.add(System.nanoTime() - probeStarted);
            lastCandidate = null;
            if (candidate == null) {
                exhausted = true;
                return false;
            }
            for (var entry : candidate.usedStock().entrySet())
                if (entry.getValue() < 0 || entry.getValue() > candidateGraph.stock(entry.getKey())) return false;
            lastCandidate = candidate;
            if (!improves(best, candidate)) return false;
            BigInteger before = executions(best);
            BigInteger gain = before.subtract(executions(candidate));
            // Stock-only tie-breaks and sub-percent gains do not restart the stall clock.
            if (gain.signum() > 0 && gain.multiply(BigInteger.valueOf(100))
                    .compareTo(before.multiply(BigInteger.valueOf(MIN_GAIN_PERCENT))) >= 0) lastGain = System.nanoTime();
            best = candidate;
            improvements++;
            region = null;
            regionVersion++;
            return true;
        }

        /**
         * Forward fixpoint and dual costs over the given patterns under the available stock.
         * Every executable plan fires only reached patterns: a first firing needs its inputs from
         * stock or from an earlier firing. On reached patterns, y = c / s satisfies
         * y(out) * out - sum y(in) * in <= executionCost (byproduct keys priced at 0), so amount * y(target)
         * bounds executions; a second potential that also prices limited stock subtracts it.
         */
        private Prop propagate(int[] patterns, boolean keepChoices) {
            int n = index.keys.size(), m = patterns.length;
            var prop = new Prop();
            prop.patterns = patterns;
            int[] head = new int[n + 1];
            for (int p : patterns)
                for (int j = index.needStart[p]; j < index.needStart[p + 1]; j++) head[index.needKey[j] + 1]++;
            for (int k = 0; k < n; k++) head[k + 1] += head[k];
            int[] consumer = new int[head[n]];
            int[] fill = Arrays.copyOf(head, n);
            int[] missing = new int[m];
            for (int li = 0; li < m; li++) {
                int p = patterns[li];
                missing[li] = index.needStart[p + 1] - index.needStart[p];
                for (int j = index.needStart[p]; j < index.needStart[p + 1]; j++) consumer[fill[index.needKey[j]]++] = li;
            }
            prop.consumerStart = head;
            prop.consumer = consumer;
            prop.avail = new boolean[n];
            prop.side = new boolean[n];
            prop.produced = new boolean[n];
            prop.rank = new int[n];
            prop.first = new int[n];
            Arrays.fill(prop.rank, -1);
            Arrays.fill(prop.first, -1);
            prop.fired = new boolean[m];
            prop.order = new int[m];
            int[] queue = new int[n];
            int qh = 0, qt = 0, rank = 0;
            for (int key : limited) {
                if (!prop.avail[key]) {
                    prop.avail[key] = true;
                    prop.rank[key] = rank++;
                    queue[qt++] = key;
                }
            }
            var ready = new IntBuf();
            for (int li = 0; li < m; li++) if (missing[li] == 0) ready.add(li);
            int readyAt = 0;
            while (readyAt < ready.size || qh < qt) {
                PlanningCancellation.check();
                while (readyAt < ready.size) {
                    int li = ready.data[readyAt++];
                    int p = patterns[li];
                    prop.fired[li] = true;
                    prop.order[prop.firedCount++] = li;
                    int out = index.out[p];
                    prop.produced[out] = true;
                    if (!prop.avail[out]) {
                        prop.avail[out] = true;
                        prop.rank[out] = rank++;
                        prop.first[out] = p;
                        queue[qt++] = out;
                    }
                    for (int j = index.sideStart[p]; j < index.sideStart[p + 1]; j++) {
                        int side = index.sideKey[j];
                        prop.side[side] = true;
                        if (!prop.avail[side]) {
                            prop.avail[side] = true;
                            prop.rank[side] = rank++;
                            prop.first[side] = p;
                            queue[qt++] = side;
                        }
                    }
                }
                if (qh < qt) {
                    int key = queue[qh++];
                    for (int j = head[key]; j < head[key + 1]; j++) if (--missing[consumer[j]] == 0) ready.add(consumer[j]);
                }
            }
            int t = index.ids.get(target);
            prop.targetAvailable = prop.avail[t];
            if (!prop.targetAvailable) return prop;

            // Stock free up to its limit.
            double[] free = new double[n];
            Arrays.fill(free, Double.POSITIVE_INFINITY);
            for (int key : limited) free[key] = 0;
            for (int k = 0; k < n; k++) if (prop.side[k]) free[k] = 0;
            if (keepChoices) {
                prop.freeArgmin = new int[n];
                Arrays.fill(prop.freeArgmin, -1);
            }
            relax(prop, 1, free, prop.freeArgmin);
            double scale = settle(prop, free);
            double freeBound = Double.isInfinite(scale) ? 0 : amount * free[t] / scale;
            if (availableStock) {
                // This phase only proposes a whole-inventory policy. The local region uses the
                // next, capped-stock propagation, so production prices are not needed yet.
                prop.execBound = Math.max(0L, (long) Math.ceil(freeBound * (1 - 1e-9) - 1e-9));
                prop.cost = free;
                prop.argmin = prop.freeArgmin;
                return prop;
            }
            // Stock priced by production, then charged for its limit.
            double[] priced = new double[n];
            Arrays.fill(priced, Double.POSITIVE_INFINITY);
            for (int k = 0; k < n; k++) if (prop.side[k] || prop.avail[k] && !prop.produced[k]) priced[k] = 0;
            int[] argmin = keepChoices ? new int[n] : null;
            if (argmin != null) Arrays.fill(argmin, -1);
            boolean settled = relax(prop, 1, priced, argmin);
            for (int k = 0; k < n; k++) if (prop.avail[k] && Double.isInfinite(priced[k])) {
                priced[k] = 0;
                settled = false;
            }
            // Without new seeds, a drained work queue already represents the fixed point.
            if (!settled) relax(prop, 1, priced, argmin);
            scale = settle(prop, priced);
            double charged = amount * priced[t];
            for (int key : limited) charged -= limits[key] * priced[key] * (1 + 1e-9);
            double bound = Math.max(freeBound, Double.isInfinite(scale) ? 0 : charged / scale);
            prop.execBound = Math.max(0L, (long) Math.ceil(bound * (1 - 1e-9) - 1e-9));
            prop.cost = priced;
            prop.argmin = argmin;

            return prop;
        }

        /** Stock units can reject a probe only after executions cannot improve enough. */
        private long stockBound(Prop prop) {
            if (prop.stockBoundComputed) return prop.stockBound;
            int n = index.keys.size(), t = index.ids.get(target);
            // Stock units: y <= 1 on limited keys, y(out) * out <= sum y(in) * in.
            double[] units = new double[n];
            Arrays.fill(units, Double.POSITIVE_INFINITY);
            for (int key : limited) units[key] = 1;
            for (int k = 0; k < n; k++) if (prop.side[k]) units[k] = 0;
            boolean exact = relax(prop, 0, units, null);
            for (int k = 0; k < n; k++) if (prop.avail[k] && Double.isInfinite(units[k])) units[k] = 0;
            for (int i = 0; i < prop.firedCount && exact; i++) {
                int p = prop.patterns[prop.order[i]];
                double consumed = 0;
                for (int j = index.useStart[p]; j < index.useStart[p + 1]; j++)
                    consumed += units[index.useKey[j]] * index.useAmount[j];
                exact = units[index.out[p]] * index.outAmount[p] <= consumed * (1 + 1e-9) + 1e-9;
            }
            prop.stockBound = exact ? Math.max(0L, (long) Math.ceil(amount * units[t] * (1 - 1e-9) - 1e-9)) : 0L;
            prop.stockBoundComputed = true;
            return prop.stockBound;
        }

        /** Label-correcting shortest costs over reached patterns; false when the work cap stops it. */
        private boolean relax(Prop prop, double work, double[] cost, int[] argmin) {
            int m = prop.patterns.length;
            int[] ring = new int[m + 1];
            boolean[] queued = new boolean[m];
            int head = 0, tail = 0, size = 0;
            for (int i = 0; i < prop.firedCount; i++) {
                int li = prop.order[i];
                ring[tail] = li;
                tail = tail + 1 == ring.length ? 0 : tail + 1;
                size++;
                queued[li] = true;
            }
            long budget = 8L * prop.firedCount + 1024;
            while (size > 0 && budget-- > 0) {
                if ((budget & 4095) == 0) PlanningCancellation.check();
                int li = ring[head];
                head = head + 1 == ring.length ? 0 : head + 1;
                size--;
                queued[li] = false;
                int p = prop.patterns[li];
                double value = work * index.executionCost[p];
                for (int j = index.useStart[p]; j < index.useStart[p + 1] && value < Double.POSITIVE_INFINITY; j++)
                    value += cost[index.useKey[j]] * index.useAmount[j];
                if (Double.isInfinite(value)) continue;
                value /= index.outAmount[p];
                int out = index.out[p];
                if (!(value < cost[out] * (1 - 1e-12))) continue;
                cost[out] = value;
                if (argmin != null) argmin[out] = p;
                for (int j = prop.consumerStart[out]; j < prop.consumerStart[out + 1]; j++) {
                    int next = prop.consumer[j];
                    if (!prop.fired[next] || queued[next]) continue;
                    queued[next] = true;
                    ring[tail] = next;
                    tail = tail + 1 == ring.length ? 0 : tail + 1;
                    size++;
                }
            }
            return size == 0;
        }

        /** Scale paid rows; a violated free row cannot be repaired by any positive scaling. */
        private double settle(Prop prop, double[] cost) {
            for (int k = 0; k < cost.length; k++) if (prop.avail[k] && Double.isInfinite(cost[k])) cost[k] = 0;
            double scale = 1;
            for (int i = 0; i < prop.firedCount; i++) {
                int p = prop.patterns[prop.order[i]];
                if (index.executionCost[p] == 0) {
                    // The explicit tag factory guarantees a consumed 1:1 edge. Comparing its
                    // two potentials directly avoids rounding a zero-RHS inequality into a
                    // positive allowance. Keep the policy, but abandon this bound if unsettled.
                    double output = cost[index.out[p]];
                    double member = cost[index.useKey[index.useStart[p]]];
                    if (!Double.isFinite(output) || !Double.isFinite(member) || output > member)
                        return Double.POSITIVE_INFINITY;
                    continue;
                }
                double excess = cost[index.out[p]] * index.outAmount[p];
                for (int j = index.useStart[p]; j < index.useStart[p + 1]; j++)
                    excess -= cost[index.useKey[j]] * index.useAmount[j];
                scale = Math.max(scale, excess * (1 + 1e-9));
            }
            return scale;
        }

        private long minimumGain(long executions) {
            return Math.max(1L, (executions * MIN_GAIN_PERCENT + 99) / 100);
        }

        String trace() {
            var text = new StringBuilder("{\"stop\":\"").append(stop).append('"');
            if (index != null) text.append(",\"keys\":").append(index.keys.size())
                    .append(",\"patterns\":").append(index.patterns.size());
            if (global != null) text.append(",\"fired\":").append(global.firedCount);
            text.append(",\"region\":").append(regionSize);
            text.append(",\"execBound\":").append(execBound).append(",\"stockBound\":").append(stockBound);
            text.append(",\"exec0\":").append(initialExecutions).append(",\"exec1\":").append(executionCount(best));
            text.append(",\"stock0\":").append(initialStock).append(",\"stock1\":").append(stockCount(best));
            text.append(",\"oracle\":").append(probes - mixedProbes).append(",\"mixed\":").append(mixedProbes)
                    .append(",\"improvements\":").append(improvements);
            text.append(",\"pruned\":").append(pruned);
            text.append(",\"indexMs\":").append(indexNanos / 1e6).append(",\"propMs\":").append(propagationNanos / 1e6);
            text.append(",\"oracleMs\":[");
            for (int i = 0; i < oracleNanos.size(); i++) text.append(i == 0 ? "" : ",").append(oracleNanos.get(i) / 1e6);
            text.append("],\"totalMs\":").append((System.nanoTime() - started) / 1e6).append('}');
            return text.toString();
        }
    }

    private static final class Prop {
        int[] patterns;
        int[] consumerStart;
        int[] consumer;
        boolean[] fired;
        int[] order;
        int firedCount;
        boolean[] avail;
        boolean[] side;
        boolean[] produced;
        int[] rank;
        int[] first;
        boolean targetAvailable;
        double[] cost;
        int[] argmin;
        /** First-round policy choices before stock is charged a scarcity price. */
        int[] freeArgmin;
        long execBound;
        long stockBound;
        boolean stockBoundComputed;
    }

    /** Integer view of the reachable graph, built once per request. */
    private static final class Index<K> {
        private static final int LINEAR_SLOT_LIMIT = 32;
        final ArrayList<K> keys = new ArrayList<>();
        final HashMap<K, Integer> ids = new HashMap<>();
        final ArrayList<CraftPattern<K>> patterns = new ArrayList<>();
        final HashMap<CraftPattern<K>, Integer> patternIds = new HashMap<>();
        boolean choices;
        int[] out;
        int[] executionCost;
        long[] outAmount;
        boolean[] stateful;
        /** Inputs that must be present to fire: all but host-owned reusable stock. */
        int[] needStart, needKey;
        /** Consumed (non-returned) inputs, merged per key. */
        int[] useStart, useKey;
        long[] useAmount;
        /** Byproducts other than the output key. */
        int[] sideStart, sideKey;
        int[] producerStart, producer;

        private int id(K key) {
            Integer id = ids.get(key);
            if (id == null) {
                id = keys.size();
                ids.put(key, id);
                keys.add(key);
            }
            return id;
        }

        static <K> Index<K> build(CraftGraph<K> graph, K target) {
            var index = new Index<K>();
            var sat = BigInteger.valueOf(Sat.SAT);
            var outs = new IntBuf();
            var executionCosts = new IntBuf();
            var outAmounts = new LongBuf();
            var states = new IntBuf();
            var needStart = new IntBuf();
            var needKey = new IntBuf();
            var useStart = new IntBuf();
            var useKey = new IntBuf();
            var useAmount = new LongBuf();
            var sideStart = new IntBuf();
            var sideKey = new IntBuf();
            needStart.add(0);
            useStart.add(0);
            sideStart.add(0);
            var pending = new IntBuf();
            var enqueued = new BitSet();
            pending.add(index.id(target));
            enqueued.set(0);
            for (int cursor = 0; cursor < pending.size; cursor++) {
                PlanningCancellation.check();
                int keyId = pending.data[cursor];
                var routes = graph.patternsFor(index.keys.get(keyId));
                index.choices |= routes.size() > 1;
                for (var pattern : routes) {
                    // Repeated registration does not add another firing variable. Keep the
                    // first identity in graph order, including distinct fuzzy expansions.
                    if (index.patternIds.containsKey(pattern)) continue;
                    if (pattern.exactOutputAmount().compareTo(sat) >= 0) return null;
                    long produced = pattern.exactOutputAmount().longValueExact();
                    int sideFrom = sideKey.size;
                    var wideSide = pattern.byproducts().size() > LINEAR_SLOT_LIMIT ? new HashSet<Integer>() : null;
                    int checkedSides = 0;
                    for (var side : pattern.byproducts()) {
                        if ((++checkedSides & 255) == 0) PlanningCancellation.check();
                        if (side.exactAmount().compareTo(sat) >= 0) return null;
                        long value = side.exactAmount().longValueExact();
                        int sideId = index.id(side.key());
                        if (sideId == keyId) {
                            produced = Sat.add(produced, value);
                            continue;
                        }
                        if (wideSide == null ? sideKey.indexOf(sideId, sideFrom) < 0 : wideSide.add(sideId))
                            sideKey.add(sideId);
                    }
                    int needFrom = needKey.size, useFrom = useKey.size;
                    // Short recipes keep allocation-free linear scans. Wide recipes retain
                    // first-seen array order without repeatedly scanning all previous slots.
                    var wideNeed = pattern.inputs().size() > LINEAR_SLOT_LIMIT ? new HashSet<Integer>() : null;
                    var wideUse = pattern.inputs().size() > LINEAR_SLOT_LIMIT ? new HashMap<Integer, Integer>() : null;
                    boolean stateful = false;
                    int checkedInputs = 0;
                    for (var input : pattern.inputs()) {
                        if ((++checkedInputs & 255) == 0) PlanningCancellation.check();
                        if (input.exactAmount().compareTo(sat) >= 0) return null;
                        int inputId = index.id(input.key());
                        // Side-only keys are indexed but are not traversed until used as an input.
                        if (!enqueued.get(inputId)) {
                            enqueued.set(inputId);
                            pending.add(inputId);
                        }
                        stateful |= input.returned() || input.remainder() != null || input.reusableStockSource() != null;
                        if (input.reusableStockSource() == null
                                && (wideNeed == null ? needKey.indexOf(inputId, needFrom) < 0 : wideNeed.add(inputId)))
                            needKey.add(inputId);
                        // CraftPattern already includes consumed-container remainders in byproducts.
                        if (!input.returned()) {
                            long value = input.exactAmount().longValueExact();
                            int at = wideUse == null ? useKey.indexOf(inputId, useFrom) : wideUse.getOrDefault(inputId, -1);
                            if (at >= 0) useAmount.data[at] = Sat.add(useAmount.data[at], value);
                            else {
                                if (wideUse != null) wideUse.put(inputId, useKey.size);
                                useKey.add(inputId);
                                useAmount.add(value);
                            }
                        }
                    }
                    index.patternIds.put(pattern, index.patterns.size());
                    index.patterns.add(pattern);
                    outs.add(keyId);
                    executionCosts.add(pattern.executionCost());
                    outAmounts.add(produced);
                    states.add(stateful ? 1 : 0);
                    needStart.add(needKey.size);
                    useStart.add(useKey.size);
                    sideStart.add(sideKey.size);
                }
            }
            index.out = outs.toArray();
            index.executionCost = executionCosts.toArray();
            index.outAmount = outAmounts.toArray();
            index.stateful = new boolean[index.out.length];
            for (int p = 0; p < index.out.length; p++) index.stateful[p] = states.data[p] != 0;
            index.needStart = needStart.toArray();
            index.needKey = needKey.toArray();
            index.useStart = useStart.toArray();
            index.useKey = useKey.toArray();
            index.useAmount = useAmount.toArray();
            index.sideStart = sideStart.toArray();
            index.sideKey = sideKey.toArray();
            int n = index.keys.size();
            index.producerStart = new int[n + 1];
            for (int key : index.out) index.producerStart[key + 1]++;
            for (int k = 0; k < n; k++) index.producerStart[k + 1] += index.producerStart[k];
            index.producer = new int[index.out.length];
            int[] fill = Arrays.copyOf(index.producerStart, n);
            for (int p = 0; p < index.out.length; p++) index.producer[fill[index.out[p]]++] = p;
            return index;
        }
    }

    private static final class IntBuf {
        int[] data = new int[16];
        int size;

        void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = value;
        }

        int indexOf(int value, int from) {
            for (int i = from; i < size; i++) if (data[i] == value) return i;
            return -1;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private static final class LongBuf {
        long[] data = new long[16];
        int size;

        void add(long value) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = value;
        }

        long[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    /**
     * Fewer executions may use different ordinary stock from the same inventory snapshot.
     * Equal executions require no greater material draws and less stock or net resource loss.
     * Host-private reusable stock and stateful firings retain the incumbent's constraints.
     */
    static <K> boolean improves(CraftPlan<K> best, CraftPlan<K> candidate) {
        if (!candidate.supported() || !candidate.feasible() || !candidate.missing().isEmpty()
                || !noMore(candidate.usedReusableStock(), best.usedReusableStock())
                || !statefulFirings(best).equals(statefulFirings(candidate))) return false;
        // Saving a few items is not worth hundreds of extra machine operations.
        int executions = executions(candidate).compareTo(executions(best));
        if (executions != 0) return executions < 0;
        if (!noMore(candidate.usedStock(), best.usedStock())) return false;
        var oldLoss = netLoss(best);
        var newLoss = netLoss(candidate);
        for (var entry : newLoss.entrySet())
            if (entry.getValue().compareTo(oldLoss.getOrDefault(entry.getKey(), BigInteger.ZERO)) > 0) return false;
        boolean reduced = MissingRefinement.strictlyDominates(candidate.usedStock(), best.usedStock())
                || MissingRefinement.strictlyDominates(candidate.usedReusableStock(), best.usedReusableStock());
        for (var entry : oldLoss.entrySet())
            reduced |= newLoss.getOrDefault(entry.getKey(), BigInteger.ZERO).compareTo(entry.getValue()) < 0;
        return reduced;
    }

    private static <K> boolean noMore(Map<K, Long> candidate, Map<K, Long> best) {
        for (var entry : candidate.entrySet())
            if (entry.getValue() < 0 || entry.getValue() > best.getOrDefault(entry.getKey(), 0L)) return false;
        return true;
    }

    private static <K> Map<CraftPattern<K>, Long> statefulFirings(CraftPlan<K> plan) {
        var result = new HashMap<CraftPattern<K>, Long>();
        plan.firings().forEach((pattern, count) -> {
            if (stateful(pattern)) result.put(pattern, count);
        });
        return result;
    }

    /** Catalysts, seeds, returned containers and durability carriers. */
    private static boolean stateful(CraftPattern<?> pattern) {
        return pattern.inputs().stream().anyMatch(i -> i.returned() || i.remainder() != null
                || i.reusableStockSource() != null);
    }

    private static <K> Map<K, BigInteger> netLoss(CraftPlan<K> plan) {
        var loss = new HashMap<K, BigInteger>();
        plan.firings().forEach((pattern, count) -> {
            PlanningCancellation.check();
            var times = BigInteger.valueOf(count);
            for (var input : pattern.inputs()) if (!input.returned())
                loss.merge(input.key(), input.exactAmount().multiply(times), BigInteger::add);
            loss.merge(pattern.output(), pattern.exactOutputAmount().multiply(times).negate(), BigInteger::add);
            for (var side : pattern.byproducts())
                loss.merge(side.key(), side.exactAmount().multiply(times).negate(), BigInteger::add);
        });
        loss.entrySet().removeIf(e -> e.getValue().signum() <= 0);
        return loss;
    }

    private static BigInteger executions(CraftPlan<?> plan) {
        return plan.executionCount();
    }

    private static long executionCount(CraftPlan<?> plan) {
        long total = 0;
        for (var entry : plan.firings().entrySet())
            if (entry.getKey().executionCost() != 0) total = Sat.add(total, entry.getValue());
        return total;
    }

    private static long stockCount(CraftPlan<?> plan) {
        long total = 0;
        for (long count : plan.usedStock().values()) total = Sat.add(total, count);
        return total;
    }

    private static <K> List<K> dagOrder(Map<K, List<CraftPattern<K>>> patterns) {
        var edges = new LinkedHashMap<K, LinkedHashSet<K>>();
        var degree = new HashMap<K, Integer>();
        patterns.forEach((key, routes) -> {
            PlanningCancellation.check();
            var inputs = new LinkedHashSet<K>();
            for (var pattern : routes) for (var input : pattern.inputs()) inputs.add(input.key());
            edges.put(key, inputs);
            for (K input : inputs) degree.merge(input, 1, Integer::sum);
        });
        var pending = new ArrayDeque<K>();
        for (K key : patterns.keySet()) if (degree.getOrDefault(key, 0) == 0) pending.addLast(key);
        var result = new ArrayList<K>();
        while (!pending.isEmpty()) {
            PlanningCancellation.check();
            K key = pending.removeFirst();
            result.add(key);
            for (K input : edges.get(key)) if (degree.merge(input, -1, Integer::sum) == 0) pending.addLast(input);
        }
        return result.size() == patterns.size() ? result : List.of();
    }
}
