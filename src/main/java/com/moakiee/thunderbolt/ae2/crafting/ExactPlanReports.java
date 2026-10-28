package com.moakiee.thunderbolt.ae2.crafting;

import java.util.Map;
import com.google.common.collect.MapMaker;

/** Weak identity attachments survive plan finalization without retaining closed menus or jobs. */
public final class ExactPlanReports {
    private static final Map<Object, ExactPlanReport> REPORTS = new MapMaker().weakKeys().makeMap();
    private static final Map<Object, ExactPlanReport.Amounts> ENTRIES = new MapMaker().weakKeys().makeMap();

    private ExactPlanReports() {}

    public static void attach(Object owner, ExactPlanReport report) {
        REPORTS.put(owner, report);
        if (owner instanceof appeng.menu.me.crafting.CraftingPlanSummary summary) {
            for (var entry : summary.getEntries()) {
                var amounts = report.entries().get(entry.getWhat());
                if (amounts != null) ENTRIES.put(entry, amounts);
            }
        }
    }

    public static ExactPlanReport.Amounts amounts(Object entry) {
        return ENTRIES.get(entry);
    }

    public static ExactPlanReport get(Object owner) {
        if (owner == null) return null;
        var report = REPORTS.get(owner);
        // GTO finalizes restricted plans by wrapping the original CraftingPlan. The report
        // remains attached to that delegate, including its preview-only submission guard.
        if (report == null && owner instanceof com.moakiee.thunderbolt.core.crafting.plan.LoopCraftingPlan loop) {
            return get(loop.delegate());
        }
        return report;
    }

    public static boolean isPreview(Object plan) {
        // Older GTO integrations use the three-argument report constructor for executable
        // summaries too. Their native simulation flag remains authoritative for display;
        // ICraftingPlan submission guards below still honor the explicit preview marker.
        if (plan instanceof appeng.menu.me.crafting.CraftingPlanSummary summary && !summary.isSimulation()) {
            return false;
        }
        var report = get(plan);
        return report != null && report.previewOnly();
    }
}
