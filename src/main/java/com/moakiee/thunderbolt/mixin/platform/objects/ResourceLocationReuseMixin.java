package com.moakiee.thunderbolt.mixin.platform.objects;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moakiee.thunderbolt.core.keys.ObjectReuseOptions;
import com.moakiee.thunderbolt.core.keys.ResourceConstructionCache;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ResourceLocation.class, priority = 900)
abstract class ResourceLocationReuseMixin {
    @Unique private int thunderbolt$cachedHash;
    @Shadow @Final private String namespace;

    @WrapMethod(method = {"createUntrusted", "tryBuild"})
    private static ResourceLocation thunderbolt$reuse(String namespace, String path, Operation<ResourceLocation> original) {
        if (!ResourceConstructionCache.locationsEnabled()) return original.call(namespace, path);
        return ResourceConstructionCache.location(namespace, path, original);
    }

    @WrapMethod(method = "withDefaultNamespace")
    private static ResourceLocation thunderbolt$reuseDefault(String path, Operation<ResourceLocation> original) {
        if (!ResourceConstructionCache.locationsEnabled()) return original.call(path);
        return ResourceConstructionCache.pathLocation("minecraft", path, original);
    }

    @WrapMethod(method = {"bySeparator", "tryBySeparator"})
    private static ResourceLocation thunderbolt$reuseParsed(String input, char separator, Operation<ResourceLocation> original) {
        if (!ResourceConstructionCache.locationsEnabled()) return original.call(input, separator);
        return ResourceConstructionCache.parsedLocation(input, separator, original);
    }

    @WrapMethod(method = "withPath(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;")
    private ResourceLocation thunderbolt$reusePath(String path, Operation<ResourceLocation> original) {
        if (!ResourceConstructionCache.locationsEnabled()) return original.call(path);
        return ResourceConstructionCache.pathLocation(namespace, path, original);
    }

    @WrapMethod(method = "hashCode", remap = false)
    private int thunderbolt$cacheHash(Operation<Integer> original) {
        if (!ObjectReuseOptions.cacheHashes) return original.call();
        int hash = thunderbolt$cachedHash;
        if (hash == 0) thunderbolt$cachedHash = hash = original.call();
        // ResourceLocation is immutable. A benign race recomputes the same hash; zero just misses.
        return hash;
    }
}
