package com.moakiee.thunderbolt.mixin;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Pure target-to-mod mapping used by the early Mixin config plugin. */
public final class OptionalMixinSelector {
    private static final Set<String> NBT_COPY_MIXINS = Set.of("CompoundTagCopyMixin", "ListTagCopyMixin");
    private static final Map<String, String> REQUIRED_MODS = Map.ofEntries(
            Map.entry("Ae2CraftingTreeCompatibilityMixin", "ae2ct"),
            Map.entry("LtBigCraftingPlanSummaryMixin", "ae2lt"),
            Map.entry("LtIOPortInventoryLoadMixin", "ae2lt"),
            Map.entry("AdvCraftingCpuLogicBatchMixin", "advanced_ae"),
            Map.entry("AdvCraftingCpuAccessor", "advanced_ae"),
            Map.entry("AaeExecutingCraftingJobAccessor", "advanced_ae"),
            Map.entry("AaeElapsedTimeTrackerAccessor", "advanced_ae"),
            Map.entry("AaeTaskProgressAccessor", "advanced_ae"),
            Map.entry("AppliedETransmutationModuleBatchMixin", "appliede"));

    private OptionalMixinSelector() {
    }

    public static boolean shouldApply(String mixinClassName, Predicate<String> modLoaded) {
        int separator = mixinClassName.lastIndexOf('.');
        String simpleName = separator >= 0 ? mixinClassName.substring(separator + 1) : mixinClassName;
        if (nbtCopyOwner(simpleName, modLoaded) != null) return false;
        String requiredMod = REQUIRED_MODS.get(simpleName);
        return requiredMod == null || modLoaded.test(requiredMod);
    }

    /** Select before injection validation: require=0 cannot bypass an overwrite conflict. */
    public static String nbtCopyOwner(String mixinClassName, Predicate<String> modLoaded) {
        int separator = mixinClassName.lastIndexOf('.');
        String simpleName = separator >= 0 ? mixinClassName.substring(separator + 1) : mixinClassName;
        if (!NBT_COPY_MIXINS.contains(simpleName)) return null;
        for (String mod : new String[] {"leanobject", "radium", "lithium", "canary"}) {
            if (modLoaded.test(mod)) return mod;
        }
        return null;
    }
}
