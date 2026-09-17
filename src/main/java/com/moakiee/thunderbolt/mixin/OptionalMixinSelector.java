package com.moakiee.thunderbolt.mixin;

import java.util.Map;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.compat.gtl.GtlCompat;

/**
 * Pure target-to-mod mapping used by the early Mixin config plugin.
 *
 * <p>Two rules exist, and they are independent:
 *
 * <ul>
 *   <li>{@link #REQUIRED_MODS} — an addon-targeted mixin only applies while the owning addon is
 *       present.</li>
 *   <li>{@link #GTL_OWNED_MIXINS} — a mixin whose AE2 injection points GTLCore overwrites is
 *       suppressed while GTLCore is present (see {@link GtlCompat} for the full reasoning).</li>
 * </ul>
 */
public final class OptionalMixinSelector {
    private static final Map<String, String> REQUIRED_MODS = Map.ofEntries(
            Map.entry("AdvCraftingCpuLogicBatchMixin", "advanced_ae"),
            Map.entry("AdvCraftingCpuAccessor", "advanced_ae"),
            Map.entry("AaeExecutingCraftingJobAccessor", "advanced_ae"),
            Map.entry("AaeElapsedTimeTrackerAccessor", "advanced_ae"),
            Map.entry("AaeTaskProgressAccessor", "advanced_ae"),
            Map.entry("ExtendedAePlusSuperMatrixBatchMixin", "extendedae_plus"),
            Map.entry("AppliedETransmutationModuleBatchMixin", "appliede"));

    /**
     * Mixins that must not apply when GTLCore owns the affected method, mapped to the mod id that
     * owns it.
     *
     * <p>CPU dispatch is structural: the {@code @WrapOperation} anchors live inside methods GTLCore
     * {@code @Overwrite}s ({@code tickCraftingLogic} / {@code executeCrafting}), so applying them
     * fails at class load. The batch mixins for other CPU implementations still resolve, but their
     * accounting derives material usage from AE2 pattern semantics while a GTL CPU expands patterns
     * with GTLCore's semantics (catalyst slots, automated pattern expansion). Dispatching through
     * Thunderbolt's batch accounting could charge the wrong keys, so the whole family stands down
     * together.
     *
     * <p>Planning is the opposite: GTLCore {@code @Overwrite}s {@code CraftingCalculation#run} but
     * still calls {@code computePlan()} from that body. Thunderbolt therefore keeps
     * {@code CraftingCalculationMixin} applied and attaches at {@code computePlan()} instead of
     * fighting the overwrite.
     */
    private static final Map<String, String> GTL_OWNED_MIXINS = Map.ofEntries(
            Map.entry("CraftingCpuLogicBatchMixin", GtlCompat.GTL_MOD_ID),
            Map.entry("AdvCraftingCpuLogicBatchMixin", GtlCompat.GTL_MOD_ID),
            Map.entry("ExtendedAePlusSuperMatrixBatchMixin", GtlCompat.GTL_MOD_ID),
            Map.entry("AppliedETransmutationModuleBatchMixin", GtlCompat.GTL_MOD_ID));

    private OptionalMixinSelector() {
    }

    /**
     * Decides whether a mixin from {@code thunderbolt.mixins.json} may be applied.
     *
     * @param mixinClassName fully qualified mixin class name, or a bare simple name
     * @param modLoaded reports whether a mod id is present in the (early) mod list
     * @return true when the mixin may be applied
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
     * Reports whether a mixin belongs to the GTL-owned family.
     *
     * @param mixinClassName fully qualified mixin class name, or a bare simple name
     * @return true when GTLCore supersedes this mixin's injection points
     */
    public static boolean isGtlOwned(String mixinClassName) {
        return GTL_OWNED_MIXINS.containsKey(simpleName(mixinClassName));
    }

    /**
     * Returns the mod id that supersedes a mixin, for diagnostics.
     *
     * @param mixinClassName fully qualified mixin class name, or a bare simple name
     * @return the owning mod id, or {@code null} when the mixin is not GTL-owned
     */
    @Nullable
    public static String gtlOwner(String mixinClassName) {
        return GTL_OWNED_MIXINS.get(simpleName(mixinClassName));
    }

    /**
     * Returns the addon a mixin requires, for diagnostics.
     *
     * @param mixinClassName fully qualified mixin class name, or a bare simple name
     * @return the required mod id, or {@code null} when the mixin applies unconditionally
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
