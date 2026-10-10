package com.moakiee.thunderbolt.mixin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;

import com.moakiee.thunderbolt.compat.gtl.GtlCompat;

/** Applies optional-addon mixins only when their owning mod is present. */
public final class ThunderboltMixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("thunderbolt");

    /** One INFO line per suppressed GTL-owned mixin; everything else stays at debug. */
    private static final Set<String> GTL_STAND_DOWN_REPORTED = ConcurrentHashMap.newKeySet();

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean apply = OptionalMixinSelector.shouldApply(
                mixinClassName, ThunderboltMixinConfigPlugin::isModLoaded);
        String requiredMod = OptionalMixinSelector.requiredMod(mixinClassName);
        boolean requiredPresent = requiredMod == null || isModLoaded(requiredMod);
        if (!apply && requiredPresent && OptionalMixinSelector.isGtlOwned(mixinClassName)
                && GtlCompat.standDown(ThunderboltMixinConfigPlugin::isModLoaded)) {
            // Report GTL handover only when the owning addon is present.
            reportGtlStandDown(mixinClassName, targetClassName);
        } else {
            LOGGER.debug("Mixin select: {} -> {} : {}", mixinClassName, targetClassName, apply);
        }
        return apply;
    }

    private static void reportGtlStandDown(String mixinClassName, String targetClassName) {
        if (GTL_STAND_DOWN_REPORTED.add(mixinClassName)) {
            LOGGER.info(
                    "[Thunderbolt Core Reborn] GTL stand-down (mode {}): mixin {} is not applied "
                            + "because GTLCore owns {}. Force it back with -D{}=never.",
                    GtlCompat.handoverMode(), mixinClassName, targetClassName,
                    GtlCompat.MODE_PROPERTY);
        }
    }

    private static boolean isModLoaded(String modId) {
        // Mixin selection can precede ModList.init(); consult LoadingModList first.
        try {
            var loading = LoadingModList.get();
            if (loading != null && loading.getModFileById(modId) != null) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // fall through to the full ModList below
        }
        try {
            var modList = ModList.get();
            return modList != null && modList.getModFileById(modId) != null;
        } catch (RuntimeException ignored) {
            // Skip optional mixins when discovery is unavailable.
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
