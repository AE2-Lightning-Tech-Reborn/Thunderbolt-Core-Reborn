package com.moakiee.thunderbolt.mixin.platform.objects;

import java.util.HashMap;
import java.util.Map;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.keys.ObjectReuseOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CompoundTag.class, priority = 900)
abstract class CompoundTagCopyMixin {
    @WrapOperation(method = "copy", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/google/common/collect/Maps;newHashMap(Ljava/util/Map;)Ljava/util/HashMap;"), require = 0)
    private HashMap<String, Tag> thunderbolt$copyWithoutEntryWrappers(Map<String, Tag> transformed,
                                                                    Operation<HashMap<String, Tag>> original) {
        if (!ObjectReuseOptions.fastNbtCopies || transformed.isEmpty()) return original.call(transformed);
        var result = HashMap.<String, Tag>newHashMap(transformed.size());
        // Use the actual transformed view passed by vanilla/other mixins. Its forEach invokes
        // Tag.copy exactly once per value, without allocating a transformed Entry per element.
        transformed.forEach(result::put);
        return result;
    }
}
