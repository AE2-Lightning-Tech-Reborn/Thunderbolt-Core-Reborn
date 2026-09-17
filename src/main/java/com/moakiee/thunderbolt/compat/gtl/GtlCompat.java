package com.moakiee.thunderbolt.compat.gtl;

import java.util.Locale;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;

/**
 * Detects a GregTech Leisure Core ("GTL") environment and decides which Thunderbolt hooks must
 * stand down there.
 *
 * <p><b>Why a stand-down exists at all.</b> GTLCore ships its own crafting-calculation wrapper and
 * crafting-CPU-dispatch layer and implements both with {@code @Overwrite}:
 *
 * <ul>
 *   <li>{@code appeng.crafting.CraftingCalculation#run/finish/simulateFor}</li>
 *   <li>{@code appeng.crafting.execution.CraftingCpuLogic#tickCraftingLogic/executeCrafting}
 *       (priority 1100)</li>
 * </ul>
 *
 * <p>An overwrite replaces the method body, so injectors that anchored on a <em>call site inside
 * the overwritten method</em> ({@code computePlan()} inside {@code run()}, {@code executeCrafting}
 * inside {@code tickCraftingLogic}) disappear at injection time. Thunderbolt's mixin config
 * declares {@code defaultRequire: 1}, so a missing injection point is a hard failure.
 *
 * <p>CPU dispatch has no surviving alternative: GTLCore owns {@code executeCrafting} itself, and
 * its pattern expansion (catalysts, automated ME-pattern inflation) is what the pack executes.
 * Those mixins stand down.
 *
 * <p>Planning is different. GTLCore's overwritten {@code run()} still calls
 * {@code computePlan()} — the same private method AE2 used — so Thunderbolt wraps
 * <em>that</em> method instead of fighting the overwrite. The planner therefore runs inside
 * GTLCore's calculation job (MAX_FAST metrics, logging, {@code finish()}) rather than replacing
 * it. Vanilla fallback from Thunderbolt still lands in GTLCore's redirected tree request.
 *
 * <p>So in a GTL environment Thunderbolt yields <b>CPU dispatch</b> to GTLCore, keeps the
 * planning engines attached at {@code computePlan()}, and keeps every other subsystem (channel
 * max-flow, ejection, indexed storage, extended crafting CPUs, client screens, the AE2LT-facing
 * APIs).
 *
 * <p><b>Timing.</b> {@link #standDown} is consulted by the Mixin config plugin while mods are still
 * being discovered, so it must not touch Forge config values and must not load game classes. The
 * mode therefore comes from a system property, which is available before Mixin applies anything:
 *
 * <pre>
 *   -Dthunderbolt.gtlCompat=auto    (default) stand CPU dispatch down only when gtlcore is present
 *   -Dthunderbolt.gtlCompat=always            stand CPU dispatch down even without gtlcore
 *   -Dthunderbolt.gtlCompat=never             keep Thunderbolt's CPU-dispatch hooks in a GTL environment
 * </pre>
 */
public final class GtlCompat {
    /** GTLCore's Forge mod id. */
    public static final String GTL_MOD_ID = "gtlcore";

    /** System property that overrides the automatic GTL detection. */
    public static final String MODE_PROPERTY = "thunderbolt.gtlCompat";

    private static final Logger LOGGER = LoggerFactory.getLogger("thunderbolt");

    /** Reason line used both for the early Mixin log and the startup notice. */
    public static final String HANDOVER_NOTICE =
            "GTLCore detected: Thunderbolt stands down from CPU dispatch "
                    + "(GTLCore overwrites CraftingCpuLogic#executeCrafting) and attaches the planner at "
                    + "CraftingCalculation#computePlan (GTLCore's overwritten run() still calls it). "
                    + "Channel max-flow, ejection, storage, extended CPUs and client screens stay active.";

    private static volatile Boolean gtlPresent;

    private GtlCompat() {
    }

    /** How Thunderbolt treats a GTL environment. */
    public enum Handover {
        /** Stand down only when GTLCore is loaded. */
        AUTO,
        /** Always stand down; reproduces GTL behavior on a non-GTL instance for testing. */
        ALWAYS,
        /** Never stand down; keeps Thunderbolt's CPU-dispatch hooks next to GTLCore's overwrites. */
        NEVER
    }

    /**
     * Resolves the configured mode. Pure: safe to call from the Mixin config plugin.
     *
     * @return the configured mode; {@link Handover#AUTO} when unset or unrecognized
     */
    public static Handover handoverMode() {
        return parseMode(System.getProperty(MODE_PROPERTY));
    }

