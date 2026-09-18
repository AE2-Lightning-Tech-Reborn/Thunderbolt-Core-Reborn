package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Prefix constraints for a finite family of real execution traces, sharing the master's resources. */
final class CpSatExecutionBlocks {
    private CpSatExecutionBlocks() { }

    /** Wire row: group, original firing vector, required marking, net change. */
    static IntVar[] add(CpModel model, IntVar[] firings, IntVar[] used, IntVar[] missing,
                        long[][] produced, int[] outputs, int[] groups, long[] firingBounds,
                        long[][] blocks, int stages) {
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
        for (int stage = 0; stage < stages; stage++) for (int b = 0; b < size; b++) {
            long upper = safeUpper;
            for (int r = 0; r < recipes; r++) if (blocks[b][1+r] > 0)
                upper = Math.min(upper, firingBounds[r] / blocks[b][1+r]);
            int index = stage * size + b;
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
                    for (int r = 0; r < recipes; r++)
                        if (groups[outputs[r]] != group && produced[r][i] > 0)
                            prefix.addTerm(firings[r], produced[r][i]);
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
        return repeats;
    }
}
