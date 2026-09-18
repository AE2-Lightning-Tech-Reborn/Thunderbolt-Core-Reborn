package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A bounded witness search for shallow ordinary alternatives sharing several raw materials. */
final class WholeBatchAllocation {
    private static final int MAX_GROUPS = 128;
    private static final int MAX_ROUTES = 16;
    private static final int MAX_CELLS = 16_384;
    private static final long MAX_WORK = 1_500_000;

    private WholeBatchAllocation() {}

    // Each output uses one route for its entire batch. Failure is NOT an infeasibility proof:
    // a valid allocation may split a batch between routes. Leave that to the existing solver.
    static <K> UnitMaterialFlow.Result<K> trySolve(CraftGraph<K> graph, List<K> items,
            List<CraftPattern<K>> patterns, Map<K, Long> demand, Map<K, Long> supply,
            BoundedIntegerLinearSolver.WorkBudget budget) {
        var groups = new LinkedHashMap<K, List<CraftPattern<K>>>();
        for (var p : patterns) {
            PlanningCancellation.check();
            if (!p.byproducts().isEmpty() || p.outputAmount() <= 0
                    || !p.exactOutputAmount().equals(BigInteger.valueOf(p.outputAmount()))) return null;
            groups.computeIfAbsent(p.output(), ignored -> new ArrayList<>()).add(p);
        }
        if (groups.isEmpty() || groups.size() > MAX_GROUPS) return null;
        var raw = new ArrayList<K>();
        for (K key : items) if (!groups.containsKey(key)) {
            if (!graph.patternsFor(key).isEmpty()) return null;
            raw.add(key);
        }
        if (raw.isEmpty() || (long) raw.size() * patterns.size() > MAX_CELLS) return null;
        var indices = new LinkedHashMap<K, Integer>();
        for (int r = 0; r < raw.size(); r++) indices.put(raw.get(r), r);
        var routes = new ArrayList<List<CraftPattern<K>>>();
        var counts = new ArrayList<Long>();
        var exact = new ArrayList<BigInteger[][]>();
        BigInteger[] gcd = new BigInteger[raw.size()];
        Arrays.fill(gcd, BigInteger.ZERO);
        for (var entry : groups.entrySet()) {
            var alternatives = entry.getValue();
            if (alternatives.size() > MAX_ROUTES) return null;
            long batch = alternatives.getFirst().outputAmount();
            if (alternatives.stream().anyMatch(p -> p.outputAmount() != batch)) return null;
            K output = entry.getKey();
            BigInteger deficit = BigInteger.valueOf(demand.getOrDefault(output, 0L))
                    .subtract(BigInteger.valueOf(graph.stock(output)))
                    .subtract(BigInteger.valueOf(supply.getOrDefault(output, 0L)));
            long count = deficit.signum() <= 0 ? 0 : deficit.add(BigInteger.valueOf(batch-1))
                    .divide(BigInteger.valueOf(batch)).longValueExact();
            BigInteger[][] use = new BigInteger[alternatives.size()][raw.size()];
            for (int a = 0; a < alternatives.size(); a++) {
                Arrays.fill(use[a], BigInteger.ZERO);
                for (var input : alternatives.get(a).inputs()) {
                    Integer r = indices.get(input.key());
                    if (r == null || input.returned() || input.remainder() != null
                            || input.reusableStockSource() != null || input.amount() <= 0
                            || !input.exactAmount().equals(BigInteger.valueOf(input.amount()))) return null;
                    use[a][r] = use[a][r].add(input.exactAmount().multiply(BigInteger.valueOf(count)));
                }
                for (int r = 0; r < raw.size(); r++) gcd[r] = gcd[r].gcd(use[a][r]);
            }
            routes.add(alternatives);
            counts.add(count);
            exact.add(use);
        }
        if (!budget.tryConsume((long) patterns.size() * raw.size())) return null;
        int size = routes.size(), resources = raw.size();
        long[][][] use = new long[size][][];
        long[] cap = new long[resources];
        BigInteger limit = BigInteger.valueOf(Sat.SAT / Math.max(1, size * resources));
        for (int r = 0; r < resources; r++) {
            BigInteger available = BigInteger.valueOf(graph.stock(raw.get(r)))
                    .add(BigInteger.valueOf(supply.getOrDefault(raw.get(r), 0L)))
                    .subtract(BigInteger.valueOf(demand.getOrDefault(raw.get(r), 0L)));
            if (available.signum() < 0) return null;
            if (gcd[r].signum() == 0) gcd[r] = BigInteger.ONE;
            // Rounding down is exact for this witness domain: every option uses a multiple.
            cap[r] = available.divide(gcd[r]).min(BigInteger.valueOf(Sat.SAT)).longValueExact();
        }
        long[] maxSum = new long[resources];
        long total = 0;
        boolean constantTotal = true;
        for (int g = 0; g < size; g++) {
            use[g] = new long[routes.get(g).size()][resources];
            long[] max = new long[resources];
            long firstTotal = -1;
            for (int a = 0; a < use[g].length; a++) {
                long sum = 0;
                for (int r = 0; r < resources; r++) {
                    BigInteger value = exact.get(g)[a][r].divide(gcd[r]);
                    if (value.compareTo(limit) > 0) return null;
                    use[g][a][r] = value.longValueExact();
                    max[r] = Math.max(max[r], use[g][a][r]);
                    sum += use[g][a][r];
                }
                if (firstTotal < 0) firstTotal = sum;
                else constantTotal &= firstTotal == sum;
            }
            total += firstTotal;
            for (int r = 0; r < resources; r++) maxSum[r] += max[r];
        }
        long capacityTotal = 0;
        for (int r = 0; r < resources; r++) {
            cap[r] = Math.min(cap[r], maxSum[r]);
            capacityTotal += cap[r];
        }
        // Tight conservation gives lower bounds as well as upper bounds on each resource.
        var search = new Search(use, cap, constantTotal && capacityTotal == total, budget);
        int[] domains = new int[size];
        for (int g = 0; g < size; g++) domains[g] = (1 << use[g].length)-1;
        int[] solution = search.solve(domains);
        if (solution == null) return null;
        // Check the certificate again against the original unscaled amounts. Search ordering
        // uses floating point only as a heuristic; acceptance always uses exact arithmetic.
        BigInteger[] consumed = new BigInteger[resources];
        Arrays.fill(consumed, BigInteger.ZERO);
        var firings = new IdentityHashMap<CraftPattern<K>, Long>();
        for (int g = 0; g < size; g++) {
            int a = Integer.numberOfTrailingZeros(solution[g]);
            if (counts.get(g) > 0) firings.put(routes.get(g).get(a), counts.get(g));
            for (int r = 0; r < resources; r++) consumed[r] = consumed[r].add(exact.get(g)[a][r]);
        }
        for (int r = 0; r < resources; r++) {
            BigInteger available = BigInteger.valueOf(graph.stock(raw.get(r)))
                    .add(BigInteger.valueOf(supply.getOrDefault(raw.get(r), 0L)))
                    .subtract(BigInteger.valueOf(demand.getOrDefault(raw.get(r), 0L)));
            if (consumed[r].compareTo(available) > 0) return null;
        }
        return new UnitMaterialFlow.Result<>(BoundedIntegerLinearSolver.Status.SOLVED, Map.copyOf(firings), null);
    }

