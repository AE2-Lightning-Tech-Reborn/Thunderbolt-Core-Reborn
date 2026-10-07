package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Prefix constraints for a finite family of real execution traces, sharing the master's resources. */
final class CpSatExecutionBlocks {
    private CpSatExecutionBlocks() { }

    /** Wire row: group, original firing vector, required marking, net change. */
    static IntVar[] add(CpModel model, IntVar[] firings, IntVar[] used, IntVar[] missing,
                        SparseLongMatrix produced, int[] outputs, int[] groups, long[] firingBounds,
                        long[][] blocks, int stages, long[] stocks) {
        if (blocks.length == 0) return new IntVar[0];
        if (stages < 1 || stages > 8 || blocks.length > 96) throw new IllegalArgumentException("block shape");
        int recipes = firings.length, items = groups.length, size = blocks.length;
        var byGroup = new LinkedHashMap<Integer, ArrayList<Integer>>();
        long maximumCoefficient = 1;
        for (int b = 0; b < size; b++) {
            long[] row = blocks[b];
            if (row.length != 1 + recipes + 2 * items || row[0] < 0 || row[0] > Integer.MAX_VALUE)
                throw new IllegalArgumentException("block row");
            int group = (int) row[0];
            byGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(b);
            boolean nonempty = false;
            for (int r = 0; r < recipes; r++) {
                if (row[1+r] < 0 || (row[1+r] > 0 && groups[outputs[r]] != group))
                    throw new IllegalArgumentException("block crosses execution ranks");
                nonempty |= row[1+r] > 0;
                maximumCoefficient = Math.max(maximumCoefficient, row[1+r]);
            }
            if (!nonempty) throw new IllegalArgumentException("empty block");
            for (int i = 0; i < items; i++) {
                long h = row[1+recipes+i], delta = row[1+recipes+items+i];
                if (h < 0 || delta == Long.MIN_VALUE || h < Math.max(-delta, 0))
                    throw new IllegalArgumentException("invalid block prefix");
                maximumCoefficient = Math.max(maximumCoefficient, Math.abs(delta));
            }
        }
        // These bounds apply only to this optional witness family. Its failure never proves the
        // unrestricted master infeasible. Reserve ample int64 row/domain space for original vars.
        long safeUpper = (Long.MAX_VALUE / 16L) / (size * (long) stages) / maximumCoefficient;
        var repeats = new IntVar[stages * size];
        var active = new BoolVar[repeats.length];
        long[] repeatBounds = new long[size];
        for (int stage = 0; stage < stages; stage++) for (int b = 0; b < size; b++) {
            long upper = safeUpper;
            for (int r = 0; r < recipes; r++) if (blocks[b][1+r] > 0)
                upper = Math.min(upper, firingBounds[r] / blocks[b][1+r]);
            int index = stage * size + b;
            repeatBounds[b] = upper;
            repeats[index] = model.newIntVar(0, upper, "block_n_" + stage + "_" + b);
            active[index] = model.newBoolVar("block_active_" + stage + "_" + b);
            model.addGreaterOrEqual(repeats[index], 1).onlyEnforceIf(active[index]);
            model.addEquality(repeats[index], 0).onlyEnforceIf(active[index].not());
        }
        for (var entry : byGroup.entrySet()) {
            int group = entry.getKey();
            var members = entry.getValue();
            for (int r = 0; r < recipes; r++) {
                if (groups[outputs[r]] != group) continue;
                var counts = LinearExpr.newBuilder();
                for (int stage = 0; stage < stages; stage++) for (int b : members)
                    if (blocks[b][1+r] > 0) counts.addTerm(repeats[stage*size+b], blocks[b][1+r]);
                model.addEquality(firings[r], counts);
            }
            for (int stage = 0; stage < stages; stage++) {
                var choices = new ArrayList<BoolVar>();
                for (int b : members) choices.add(active[stage*size+b]);
                model.addAtMostOne(choices.toArray(BoolVar[]::new));
                if (stage > 0) {
                    var previous = new ArrayList<BoolVar>();
                    for (int b : members) previous.add(active[(stage-1)*size+b]);
                    model.addLessOrEqual(LinearExpr.sum(choices.toArray(BoolVar[]::new)),
                            LinearExpr.sum(previous.toArray(BoolVar[]::new)));
                }
                for (int b : members) for (int i = 0; i < items; i++) {
                    if (groups[i] != group) continue;
                    var prefix = LinearExpr.newBuilder().add(used[i]).add(missing[i]);
                    // Outside producers are earlier and outside consumers are later by the
                    // master's rank constraints. Internal seed producers are staged above.
                    for (int r : produced.columnKeys(i))
                        if (groups[outputs[r]] != group && produced.get(r, i) > 0)
                            prefix.addTerm(firings[r], produced.get(r, i));
                    for (int earlier = 0; earlier < stage; earlier++) for (int prior : members) {
                        long delta = blocks[prior][1+recipes+items+i];
                        if (delta != 0) prefix.addTerm(repeats[earlier*size+prior], delta);
                    }
                    long drain = Math.max(-blocks[b][1+recipes+items+i], 0);
                    prefix.addTerm(repeats[stage*size+b], -drain);
                    model.addGreaterOrEqual(prefix, blocks[b][1+recipes+i] - drain)
                            .onlyEnforceIf(active[stage*size+b]);
                }
            }
        }
        hintSchedules(model, firings, repeats, active, produced, outputs, groups, firingBounds,
                blocks, stages, stocks, repeatBounds, byGroup);
        return repeats;
    }

