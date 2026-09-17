package com.moakiee.thunderbolt.core.crafting.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;
import com.google.common.collect.MapMaker;
import com.moakiee.thunderbolt.core.crafting.loop.PatternFiringExpander;
import com.moakiee.thunderbolt.core.crafting.pattern.PlannedInputPattern;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlan;

/** Optional execution metadata. Native CPUs still see the original registered patternTimes map. */
public final class PlannedInputAssignments {
    private static final Map<CraftingPlan, Map<IPatternDetails, List<Task>>> PLANS =
            new MapMaker().weakKeys().makeMap();

    private PlannedInputAssignments() { }

    public record Task(IPatternDetails pattern, long copies) { }

    public static void record(CraftingPlan exported, CraftPlan<AEKey> plan) {
        var assignments = new LinkedHashMap<IPatternDetails, List<Task>>();
        for (var entry : plan.firings().entrySet()) {
            var concrete = entry.getKey();
            var source = (IPatternDetails) concrete.source();
            // Macro inputs are not the slots of their expanded physical tasks. Those tasks already
            // carry their own routed seed contract and must not inherit an unrelated slot allocation.
            if (source instanceof PatternFiringExpander || concrete.executionSlots().isEmpty()
                    || concrete.executionSlots().stream().allMatch(List::isEmpty)) continue;
            var slots = new ArrayList<Map<AEKey, Long>>();
            for (var inputs : concrete.executionSlots()) {
                var slot = new LinkedHashMap<AEKey, Long>();
                for (var input : inputs) slot.merge(input.key(), input.exactAmount().longValueExact(), Math::addExact);
                slots.add(slot);
            }
            assignments.computeIfAbsent(source, ignored -> new ArrayList<>())
                    .add(new Task(new PlannedInputPattern(source, slots), entry.getValue()));
        }
        // A partially bound source must not silently become an unrestricted task.
        for (var entry : assignments.entrySet()) {
            long copies = 0;
            for (var task : entry.getValue()) copies = Math.addExact(copies, task.copies());
            if (copies != exported.patternTimes().getOrDefault(entry.getKey(), 0L)) {
                throw new IllegalStateException("Incomplete planned input assignments");
            }
        }
        assignments.replaceAll((source, tasks) -> List.copyOf(tasks));
        if (!assignments.isEmpty()) PLANS.put(exported, Map.copyOf(assignments));
    }

    public static Map<IPatternDetails, List<Task>> get(ICraftingPlan plan) {
        if (plan instanceof LoopCraftingPlan loop) plan = loop.delegate();
        return plan instanceof CraftingPlan concrete ? PLANS.getOrDefault(concrete, Map.of()) : Map.of();
    }
}
