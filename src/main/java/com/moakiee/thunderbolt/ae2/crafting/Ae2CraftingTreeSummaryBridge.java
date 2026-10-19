package com.moakiee.thunderbolt.ae2.crafting;

import java.lang.reflect.InvocationTargetException;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;

/** Keeps AE2 Crafting Tree's optional summary payload initialized on Thunderbolt summaries. */
public final class Ae2CraftingTreeSummaryBridge {
    private static final String API_PACKAGE = "com.neuvillette.ae2ct.api";

    private Ae2CraftingTreeSummaryBridge() {
    }

    public static void attach(CraftingPlanSummary summary, ICraftingPlan plan) {
        attachSupportedIntegration(summary, plan, CraftingPlanSummary.class.getClassLoader());
    }

    static boolean attachSupportedIntegration(Object summary, ICraftingPlan plan, ClassLoader loader) {
        final Class<?> summaryView;
        final Class<?> recipeHelper;
        try {
            summaryView = Class.forName(API_PACKAGE + ".ICraftingPlanSummary", false, loader);
            recipeHelper = Class.forName(API_PACKAGE + ".RecipeHelper", false, loader);
        } catch (ClassNotFoundException absent) {
            return false;
        }
        if (!summaryView.isInstance(summary)) {
            return false;
        }

        try {
            // A third-party summary factory may already have attached its own tree.
            if (summaryView.getMethod("getJob").invoke(summary) != null) {
                return true;
            }
            Object tree = recipeHelper
                    .getMethod("fromCraftingPlan", CraftingPlan.class)
                    .invoke(null, CraftingPlanSummaryAdapter.adapt(plan));
            summaryView.getMethod("setJob", recipeHelper).invoke(summary, tree);
            return true;
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    ? invocation.getCause()
                    : failure;
            throw new IllegalStateException(
                    "Failed to attach AE2 Crafting Tree data to a Thunderbolt plan summary", cause);
        }
    }
}