    /** A bounded scheduling hint, never a restriction or a certificate of an executable plan. */
    private static void hintSchedules(CpModel model, IntVar[] firings, IntVar[] repeats, BoolVar[] active,
            SparseLongMatrix produced, int[] outputs, int[] groups, long[] firingBounds,
            long[][] blocks, int stages, long[] stocks, long[] repeatBounds,
            LinkedHashMap<Integer, ArrayList<Integer>> byGroup) {
        int recipes = firings.length, items = groups.length, size = blocks.length;
        for (var entry : byGroup.entrySet()) {
            if (Thread.currentThread().isInterrupted()) return;
            int group = entry.getKey();
            long[] remaining = new long[recipes];
            BigInteger[] marking = new BigInteger[items];
            for (int r = 0; r < recipes; r++)
                if (groups[outputs[r]] == group) remaining[r] = firingBounds[r];
            for (int i = 0; i < items; i++) {
                marking[i] = BigInteger.valueOf(stocks[i]);
                // Rank constraints place outside producers before this group. Their bounds are
                // optimistic hint credits only; the solver still chooses and checks their counts.
                for (int r : produced.columnKeys(i))
                    if (groups[outputs[r]] != group)
                        marking[i] = marking[i].add(BigInteger.valueOf(produced.get(r, i))
                                .multiply(BigInteger.valueOf(firingBounds[r])));
            }
            long[] scheduled = new long[stages * size];
            for (int stage = 0; stage < stages; stage++) {
                if (Thread.currentThread().isInterrupted()) return;
                int selected = -1;
                long copies = 0;
                BigInteger bestScore = BigInteger.ZERO;
                for (int b : entry.getValue()) {
                    long n = repeatBounds[b];
                    BigInteger perCopy = BigInteger.ZERO;
                    for (int r = 0; r < recipes; r++) if (blocks[b][1 + r] > 0) {
                        n = Math.min(n, remaining[r] / blocks[b][1 + r]);
                        perCopy = perCopy.add(BigInteger.valueOf(blocks[b][1 + r]));
                    }
                    for (int i = 0; n > 0 && i < items; i++) {
                        long required = blocks[b][1 + recipes + i];
                        long delta = blocks[b][1 + recipes + items + i];
                        BigInteger spare = marking[i].subtract(BigInteger.valueOf(required));
                        if (spare.signum() < 0) n = 0;
                        else if (delta < 0) n = Math.min(n, spare.divide(BigInteger.valueOf(-delta))
                                .add(BigInteger.ONE).min(BigInteger.valueOf(n)).longValueExact());
                    }
                    BigInteger score = BigInteger.valueOf(n).multiply(perCopy);
                    if (score.compareTo(bestScore) > 0) {
                        selected = b;
                        copies = n;
                        bestScore = score;
                    }
                }
                if (selected < 0) break;
                scheduled[stage * size + selected] = copies;
                for (int r = 0; r < recipes; r++)
                    remaining[r] -= Math.multiplyExact(blocks[selected][1 + r], copies);
                for (int i = 0; i < items; i++)
                    marking[i] = marking[i].add(BigInteger.valueOf(blocks[selected][1 + recipes + items + i])
                            .multiply(BigInteger.valueOf(copies)));
            }
            boolean complete = true;
            for (long n : remaining) complete &= n == 0;
            if (!complete) continue;
            for (int stage = 0; stage < stages; stage++) for (int b : entry.getValue()) {
                int index = stage * size + b;
                model.addHint(repeats[index], scheduled[index]);
                model.addHint(active[index], scheduled[index] > 0 ? 1L : 0L);
            }
            for (int r = 0; r < recipes; r++)
                if (groups[outputs[r]] == group) model.addHint(firings[r], firingBounds[r]);
        }
    }
}
