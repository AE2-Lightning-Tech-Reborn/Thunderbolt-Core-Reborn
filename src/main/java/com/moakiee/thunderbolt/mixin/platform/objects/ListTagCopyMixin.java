package com.moakiee.thunderbolt.mixin.platform.objects;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.keys.ObjectReuseOptions;
import com.moakiee.thunderbolt.core.keys.NbtCopyViews;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ListTag.class, priority = 900)
abstract class ListTagCopyMixin {
    @Shadow @Final private List<Tag> list;

    @WrapOperation(method = "copy", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/google/common/collect/Iterables;transform(Ljava/lang/Iterable;Lcom/google/common/base/Function;)Ljava/lang/Iterable;"), require = 0)
    private Iterable<Tag> thunderbolt$fuseTransform(Iterable<Tag> source,
            com.google.common.base.Function<? super Tag, Tag> function, Operation<Iterable<Tag>> original) {
        return ObjectReuseOptions.fastNbtCopies && source.getClass() == ArrayList.class
                ? new NbtCopyViews.TransformIterable(source, function)
                : original.call(source, function);
    }

    @WrapOperation(method = "copy", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/google/common/collect/Lists;newArrayList(Ljava/lang/Iterable;)Ljava/util/ArrayList;"), require = 0)
    private ArrayList<Tag> thunderbolt$sizeTransformedCopy(Iterable<Tag> values, Operation<ArrayList<Tag>> original) {
        if (!ObjectReuseOptions.fastNbtCopies || values instanceof Collection<?>) return original.call(values);
        if (values instanceof NbtCopyViews.TransformIterable view) return view.copy(list.size());
        // Keep the supplied iterable, element-copy semantics, ArrayList type and insertion order.
        // Only the capacity hint differs; custom iterables may still yield any number of elements.
        var result = new ArrayList<Tag>(list.size());
        for (var value : values) result.add(value);
        return result;
    }
}
