package com.moakiee.thunderbolt.mixin.platform.objects;

import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Function;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.nbt.NbtCopyOptions;
import com.moakiee.thunderbolt.core.nbt.NbtCopyViews;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adapted from Thunderbolt-Core-Reborn dc4d9bc, LGPL-3.0; preserves replaced copy views. */
@Mixin(value = CompoundTag.class, priority = 900)
abstract class CompoundTagCopyMixin {
    @WrapOperation(method = "copy()Lnet/minecraft/nbt/CompoundTag;", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/google/common/collect/Maps;transformValues(Ljava/util/Map;Lcom/google/common/base/Function;)Ljava/util/Map;"), require = 0)
    private Map<String, Tag> thunderbolt$fuseTransform(Map<String, Tag> source,
            Function<? super Tag, Tag> function, Operation<Map<String, Tag>> original) {
        return NbtCopyOptions.enabled() && source.getClass() == HashMap.class
                ? new NbtCopyViews.TransformMap(source, function)
                : original.call(source, function);
    }

    @WrapOperation(method = "copy()Lnet/minecraft/nbt/CompoundTag;", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/google/common/collect/Maps;newHashMap(Ljava/util/Map;)Ljava/util/HashMap;"), require = 0)
    private HashMap<String, Tag> thunderbolt$collectCopy(Map<String, Tag> transformed,
            Operation<HashMap<String, Tag>> original) {
        // A disabled flag or a view supplied by another mod keeps the original collector.
        return NbtCopyOptions.enabled() && transformed instanceof NbtCopyViews.TransformMap view
                ? view.copy() : original.call(transformed);
    }
}
