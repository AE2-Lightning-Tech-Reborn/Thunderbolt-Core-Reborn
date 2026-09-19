package com.moakiee.thunderbolt.mixin.ae2.key;

import appeng.api.stacks.AEFluidKey;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import com.moakiee.thunderbolt.core.keys.FluidKeyComponents;
import com.moakiee.thunderbolt.core.keys.FluidKeyCacheOwner;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AEFluidKey.class, remap = false)
abstract class AEFluidKeyConstructionMixin implements FluidKeyComponents {
    @Shadow @Final private FluidStack stack;

    @Override
    public DataComponentMap thunderbolt$components() { return stack.getComponents(); }

    @WrapMethod(method = "of(Lnet/neoforged/neoforge/fluids/FluidStack;)Lappeng/api/stacks/AEFluidKey;")
    private static AEFluidKey thunderbolt$cachedFluidKey(FluidStack source, Operation<AEFluidKey> original) {
        // getFluid maps zero/negative amounts to EMPTY, which never has a cached key.
        var plain = ((FluidKeyCacheOwner) (Object) source.getFluid()).thunderbolt$plainFluidKey();
        if (plain != null && KeyConstructionCache.isPlainFluidStack(source)) return plain;
        var cached = KeyConstructionCache.findFluid(source);
        return cached != null ? cached : original.call(source);
    }

    @WrapMethod(method = "of(Lnet/minecraft/world/level/material/Fluid;)Lappeng/api/stacks/AEFluidKey;")
    private static AEFluidKey thunderbolt$cachedFluid(Fluid source, Operation<AEFluidKey> original) {
        var cached = ((FluidKeyCacheOwner) (Object) source).thunderbolt$plainFluidKey();
        return cached != null ? cached : original.call(source);
    }

    @WrapOperation(method = "of(Lnet/neoforged/neoforge/fluids/FluidStack;)Lappeng/api/stacks/AEFluidKey;",
            at = @At(value = "NEW", target = "appeng/api/stacks/AEFluidKey"), require = 0)
    private static AEFluidKey thunderbolt$reuseKey(FluidStack stack, Operation<AEFluidKey> original) {
        return KeyConstructionCache.fluid(stack, original);
    }
}
