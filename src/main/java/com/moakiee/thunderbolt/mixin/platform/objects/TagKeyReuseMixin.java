package com.moakiee.thunderbolt.mixin.platform.objects;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.keys.ObjectReuseOptions;
import com.moakiee.thunderbolt.core.keys.ResourceConstructionCache;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = TagKey.class, priority = 900)
abstract class TagKeyReuseMixin {
    @Unique private int thunderbolt$cachedHash;

    @WrapOperation(method = "create", at = @At(value = "NEW", target = "net/minecraft/tags/TagKey"), require = 0)
    private static <T> TagKey<T> thunderbolt$reuse(ResourceKey<? extends Registry<T>> registry,
                                                ResourceLocation location, Operation<TagKey<T>> original) {
        return ResourceConstructionCache.tag(registry, location, original);
    }

    @WrapMethod(method = "hashCode", remap = false)
    private int thunderbolt$cacheHash(Operation<Integer> original) {
        if (!ObjectReuseOptions.cacheHashes) return original.call();
        int hash = thunderbolt$cachedHash;
        if (hash == 0) thunderbolt$cachedHash = hash = original.call();
        return hash;
    }
}
