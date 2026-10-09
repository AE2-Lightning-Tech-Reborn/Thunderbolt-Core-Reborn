package com.moakiee.thunderbolt.compat.gtl;

import java.util.Locale;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;

/**
 * GTLCore compatibility for early Mixin selection and runtime scheduler choice.
 * GTLCore overwrites CPU dispatch methods, so Thunderbolt's batch hooks stand down. Planning stays
 * attached to {@code computePlan()}, which GTLCore's overwritten {@code run()} still calls.
 *
 * <p>Early selection must not load game classes or Forge config. Use
 * {@code -Dthunderbolt.gtlCompat=auto|always|never}: auto follows GTLCore presence, always forces
 * handover for testing, and never retains batch hooks despite GTLCore's conflicting overwrites.
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
     * Reads the system property; unset or unrecognized modes use {@link Handover#AUTO}.
     */
    public static Handover handoverMode() {
        return parseMode(System.getProperty(MODE_PROPERTY));
    }

    /**
     * Parses mode aliases; null, blank or unknown values use {@link Handover#AUTO}.
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
     * Pure handover decision shared by early Mixin selection and runtime accessors.
     */
    public static boolean standDown(Handover mode, boolean gtlPresent) {
        return switch (mode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> gtlPresent;
        };
    }

    /**
     * Early Mixin decision using only the system property and caller-supplied mod detection.
     */
    public static boolean standDown(Predicate<String> gtlModLoaded) {
        return standDown(handoverMode(), gtlModLoaded.test(GTL_MOD_ID));
    }

    /**
     * Caches definite presence only. Inconclusive early probes remain retryable; freezing a false
     * absence could select AE2's monitor under GTLCore's no-op {@code simulateFor} and deadlock.
     */
    public static boolean isGtlPresent() {
        Boolean cached = gtlPresent;
        return cached != null ? cached : rememberPresence(detectGtlPresent());
    }

    /**
     * Records a definite probe; null remains unknown and returns false. Package-visible for tests.
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
     * A LoadingModList hit proves presence; only a ready ModList can prove absence.
     * Null means detection is still inconclusive.
     */
    @Nullable
    static Boolean resolvePresence(@Nullable Boolean loadingHasGtl, @Nullable Boolean modListHasGtl) {
        return Boolean.TRUE.equals(loadingHasGtl) ? Boolean.TRUE : modListHasGtl;
    }

    /**
     * Probes both Forge mod lists; returns null while neither can determine presence.
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
     * CPU-dispatch handover state. Planning and scheduler selection are independent.
     */
    public static boolean isCraftingHandoverActive() {
        return standDown(handoverMode(), isGtlPresent());
    }

    /**
     * GTLCore's no-op {@code simulateFor} needs polling even in never mode. Always mode enables
     * that scheduler without GTLCore for compatibility tests.
     */
    public static boolean usesGtlCalculationScheduler() {
        return isGtlPresent() || handoverMode() == Handover.ALWAYS;
    }

    /**
     * Logs the startup handover decision.
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
