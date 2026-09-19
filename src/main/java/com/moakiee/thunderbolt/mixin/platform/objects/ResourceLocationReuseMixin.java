package com.moakiee.thunderbolt.mixin.platform.objects;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.keys.ObjectReuseOptions;
import com.moakiee.thunderbolt.core.keys.ResourceConstructionCache;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ResourceLocation.class, priority = 900)
abstract class ResourceLocationReuseMixin {
    @Unique private int thunderbolt$cachedHash;

    @WrapOperation(method = {"createUntrusted", "withDefaultNamespace", "tryBuild", "tryBySeparator"},
            at = @At(value = "NEW", target = "net/minecraft/resources/ResourceLocation"), require = 0)
    private static ResourceLocation thunderbolt$reuse(String namespace, String path, Operation<ResourceLocation> original) {
        return ResourceConstructionCache.location(namespace, path, original);
    }

    @WrapOperation(method = "withPath(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At(value = "NEW", target = "net/minecraft/resources/ResourceLocation"), require = 0)
    private ResourceLocation thunderbolt$reusePath(String namespace, String path, Operation<ResourceLocation> original) {
        return ResourceConstructionCache.location(namespace, path, original);
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
