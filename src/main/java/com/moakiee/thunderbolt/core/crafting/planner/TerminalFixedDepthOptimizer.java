package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/**
 * Searches lower firing counts using one, two, or three original terminal routes.
 *
 * <p>This optional supplement accepts only 17..64 ordinary routes over exactly two positive raw
 * resources, no target stock, and quantities bounded by 256. It spends at most 4096 work units
 * from the caller's remaining allowance and reserves at most one existing probe. Every proposal
 * still needs original-graph replay and the caller's objective check. Declining or exhausting this
 * incomplete neighborhood leaves the established incumbent intact.
 */
final class TerminalFixedDepthOptimizer {
    private static final int MIN_ROUTES = 17;
    private static final int MAX_ROUTES = 64;
    private static final int MAX_AMOUNT = 256;
    private static final int MAX_EXECUTIONS = 32;
    private static final int LOCAL_SPEND_CEILING = 4096;
    private static final BigInteger BIG_MAX_AMOUNT = BigInteger.valueOf(MAX_AMOUNT);

    private TerminalFixedDepthOptimizer() {}

    /** Null is a declined or unverified proposal, never an infeasibility claim. */
    static <K> CraftPlan<K> tryImprove(CraftGraph<K> graph, K target, long amount,
            CraftPlan<K> incumbent, IntPredicate spendWork, BooleanSupplier reserveProbe) {
        var budget = new Budget(spendWork);
        try {
            PlanningCancellation.check();
            budget.pay(1);
            if (!incumbent.supported() || !incumbent.feasible() || !incumbent.missing().isEmpty()
                    || !incumbent.usedReusableStock().isEmpty() || amount <= 0 || amount > MAX_AMOUNT
                    || graph.exactStock(target).signum() != 0 || graph.stock(target) != 0) return null;
            int executions = 0;
            for (var entry : incumbent.firings().entrySet()) {
                budget.pay(1);
                long count = entry.getValue();
                if (entry.getKey().executionCost() != 1
                        || count <= 0 || count > MAX_EXECUTIONS - executions) return null;
                executions += (int) count;
            }
            if (executions < 2) return null;

            // An identity/output scan can decline an already optimal incumbent before model work.
            budget.pay(MAX_ROUTES);
            @SuppressWarnings("unchecked")
            Route<K>[] routes = (Route<K>[]) new Route<?>[MAX_ROUTES];
            var identities = new IdentityHashMap<CraftPattern<K>, Route<K>>();
            int routeCount = 0;
            int maxOutput = 0;
            for (var pattern : graph.patternsFor(target)) {
                budget.pay(1);
                if (identities.containsKey(pattern)) continue;
                if (routeCount >= MAX_ROUTES || !target.equals(pattern.output())
                        || pattern.executionCost() != 1
                        || !boundedPositive(pattern.exactOutputAmount())
                        || !pattern.exactOutputAmount().equals(BigInteger.valueOf(pattern.outputAmount()))) return null;
                budget.pay(1); // New identity, route record, and its array entry.
                var route = new Route<>(pattern, pattern.exactOutputAmount().intValueExact());
                routes[routeCount++] = route;
                identities.put(pattern, route);
                maxOutput = Math.max(maxOutput, route.output);
            }
            if (routeCount < MIN_ROUTES || executions <= ceilPositive((int) amount, maxOutput)) return null;

            int incumbentSlots = 0;
            for (var pattern : incumbent.firings().keySet()) {
                budget.pay(1);
                if (!identities.containsKey(pattern)) return null;
                incumbentSlots += pattern.inputs().size();
            }
            K firstKey = null;
            K secondKey = null;
            for (int i = 0; i < routeCount; i++) {
                budget.pay(1);
                var route = routes[i];
                var pattern = route.pattern;
                if (pattern.inputs().isEmpty() || !pattern.byproducts().isEmpty()
                        || !pattern.executionSlots().isEmpty()) return null;
                int totalInputs = 0;
                for (var input : pattern.inputs()) {
                    budget.pay(1);
                    if (input.returned() || input.remainder() != null || input.reusableStockSource() != null
                            || target.equals(input.key()) || !graph.patternsFor(input.key()).isEmpty()
                            || !boundedPositive(input.exactAmount())
                            || !input.exactAmount().equals(BigInteger.valueOf(input.amount()))) return null;
                    int value = input.exactAmount().intValueExact();
                    totalInputs += value;
                    if (totalInputs > MAX_AMOUNT) return null;
                    if (firstKey == null) firstKey = input.key();
                    if (firstKey.equals(input.key())) {
                        route.first += value;
                    } else {
                        if (secondKey == null) secondKey = input.key();
                        if (!secondKey.equals(input.key())) return null;
                        route.second += value;
                    }
                }
                if (route.first <= 0 || route.second <= 0) return null;
            }
            if (firstKey == null || secondKey == null) return null;
            // Check both original resources before any Pareto reduction, including deleted routes.
            budget.pay(2);
            var firstStock = graph.exactStock(firstKey);
            var secondStock = graph.exactStock(secondKey);
            if (!boundedPositive(firstStock) || !boundedPositive(secondStock)
                    || firstStock.add(secondStock).compareTo(BIG_MAX_AMOUNT) > 0
                    || !firstStock.equals(BigInteger.valueOf(graph.stock(firstKey)))
                    || !secondStock.equals(BigInteger.valueOf(graph.stock(secondKey)))) return null;

            // The validated coordinates fit disjoint, nonnegative bit fields. Preparing this
            // rank once replaces up to three coordinate comparisons at each merge decision.
            for (int i = 0; i < routeCount; i++) {
                budget.pay(5); // One subtraction, two shifts, and two bitwise unions.
                var route = routes[i];
                route.sortKey = ((MAX_AMOUNT - route.output) << 18) | (route.first << 9) | route.second;
            }
            budget.pay(routeCount);
            @SuppressWarnings("unchecked")
            Route<K>[] scratch = (Route<K>[]) new Route<?>[routeCount];
            var sorted = stableSort(routes, scratch, routeCount, budget);
            var envelope = sorted == routes ? scratch : routes;
            var frontier = pareto(sorted, envelope, routeCount, budget);
            return search(graph, target, (int) amount, incumbent, executions, incumbentSlots,
                    frontier, firstStock.intValueExact(), secondStock.intValueExact(), budget, reserveProbe);
        } catch (Stopped ignored) {
            return null;
        }
    }

