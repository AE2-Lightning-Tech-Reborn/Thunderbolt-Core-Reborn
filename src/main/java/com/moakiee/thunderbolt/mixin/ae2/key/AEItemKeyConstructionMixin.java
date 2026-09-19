package com.moakiee.thunderbolt.mixin.ae2.key;

import appeng.api.stacks.AEItemKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import com.moakiee.thunderbolt.core.keys.ItemKeyCacheOwner;
import com.moakiee.thunderbolt.core.keys.SharedComponentPatch;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;

@Mixin(value = AEItemKey.class, remap = false)
abstract class AEItemKeyConstructionMixin {
    @Shadow @Final private ItemStack stack;
    // Published with the key through the volatile Item slot; avoids a dependent stack/map read on hits.
    @Unique private Object thunderbolt$plainPrototype;

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static ItemStack thunderbolt$normalizeKeyStack(ItemStack source) {
        return KeyConstructionCache.enabled() ? KeyConstructionCache.normalizedStack(source) : source;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void thunderbolt$capturePlainState(ItemStack source, CallbackInfo ci) {
        if (stack.isComponentsPatchEmpty() && stack.getComponents() instanceof SharedComponentPatch access) {
            thunderbolt$plainPrototype = access.thunderbolt$prototypeIdentity();
        }
    }

    @WrapMethod(method = "of(Lnet/minecraft/world/item/ItemStack;)Lappeng/api/stacks/AEItemKey;")
    private static AEItemKey thunderbolt$cachedItemKey(ItemStack source, Operation<AEItemKey> original) {
        if (!source.isComponentsPatchEmpty()) {
            if (source.getComponents() instanceof SharedComponentPatch access
                    && access.thunderbolt$copyOnWritePatchIdentity() != null) {
                var cached = KeyConstructionCache.findItem(source);
                if (cached != null) return cached;
            }
        } else {
            var key = ((ItemKeyCacheOwner) (Object) source.getItem()).thunderbolt$plainKey();
            if (key != null && source.getComponents() instanceof SharedComponentPatch access
                    && access.thunderbolt$prototypeIdentity()
                    == ((AEItemKeyConstructionMixin) (Object) key).thunderbolt$plainPrototype) return key;
        }
        return original.call(source);
    }
    // The general path keeps the factory body. At a hit, its native copy -> constructor
    // expression transports the already-owned read-only stack and returns the matching key.
    // Misses still copy before constructing; caller-owned stacks are never retained by a key.
    @WrapOperation(method = "of(Lnet/minecraft/world/item/ItemStack;)Lappeng/api/stacks/AEItemKey;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;copy()Lnet/minecraft/world/item/ItemStack;"),
            require = 0)
    private static ItemStack thunderbolt$skipCopyOnHit(ItemStack stack, Operation<ItemStack> original) {
        // Fresh writable patches cannot have an identity hit. Keep one-shot component construction
        // out of both lookup layers, including their generic dispatch and content hashing costs.
        boolean freshPatch = !stack.isComponentsPatchEmpty() && stack.getComponents() instanceof SharedComponentPatch access
                && access.thunderbolt$copyOnWritePatchIdentity() == null;
        if (!freshPatch) {
            var cached = KeyConstructionCache.findItem(stack);
            if (cached != null) return cached.getReadOnlyStack();
        }
        var copy = original.call(stack);
        if (KeyConstructionCache.enabled() && !copy.isEmpty()) {
            copy.setCount(1);
            copy.setPopTime(0);
        }
        return copy;
    }

    @WrapOperation(method = "of(Lnet/minecraft/world/item/ItemStack;)Lappeng/api/stacks/AEItemKey;",
            at = @At(value = "NEW", target = "appeng/api/stacks/AEItemKey"), require = 0)
    private static AEItemKey thunderbolt$reuseKey(ItemStack stack, Operation<AEItemKey> original) {
        return KeyConstructionCache.item(stack, original);
    }
}