    /**
     * Parses a mode token.
     *
     * @param raw property value, may be {@code null}
     * @return the matching mode, or {@link Handover#AUTO} for {@code null}/blank/unknown values
     */
    public static Handover parseMode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Handover.AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "always", "force", "on", "true" -> Handover.ALWAYS;
            case "never", "off", "false" -> Handover.NEVER;
            default -> Handover.AUTO;
        };
    }

    /**
     * Decides whether the GTL stand-down applies.
     *
     * <p>Pure decision table shared by the Mixin plugin (which supplies GTLCore's presence from the
     * early mod list) and the runtime accessors.
     *
     * @param mode configured handover mode
     * @param gtlPresent whether GTLCore is loaded
     * @return true when Thunderbolt's GTL-owned hooks must not apply
     */
    public static boolean standDown(Handover mode, boolean gtlPresent) {
        return switch (mode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> gtlPresent;
        };
    }

    /**
     * Decides whether to suppress a mixin that GTLCore supersedes.
     *
     * <p>Called from {@code OptionalMixinSelector} during Mixin application, so it only reads system
     * properties and the caller-supplied presence predicate.
     *
     * @param gtlModLoaded predicate reporting whether {@value #GTL_MOD_ID} is present
     * @return true when the caller must not apply the mixin
     */
    public static boolean standDown(Predicate<String> gtlModLoaded) {
        return standDown(handoverMode(), gtlModLoaded.test(GTL_MOD_ID));
    }

    /**
     * Reports whether GTLCore is loaded.
     *
     * <p>A definite answer is cached. An undetermined probe (neither {@code LoadingModList} nor
     * {@code ModList} is usable yet) returns {@code false} without caching, so a later call can still
     * observe GTLCore. Caching a premature {@code false} would leave Mixin CPU-dispatch
     * stand-down in effect while {@link #isCraftingHandoverActive()} stayed false. The
     * planner wrapper would then wait on AE2's per-tick monitor under GTLCore's no-op
     * {@code simulateFor} and deadlock, and leftover {@code ICraftingPlan} jobs would be
     * rejected as {@code CPU_OFFLINE} instead of being left to GTLCore.
     *
     * @return true when {@value #GTL_MOD_ID} is present
     */
    public static boolean isGtlPresent() {
        return rememberPresence(detectGtlPresent());
    }

    /**
     * Records a definite presence probe. Package-visible so the cache policy can be unit-tested
     * without constructing Forge's mod lists.
     *
     * @param detected {@code true}/{@code false} when known, {@code null} when the probe was inconclusive
     * @return the cached or newly recorded value; {@code false} when still unknown
     */
    static boolean rememberPresence(@Nullable Boolean detected) {
        Boolean cached = gtlPresent;
        if (cached != null) {
            return cached;
        }
        if (detected == null) {
            return false;
        }
        gtlPresent = detected;
        return detected;
    }

    /** Clears the presence cache. Package-visible for tests. */
    static void resetPresenceCache() {
        gtlPresent = null;
    }

    /**
     * Combines the two Forge lists the Mixin plugin also consults.
     *
     * <p>A hit on {@code LoadingModList} is enough. A miss there is <em>not</em> a definite
     * absence: the plugin falls through to {@code ModList}, and so must this probe. Only a
     * ready {@code ModList} may record {@code false}. Either list missing entirely is
     * inconclusive ({@code null}) so {@link #rememberPresence} will not freeze a premature no.
     *
     * @param loadingHasGtl {@code true}/{@code false} when {@code LoadingModList} answered,
     *                      {@code null} when it was unusable
     * @param modListHasGtl {@code true}/{@code false} when {@code ModList} answered,
     *                      {@code null} when it was unusable
     * @return presence, or {@code null} when still unknown
     */
    @Nullable
    static Boolean resolvePresence(@Nullable Boolean loadingHasGtl, @Nullable Boolean modListHasGtl) {
        if (Boolean.TRUE.equals(loadingHasGtl)) {
            return true;
        }
        if (modListHasGtl != null) {
            return modListHasGtl;
        }
        return null;
    }

    /**
     * Probes GTLCore the same way the Mixin config plugin does: {@code LoadingModList} first,
     * then {@code ModList}. Returns {@code null} when neither list can answer yet.
     */
    @Nullable
    private static Boolean detectGtlPresent() {
        Boolean loadingHasGtl = null;
        try {
            var loading = LoadingModList.get();
            if (loading != null) {
                loadingHasGtl = loading.getModFileById(GTL_MOD_ID) != null;
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.debug("GTLCore presence via LoadingModList could not be determined yet", failure);
        }
        Boolean modListHasGtl = null;
        try {
            var modList = ModList.get();
            if (modList != null) {
                modListHasGtl = modList.getModFileById(GTL_MOD_ID) != null;
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.debug("GTLCore presence could not be determined yet", failure);
        }
        return resolvePresence(loadingHasGtl, modListHasGtl);
    }

    /**
     * Reports whether Thunderbolt's CPU-dispatch hooks are handed over to GTLCore.
     *
     * <p>Planning is independent of this flag: {@code CraftingCalculationMixin} stays applied and
     * wraps {@code computePlan()}. The flag still changes how that wrapper yields — GTLCore's
     * {@code simulateFor} is a no-op, so the AE2 per-tick monitor protocol would deadlock.
     *
     * @return true when the GTL CPU-dispatch stand-down is in effect for this run
     */
    public static boolean isCraftingHandoverActive() {
        return standDown(handoverMode(), isGtlPresent());
    }

    /**
     * Emits the one-time startup notice describing the decision.
     *
     * @param logger logger of the calling mod class
     */
    public static void logStartupDecision(Logger logger) {
        var mode = handoverMode();
        if (isCraftingHandoverActive()) {
            logger.info("[Thunderbolt Core Reborn] {}", HANDOVER_NOTICE);
            if (mode == Handover.ALWAYS && !isGtlPresent()) {
                logger.info("[Thunderbolt Core Reborn] GTL stand-down forced by -D{}=always "
                        + "without GTLCore installed", MODE_PROPERTY);
            }
        } else if (isGtlPresent()) {
            logger.warn("[Thunderbolt Core Reborn] GTLCore is installed but the GTL CPU-dispatch "
                    + "stand-down is disabled by -D{}=never. Thunderbolt's CPU-batch hooks target "
                    + "methods GTLCore overwrites; expect a Mixin injection failure or broken dispatch.",
                    MODE_PROPERTY);
        }
    }
}