    /** Every stable-merge comparison and every reference copy/move is charged separately. */
    private static <K> Route<K>[] stableSort(Route<K>[] source, Route<K>[] destination,
            int count, Budget budget) {
        for (int width = 1; width < count; width *= 2) {
            budget.pay(1);
            for (int start = 0; start < count; start += 2 * width) {
                budget.pay(1);
                int middle = Math.min(count, start + width);
                int end = Math.min(count, start + 2 * width);
                int left = start, right = middle;
                for (int to = start; to < end; to++) {
                    int selected;
                    if (left == middle) selected = right++;
                    else if (right == end) selected = left++;
                    else {
                        budget.pay(1); // One prepared rank; equal ranks keep source order.
                        selected = compare(source[left], source[right]) <= 0 ? left++ : right++;
                    }
                    budget.pay(1);
                    destination[to] = source[selected];
                }
            }
            var previous = source;
            source = destination;
            destination = previous;
        }
        return source;
    }

    private static int compare(Route<?> left, Route<?> right) {
        return Integer.compare(left.sortKey, right.sortKey);
    }

    /**
     * Outputs are descending. The resource envelope stores prefix minima of the second resource
     * over increasing first resource; removing an envelope entry never removes a retained route.
     */
    private static <K> Frontier<K> pareto(Route<K>[] sorted, Route<K>[] envelope,
            int count, Budget budget) {
        // The retained prefix never overtakes the read cursor. The other merge buffer is no
        // longer live; only its initialized envelope prefix is read, so it needs no clearing.
        var retained = sorted;
        int retainedCount = 0, envelopeCount = 0;
        for (int i = 0; i < count; i++) {
            budget.pay(1);
            var route = sorted[i];
            int low = 0, high = envelopeCount;
            while (low < high) {
                budget.pay(1);
                int middle = (low + high) >>> 1;
                if (envelope[middle].first <= route.first) low = middle + 1;
                else high = middle;
            }
            budget.pay(1);
            if (low > 0 && envelope[low - 1].second <= route.second) continue;
            budget.pay(1);
            retained[retainedCount++] = route;
            int position = low;
            if (low > 0 && envelope[low - 1].first == route.first) position--;
            int end = position;
            while (end < envelopeCount) {
                budget.pay(1);
                if (envelope[end].second < route.second) break;
                end++;
            }
            int removed = end - position;
            if (removed == 0) {
                for (int to = envelopeCount; to > position; to--) {
                    budget.pay(1);
                    envelope[to] = envelope[to - 1];
                }
            } else {
                for (int from = end; from < envelopeCount; from++) {
                    budget.pay(1);
                    envelope[from - removed + 1] = envelope[from];
                }
            }
            budget.pay(1);
            envelope[position] = route;
            envelopeCount += 1 - removed;
        }
        return new Frontier<>(retained, retainedCount);
    }

