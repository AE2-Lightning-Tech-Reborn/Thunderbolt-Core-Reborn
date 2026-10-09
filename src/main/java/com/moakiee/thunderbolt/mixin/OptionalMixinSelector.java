package com.moakiee.thunderbolt.mixin;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.compat.gtl.GtlCompat;

/**
 * Early Mixin selection: require an addon's presence and suppress GTL-owned batch hooks.
 */
public final class OptionalMixinSelector {
    private static final Map<String, String> REQUIRED_MODS = Map.ofEntries(
            Map.entry("AdvCraftingCpuLogicBatchMixin", "advanced_ae"),
            Map.entry("AdvCraftingCpuAccessor", "advanced_ae"),
            Map.entry("AaeExecutingCraftingJobAccessor", "advanced_ae"),
            Map.entry("AaeElapsedTimeTrackerAccessor", "advanced_ae"),
            Map.entry("AaeTaskProgressAccessor", "advanced_ae"),
            Map.entry("AppliedETransmutationModuleBatchMixin", "appliede"));

    /**
     * CPU dispatch hooks whose injection points or material accounting GTLCore replaces.
     * Planning stays attached to {@code computePlan()}, which survives GTLCore's overwrite.
     */
    private static final Set<String> GTL_OWNED_MIXINS = Set.of(
            "CraftingCpuLogicBatchMixin",
            "AdvCraftingCpuLogicBatchMixin",
            "ExtendedAePlusSuperMatrixBatchMixin",
            "AppliedETransmutationModuleBatchMixin");

    private OptionalMixinSelector() {
    }

    /**
     * Accepts a fully qualified or simple Mixin name and an early mod-presence predicate.
     */
    public static boolean shouldApply(String mixinClassName, Predicate<String> modLoaded) {
        String simpleName = simpleName(mixinClassName);
        if (isGtlOwned(simpleName) && GtlCompat.standDown(modLoaded)) {
            return false;
        }
        String requiredMod = REQUIRED_MODS.get(simpleName);
        return requiredMod == null || modLoaded.test(requiredMod);
    }

    /**
     * Whether GTLCore supersedes this Mixin's dispatch or accounting.
     */
    public static boolean isGtlOwned(String mixinClassName) {
        return GTL_OWNED_MIXINS.contains(simpleName(mixinClassName));
    }

    /**
     * Owning mod id for diagnostics, or null when the Mixin is not GTL-owned.
     */
    @Nullable
    public static String gtlOwner(String mixinClassName) {
        return isGtlOwned(mixinClassName) ? GtlCompat.GTL_MOD_ID : null;
    }

    /**
     * Required addon id for diagnostics, or null for an unconditional Mixin.
     */
    @Nullable
    public static String requiredMod(String mixinClassName) {
        return REQUIRED_MODS.get(simpleName(mixinClassName));
    }

    private static String simpleName(String mixinClassName) {
        int separator = mixinClassName.lastIndexOf('.');
        return separator >= 0 ? mixinClassName.substring(separator + 1) : mixinClassName;
    }
}
