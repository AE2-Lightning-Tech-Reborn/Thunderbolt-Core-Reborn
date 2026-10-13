package com.moakiee.thunderbolt.mixin.platform.objects;

import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Function;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.nbt.NbtCopyOptions;
import com.moakiee.thunderbolt.core.nbt.NbtCopyViews;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Adapted from Thunderbolt-Core-Reborn dc4d9bc, LGPL-3.0; retains the value-tag branch. */
@Mixin(value = ListTag.class, priority = 900)
abstract class ListTagCopyMixin {
    @Shadow @Final private List<Tag> list;

    @WrapOperation(method = "copy()Lnet/minecraft/nbt/ListTag;", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/google/common/collect/Iterables;transform(Ljava/lang/Iterable;Lcom/google/common/base/Function;)Ljava/lang/Iterable;"), require = 0)
    private Iterable<Tag> thunderbolt$fuseTransform(Iterable<Tag> source,
            Function<? super Tag, Tag> function, Operation<Iterable<Tag>> original) {
        return NbtCopyOptions.enabled() && source.getClass() == ArrayList.class
                ? new NbtCopyViews.TransformIterable(source, function)
                : original.call(source, function);
    }

    @WrapOperation(method = "copy()Lnet/minecraft/nbt/ListTag;", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/google/common/collect/Lists;newArrayList(Ljava/lang/Iterable;)Ljava/util/ArrayList;"), require = 0)
    private ArrayList<Tag> thunderbolt$collectCopy(Iterable<Tag> values, Operation<ArrayList<Tag>> original) {
        return NbtCopyOptions.enabled() && values instanceof NbtCopyViews.TransformIterable view
                ? view.copy(list.size()) : original.call(values);
    }
}
