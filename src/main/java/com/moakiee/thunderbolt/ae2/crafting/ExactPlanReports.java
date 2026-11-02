package com.moakiee.thunderbolt.ae2.crafting;

import java.math.BigInteger;
import java.util.Map;
import com.google.common.collect.MapMaker;
import appeng.api.stacks.AEKey;

/** Weak identity attachments survive plan finalization without retaining closed menus or jobs. */
public final class ExactPlanReports {
    private static final Map<Object, ExactPlanReport> REPORTS = new MapMaker().weakKeys().makeMap();
    private static final Map<Object, ExactPlanReport.Amounts> ENTRIES = new MapMaker().weakKeys().makeMap();
    private static final Map<Object, Map<AEKey, BigInteger>> CRAFTING_TIMES = new MapMaker().weakKeys().makeMap();

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

    /**
     * Retains display-only executions per physical output, independently of output stack sizes.
     * These counts must never be exported as executable {@code ICraftingPlan.patternTimes()}.
     */
    public static void attachCraftingTimes(Object plan, Map<AEKey, BigInteger> times) {
        CRAFTING_TIMES.put(plan, Map.copyOf(times));
    }

    /** Returns server-side preview metadata, or null when ordinary plan counts should be used. */
    public static Map<AEKey, BigInteger> craftingTimes(Object plan) {
        return plan == null ? null : CRAFTING_TIMES.get(plan);
    }

    public static boolean isPreview(Object plan) {
        var report = get(plan);
        return report != null && report.previewOnly();
    }
}