    private static final class Search {
        final long[][][] use;
        final long[] cap;
        final boolean tight;
        final BoundedIntegerLinearSolver.WorkBudget budget;
        long remaining = MAX_WORK;
        boolean stopped;

        Search(long[][][] use, long[] cap, boolean tight, BoundedIntegerLinearSolver.WorkBudget budget) {
            this.use = use; this.cap = cap; this.tight = tight; this.budget = budget;
        }

        boolean charge(long work) {
            PlanningCancellation.check();
            if (stopped || work > remaining) { stopped = true; return false; }
            remaining -= work;
            if (!budget.tryConsume(work)) { stopped = true; return false; }
            return true;
        }

        int[] solve(int[] domains) {
            if (!charge(domains.length)) return null;
            int size = use.length, resources = cap.length;
            long[][] min = new long[size][resources], max = new long[size][resources];
            long[] lo = new long[resources], hi = new long[resources];
            boolean changed;
            do {
                Arrays.fill(lo, 0); Arrays.fill(hi, 0);
                for (int g = 0; g < size; g++) {
                    if (!charge((long) resources * (1 + Integer.bitCount(domains[g])))) return null;
                    Arrays.fill(min[g], Long.MAX_VALUE); Arrays.fill(max[g], 0);
                    for (int bits = domains[g]; bits != 0; bits &= bits-1) {
                        long[] option = use[g][Integer.numberOfTrailingZeros(bits)];
                        for (int r = 0; r < resources; r++) {
                            min[g][r] = Math.min(min[g][r], option[r]);
                            max[g][r] = Math.max(max[g][r], option[r]);
                        }
                    }
                    for (int r = 0; r < resources; r++) { lo[r] += min[g][r]; hi[r] += max[g][r]; }
                }
                for (int r = 0; r < resources; r++) if (lo[r] > cap[r] || tight && hi[r] < cap[r]) return null;
                changed = false;
                for (int g = 0; g < size; g++) {
                    if (Integer.bitCount(domains[g]) == 1) continue;
                    if (!charge((long) resources * Integer.bitCount(domains[g]))) return null;
                    for (int bits = domains[g]; bits != 0; bits &= bits-1) {
                        int a = Integer.numberOfTrailingZeros(bits);
                        for (int r = 0; r < resources; r++) {
                            if (lo[r]-min[g][r]+use[g][a][r] > cap[r]
                                    || tight && hi[r]-max[g][r]+use[g][a][r] < cap[r]) {
                                domains[g] &= ~(1 << a); changed = true; break;
                            }
                        }
                    }
                    if (domains[g] == 0) return null;
                }
            } while (changed);
            int branch = -1, width = Integer.MAX_VALUE;
            long bestSlack = Long.MAX_VALUE;
            double bestPressure = -1;
            for (int g = 0; g < size; g++) {
                int w = Integer.bitCount(domains[g]);
                if (w <= 1) continue;
                long slack = Long.MAX_VALUE;
                double pressure = 0;
                for (int r = 0; r < resources; r++) if (min[g][r] != max[g][r]) {
                    long room = tight ? Math.min(cap[r]-lo[r], hi[r]-cap[r]) : cap[r]-lo[r];
                    slack = Math.min(slack, room);
                    pressure += (double) (max[g][r]-min[g][r]) / (room+1.0);
                }
                if (w < width || w == width && (slack < bestSlack || slack == bestSlack && pressure > bestPressure)) {
                    branch = g; width = w; bestSlack = slack; bestPressure = pressure;
                }
            }
            if (branch < 0) return domains;
            if (!charge((long) resources * (size + width))) return null;
            int choices = domains[branch];
            while (choices != 0 && !stopped) {
                int selected = -1;
                double best = Double.NEGATIVE_INFINITY;
                for (int bits = choices; bits != 0; bits &= bits-1) {
                    int a = Integer.numberOfTrailingZeros(bits);
                    double score = 0;
                    for (int r = 0; r < resources; r++) score += use[branch][a][r]
                            * ((double) cap[r] - lo[r] - ((double) hi[r] - cap[r])) / Math.max(1L, hi[r]-lo[r]);
                    if (selected < 0 || score > best) { selected = a; best = score; }
                }
                choices &= ~(1 << selected);
                int[] child = domains.clone(); child[branch] = 1 << selected;
                int[] result = solve(child);
                if (result != null) return result;
            }
            return null;
        }
    }
}