    private static <K> CraftPlan<K> search(CraftGraph<K> graph, K target, int amount,
            CraftPlan<K> incumbent, int executions, int incumbentSlots, Frontier<K> frontier,
            int firstStock, int secondStock, Budget budget, BooleanSupplier reserveProbe) {
        if (frontier.count == 0) return null;
        var routes = frontier.routes;
        int lowerBound = ceilPositive(amount, routes[0].output);
        for (int depth = lowerBound; depth < executions; depth++) {
            budget.pay(1);
            for (int i = 0; i < frontier.count; i++) {
                budget.pay(4);
                var route = routes[i];
                if (depth * route.output >= amount && (depth - 1) * route.output + 1 <= amount
                        && depth * route.first <= firstStock && depth * route.second <= secondStock)
                    return certify(graph, target, amount, incumbent, executions, incumbentSlots,
                            route, depth, null, 0, null, 0, budget, reserveProbe);
            }
            if (depth >= 2) for (int i = 0; i < frontier.count; i++) {
                budget.pay(1);
                for (int j = i + 1; j < frontier.count; j++) {
                    budget.pay(1); // Visit one pair before reading its coordinates.
                    var first = routes[i];
                    var second = routes[j];
                    if ((depth - 1) * first.output + second.output < amount) continue;
                    int x = interval(first, second, depth, amount, firstStock, secondStock,
                            amount + first.output + second.output - 2, budget);
                    if (x > 0) return certify(graph, target, amount, incumbent, executions, incumbentSlots,
                            first, x, second, depth - x, null, 0, budget, reserveProbe);
                }
            }
            if (depth < 3) continue;
            for (int i = 0; i < frontier.count; i++) {
                budget.pay(1);
                for (int j = i + 1; j < frontier.count; j++) {
                    budget.pay(1);
                    for (int k = j + 1; k < frontier.count; k++) {
                        budget.pay(1); // Visit one triple before reading its coordinates.
                        var first = routes[i];
                        var second = routes[j];
                        var third = routes[k];
                        if ((depth - 2) * first.output + second.output + third.output < amount) continue;
                        for (int x = 1; x <= depth - 2; x++) {
                            budget.pay(3);
                            int produced = x * first.output;
                            int remainingFirst = firstStock - x * first.first;
                            int remainingSecond = secondStock - x * first.second;
                            int remainingCount = depth - x;
                            if (remainingFirst < Math.min(second.first, third.first) * remainingCount
                                    || remainingSecond < Math.min(second.second, third.second) * remainingCount) continue;
                            int y = interval(second, third, remainingCount, amount - produced,
                                    remainingFirst, remainingSecond,
                                    amount + first.output + second.output + third.output - 3 - produced, budget);
                            if (y > 0) return certify(graph, target, amount, incumbent, executions, incumbentSlots,
                                    first, x, second, y, third, remainingCount - y, budget, reserveProbe);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Positive x and y=n-x, with exact production, primary-demand, and two stock constraints. */
    private static int interval(Route<?> first, Route<?> second, int count, int demand,
            int firstStock, int secondStock, int primaryUpper, Budget budget) {
        var interval = new Interval(1, count - 1);
        if (!interval.atMost(second.output - first.output, count * second.output - demand, budget)
                || !interval.atMost(first.output - second.output, primaryUpper - count * second.output, budget)
                || !interval.atMost(first.first - second.first, firstStock - count * second.first, budget)
                || !interval.atMost(first.second - second.second, secondStock - count * second.second, budget)) return 0;
        return interval.low;
    }

    private static <K> CraftPlan<K> certify(CraftGraph<K> graph, K target, int amount,
            CraftPlan<K> incumbent, int incumbentExecutions, int incumbentSlots,
            Route<K> first, int x, Route<K> second, int y, Route<K> third, int z,
            Budget budget, BooleanSupplier reserveProbe) {
        budget.pay(12);
        if (x <= 0 || y < 0 || z < 0 || x + y + z >= incumbentExecutions) return null;
        var counts = new IdentityHashMap<CraftPattern<K>, Long>();
        budget.pay(1);
        counts.put(first.pattern, (long) x);
        int slots = first.pattern.inputs().size();
        if (second != null) {
            if (y <= 0) return null;
            budget.pay(1);
            counts.put(second.pattern, (long) y);
            slots += second.pattern.inputs().size();
        }
        if (third != null) {
            if (z <= 0) return null;
            budget.pay(1);
            counts.put(third.pattern, (long) z);
            slots += third.pattern.inputs().size();
        }
        // Replay is independent. Both this helper and its FCO caller check the original objective.
        int replayWork = counts.size() + 2 * slots;
        budget.pay(replayWork);
        int objectiveWork = 2 * (4 + 2 * (incumbent.firings().size() + counts.size()) + incumbentSlots + slots);
        budget.pay(objectiveWork);
        PlanningCancellation.check();
        if (!reserveProbe.getAsBoolean()) return null;
        PlanningCancellation.check();
        var proposal = MaterialDagReplay.tryPlan(graph, counts, target, amount);
        return proposal != null && FeasibleConsumptionOptimizer.improves(incumbent, proposal) ? proposal : null;
    }

    private static boolean boundedPositive(BigInteger amount) {
        return amount.signum() > 0 && amount.compareTo(BIG_MAX_AMOUNT) <= 0;
    }

    private static int ceilPositive(int numerator, int denominator) {
        return 1 + (numerator - 1) / denominator;
    }

    private static final class Route<K> {
        final CraftPattern<K> pattern;
        final int output;
        int first;
        int second;
        int sortKey;

        Route(CraftPattern<K> pattern, int output) {
            this.pattern = pattern;
            this.output = output;
        }
    }

    private record Frontier<K>(Route<K>[] routes, int count) {}

    private static final class Interval {
        int low;
        int high;

        Interval(int low, int high) { this.low = low; this.high = high; }

        boolean atMost(int slope, int right, Budget budget) {
            budget.pay(1);
            if (slope > 0) high = Math.min(high, Math.floorDiv(right, slope));
            else if (slope < 0) low = Math.max(low, -Math.floorDiv(-right, slope));
            else if (right < 0) return false;
            return low <= high;
        }
    }

    private static final class Budget {
        final IntPredicate spendWork;
        int remaining = LOCAL_SPEND_CEILING;
        Budget(IntPredicate spendWork) { this.spendWork = spendWork; }

        void pay(int work) {
            PlanningCancellation.check();
            if (work <= 0) throw new IllegalArgumentException("work must be positive");
            // Local refusal must not manufacture a refusal from the caller's shared budget.
            if (work > remaining) {
                throw Stopped.INSTANCE;
            }
            if (!spendWork.test(work)) {
                throw Stopped.INSTANCE;
            }
            remaining -= work;
        }
    }

    private static final class Stopped extends RuntimeException {
        static final Stopped INSTANCE = new Stopped();
        private Stopped() { super(null, null, false, false); }
    }
}
