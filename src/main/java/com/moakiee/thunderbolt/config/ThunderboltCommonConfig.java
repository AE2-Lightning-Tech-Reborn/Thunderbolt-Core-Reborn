package com.moakiee.thunderbolt.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import com.moakiee.thunderbolt.CoreConfig;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.api.channel.ChannelRequestProvider;
import com.moakiee.thunderbolt.api.channel.HighCapacityChannelOwner;
import com.moakiee.thunderbolt.core.channel.HighCapacityChannelSupport;

/** Common configuration for optional Thunderbolt runtime components. */
public final class ThunderboltCommonConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLE_CP_SAT_PLANNER;
    private static final ModConfigSpec.BooleanValue REUSE_AE_KEYS;
    private static final ModConfigSpec.BooleanValue REUSE_COMPONENT_KEYS;
    private static final ModConfigSpec.BooleanValue REUSE_RESOURCE_LOCATIONS;
    private static final ModConfigSpec.BooleanValue REUSE_TAG_KEYS;
    private static final ModConfigSpec.BooleanValue CACHE_RESOURCE_HASHES;
    private static final ModConfigSpec.BooleanValue FAST_NBT_COPIES;
    private static final ModConfigSpec.EnumValue<ChannelMode> CHANNEL_MODE;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("planning");
        ENABLE_CP_SAT_PLANNER = builder
                .comment(
                        "Enable the experimental OR-Tools CP-SAT crafting planner.",
                        "When enabled, Thunderbolt downloads and verifies the matching native runtime",
                        "during startup. A download or load failure only disables this planner for that run.")
                .define("enableCpSatPlanner", false);
        builder.pop();
        builder.push("objects");
        REUSE_AE_KEYS = builder.comment(
                        "Reuse AE2 item/fluid keys in a bounded cache (experimental).",
                        "Keeps value equality, counts, animation state and native stack copying.",
                        "Disable if another addon requires a fresh key or constructor side effects on every call.")
                .define("reuseAeKeys", true);
        REUSE_COMPONENT_KEYS = builder.comment("Also reuse component keys after repeated copying of a native copy-on-write patch snapshot.",
                        "Fresh/unknown/writable component maps bypass reuse; independent equal patches still compare by value.")
                .define("reuseComponentKeys", true);
        REUSE_RESOURCE_LOCATIONS = builder.comment("Bounded constructor reuse for ResourceLocation; preserves validation and value equality.",
                        "Opt-in: reduces allocation on repeated identifiers but costs time on mostly unique identifiers.")
                .define("reuseResourceLocations", false);
        REUSE_TAG_KEYS = builder.comment("Reuse TagKey constructor candidates before vanilla's canonical interner.")
                .define("reuseTagKeys", true);
        CACHE_RESOURCE_HASHES = builder.comment("Memoize the original hashCode of immutable ResourceLocation and TagKey values.")
                .define("cacheResourceHashes", true);
        FAST_NBT_COPIES = builder.comment("Reduce NBT copy iteration/array-growth allocations without replacing backing container types.")
                .define("fastNbtCopies", true);
        builder.pop();
        builder.push("channel");
        CHANNEL_MODE = builder.comment("When to enable Thunderbolt channel max-flow.")
                .defineEnum("mode", ChannelMode.MOD);
        builder.pop();
        SPEC = builder.build();
    }

    private ThunderboltCommonConfig() {
    }

    public static boolean enableCpSatPlanner() {
        return ENABLE_CP_SAT_PLANNER.get();
    }

    public static boolean reuseAeKeys() {
        return REUSE_AE_KEYS.get();
    }

    public static boolean reuseComponentKeys() { return REUSE_COMPONENT_KEYS.get(); }
    public static boolean reuseResourceLocations() { return REUSE_RESOURCE_LOCATIONS.get(); }
    public static boolean reuseTagKeys() { return REUSE_TAG_KEYS.get(); }
    public static boolean cacheResourceHashes() { return CACHE_RESOURCE_HASHES.get(); }
    public static boolean fastNbtCopies() { return FAST_NBT_COPIES.get(); }

    public static boolean useMaxFlow(IGrid grid, boolean hasControllers) {
        if (!hasControllers) return false;
        return switch (CHANNEL_MODE.get()) {
            case ON -> true;
            case MOD -> CoreConfig.channelMaxFlowRequired();
            case DEVICE -> hasOptInOwner(grid)
                    || HighCapacityChannelSupport.getAllControllerNodes(grid).stream()
                            .anyMatch(node -> ChannelSourceRegistry.isChannelSource(node.getOwner()));
        };
    }

    private static boolean hasOptInOwner(IGrid grid) {
        for (IGridNode node : grid.getNodes()) {
            Object owner = node.getOwner();
            if (owner instanceof ChannelRequestProvider || owner instanceof HighCapacityChannelOwner) {
                return true;
            }
        }
        return false;
    }

    public enum ChannelMode { MOD, DEVICE, ON }
}
