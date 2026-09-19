package com.moakiee.thunderbolt.mixin.ae2.key;

import appeng.api.stacks.AEItemKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AEItemKey.class, remap = false)
abstract class AEItemKeyConstructionMixin {
    // Leave factory HEAD/RETURN hooks, stack copying, codecs, packets and equality untouched.
    // If an addon replaces the factory and removes this allocation, reuse simply does not apply.
    @WrapOperation(method = "of(Lnet/minecraft/world/item/ItemStack;)Lappeng/api/stacks/AEItemKey;",
            at = @At(value = "NEW", target = "appeng/api/stacks/AEItemKey"), require = 0)
    private static AEItemKey thunderbolt$reuseKey(ItemStack stack, Operation<AEItemKey> original) {
        return KeyConstructionCache.item(stack, original);
    }
}
