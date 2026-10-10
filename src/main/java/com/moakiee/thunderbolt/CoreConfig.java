package com.moakiee.thunderbolt;

import java.util.Collection;

import com.mojang.logging.LogUtils;
import com.moakiee.thunderbolt.core.util.FastWildcardMatcher;
import org.slf4j.Logger;

/** Lightweight host-configured values shared by Thunderbolt's low-level hooks. */
public final class CoreConfig {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Default controller capacity; the host may override it through {@link #setChannelsPerController}. */
    private static volatile int channelsPerController = 128;
    private static volatile boolean channelMaxFlowRequired;
    private static volatile BatchCopyLimitRules batchCopyLimitRules =
            new BatchCopyLimitRules(0L, FastWildcardMatcher.empty());

    public static int channelsPerController() {
        return channelsPerController;
    }

    public static void setChannelsPerController(int value) {
        // Non-positive source capacity would disable channel allocation.
        int clamped = Math.max(1, value);
        if (value != clamped) {
            LOGGER.warn("非法的 channelsPerController 配置值: {}，已钳制为 {}", value, clamped);
        }
        channelsPerController = clamped;
    }

    public static boolean channelMaxFlowRequired() {
        return channelMaxFlowRequired;
    }

    /** Declares that a loaded consumer mod requires Thunderbolt channel routing. */
    public static void requireChannelMaxFlow() {
        channelMaxFlowRequired = true;
    }

    /**
     * Returns the immutable, versioned block matcher used by hot-path batch targets.
     * Callers may cache the result until {@link BatchCopyLimitRules#version()} changes.
     */
    public static BatchCopyLimitRules batchCopyLimitRules() {
        return batchCopyLimitRules;
    }

    public static synchronized void setBatchCopyLimitedBlocks(
            Collection<? extends String> patterns) {
        var current = batchCopyLimitRules;
        batchCopyLimitRules = new BatchCopyLimitRules(
                current.version() + 1L,
                FastWildcardMatcher.compile(patterns));
    }

    public record BatchCopyLimitRules(long version, FastWildcardMatcher matcher) {
        public static final int MATCHED_MAX_COPIES = 1024;

        public boolean matches(String blockId) {
            return matcher.matches(blockId);
        }

        public long limit(String blockId) {
            return matches(blockId) ? MATCHED_MAX_COPIES : Long.MAX_VALUE;
        }
    }

    private CoreConfig() {
    }
}
