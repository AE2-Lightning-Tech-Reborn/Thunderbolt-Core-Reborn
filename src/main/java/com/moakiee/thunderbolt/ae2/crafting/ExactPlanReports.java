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
        return owner == null ? null : REPORTS.get(owner);
    }

    public static boolean isPreview(Object plan) {
        return get(plan) != null;
    }
}
