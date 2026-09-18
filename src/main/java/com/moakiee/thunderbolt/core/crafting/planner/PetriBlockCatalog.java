package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Bounded witness families, never an exhaustive characterization of a cyclic component. */
final class PetriBlockCatalog {
    static final int STAGES = 4;
    private static final int MAX_BLOCKS = 96;
    record Block(int group, PetriExecutionTrace.Node trace, long[] wire) { }

    private final long[][] pre, post;
    private final int[] groups;
    private final List<Block> blocks = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private int remainingProbes = 8192;

    private PetriBlockCatalog(long[][] pre, long[][] post, int[] groups) {
        this.pre = pre; this.post = post; this.groups = groups;
        remainingProbes = Math.min(remainingProbes, 524_288 / Math.max(1, pre.length + groups.length));
    }

    static List<Block> build(long[][] pre, long[][] post, int[] groups, int[] outputs,
                             Set<Integer> cyclicGroups, List<PetriExecutionTrace.Node> suggested) {
        var catalog = new PetriBlockCatalog(pre, post, groups);
        var members = new LinkedHashMap<Integer, List<Integer>>();
        for (int r = 0; r < pre.length; r++) {
            int group = groups[outputs[r]];
            if (cyclicGroups.contains(group)) members.computeIfAbsent(group, ignored -> new ArrayList<>()).add(r);
        }
        int singles = members.values().stream().mapToInt(List::size).sum();
        if (singles == 0 || singles > 48 || members.size() > 16
                || members.values().stream().anyMatch(rs -> rs.size() > 12)) return List.of();
        // Every recipe at an encoded rank is covered, including external seed producers.
        for (var entry : members.entrySet()) for (int r : entry.getValue())
            catalog.add(entry.getKey(), new PetriExecutionTrace.Fire(r, 1));
        for (var trace : suggested) {
            var summary = PetriExecutionTrace.summarize(trace, pre, post);
            if (summary == null) continue;
            int group = -1;
            for (int r = 0; r < pre.length; r++) if (summary.firings()[r] > 0) {
                int current = groups[outputs[r]];
                if (!cyclicGroups.contains(current) || (group >= 0 && current != group)) { group = -1; break; }
                group = current;
            }
            if (group >= 0) catalog.add(group, trace);
        }
        for (var entry : members.entrySet()) {
            // Deterministic rotations also cover lossy/full-vector grouped schedules.
            var recipes = entry.getValue();
            for (int start = 0; start < recipes.size(); start++) {
                var steps = new ArrayList<PetriExecutionTrace.Node>();
                for (int j = 0; j < recipes.size(); j++)
                    steps.add(new PetriExecutionTrace.Fire(recipes.get((start + j) % recipes.size()), 1));
                catalog.add(entry.getKey(), new PetriExecutionTrace.Sequence(steps));
            }
            catalog.discover(entry.getKey(), recipes, new ArrayList<>(), new int[pre.length]);
        }
        return List.copyOf(catalog.blocks);
    }

    private void discover(int group, List<Integer> recipes, List<PetriExecutionTrace.Node> steps, int[] counts) {
        if (remainingProbes-- <= 0 || blocks.size() >= MAX_BLOCKS) return;
        PlanningCancellation.check();
        if (steps.size() >= 2) {
            var trace = new PetriExecutionTrace.Sequence(steps);
            var summary = PetriExecutionTrace.summarize(trace, pre, post);
            boolean round = summary != null;
            for (int i = 0; round && i < groups.length; i++)
                if (groups[i] == group && summary.delta()[i].signum() > 0) round = false;
            if (round) add(group, trace);
        }
        if (steps.size() >= Math.min(6, recipes.size() * 2)) return;
        for (int r : recipes) {
            if (counts[r] >= 2) continue;
            counts[r]++;
            steps.add(new PetriExecutionTrace.Fire(r, 1));
            discover(group, recipes, steps, counts);
            steps.removeLast(); counts[r]--;
            if (remainingProbes <= 0 || blocks.size() >= MAX_BLOCKS) break;
        }
    }

    private void add(int group, PetriExecutionTrace.Node trace) {
        if (blocks.size() >= MAX_BLOCKS) return;
        var summary = PetriExecutionTrace.summarize(trace, pre, post);
        if (summary == null) return;
        String key = group + ":" + Arrays.toString(summary.firings()) + Arrays.toString(summary.required());
        if (!seen.add(key)) return;
        long[] wire = new long[1 + pre.length + 2 * groups.length];
        wire[0] = group;
        System.arraycopy(summary.firings(), 0, wire, 1, pre.length);
        for (int i = 0; i < groups.length; i++) {
            BigInteger required = summary.required()[i], delta = summary.delta()[i];
            if (required.compareTo(BigInteger.valueOf(Sat.SAT)) >= 0
                    || delta.abs().compareTo(BigInteger.valueOf(Sat.SAT)) >= 0) return;
            wire[1 + pre.length + i] = required.longValueExact();
            wire[1 + pre.length + groups.length + i] = delta.longValueExact();
        }
        blocks.add(new Block(group, trace, wire));
    }
}
