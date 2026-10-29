package com.moakiee.thunderbolt.mixin;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.compat.gtl.GtlCompat;

/** Early selection by addon presence and ownership of replaced methods. */
public final class OptionalMixinSelector {
    private static final Map<String, String> REQUIRED_MODS = Map.ofEntries(
            Map.entry("Ae2CraftingTreeCompatibilityMixin", "ae2ct"),
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

    private static final Set<String> NBT_COPY_MIXINS = Set.of(
            "CompoundTagCopyMixin", "ListTagCopyMixin");

    private OptionalMixinSelector() {
    }

    /** Accepts a qualified or simple Mixin name and an early mod-presence predicate. */
    public static boolean shouldApply(String mixinClassName, Predicate<String> modLoaded) {
        String simpleName = simpleName(mixinClassName);
        if (nbtCopyOwner(simpleName, modLoaded) != null) {
            return false;
        }
        if (isGtlOwned(simpleName) && GtlCompat.standDown(modLoaded)) {
            return false;
        }
        String requiredMod = REQUIRED_MODS.get(simpleName);
        return requiredMod == null || modLoaded.test(requiredMod);
    }

    /**
     * Lithium and its Forge ports replace NBT copy methods. Skip our optional wrappers before
     * injection validation; require=0 and a runtime config flag cannot bypass overwrite conflicts.
     * Presence is deliberately conservative, including when the other mod disables its NBT rule.
     */
    @Nullable
    public static String nbtCopyOwner(String mixinClassName, Predicate<String> modLoaded) {
        if (!NBT_COPY_MIXINS.contains(simpleName(mixinClassName))) {
            return null;
        }
        for (String mod : new String[] {"radium", "lithium", "canary"}) {
            if (modLoaded.test(mod)) {
                return mod;
            }
        }
        return null;
    }

    /** Whether GTLCore supersedes this Mixin's dispatch or accounting. */
    public static boolean isGtlOwned(String mixinClassName) {
        return GTL_OWNED_MIXINS.contains(simpleName(mixinClassName));
    }

    /** Owning mod id for diagnostics, or null when the Mixin is not GTL-owned. */
    @Nullable
    public static String gtlOwner(String mixinClassName) {
        return isGtlOwned(mixinClassName) ? GtlCompat.GTL_MOD_ID : null;
    }

    /** Required addon id for diagnostics, or null for an unconditional Mixin. */
    @Nullable
    public static String requiredMod(String mixinClassName) {
        return REQUIRED_MODS.get(simpleName(mixinClassName));
    }

    private static String simpleName(String mixinClassName) {
        int separator = mixinClassName.lastIndexOf('.');
        return separator >= 0 ? mixinClassName.substring(separator + 1) : mixinClassName;
    }
}
