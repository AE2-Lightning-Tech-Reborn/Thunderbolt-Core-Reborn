package com.moakiee.thunderbolt.mixin.ae2.key;

import appeng.api.stacks.AEFluidKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import com.moakiee.thunderbolt.core.keys.FluidKeyComponents;
import net.minecraft.core.component.DataComponentMap;
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
    @WrapOperation(method = "of(Lnet/neoforged/neoforge/fluids/FluidStack;)Lappeng/api/stacks/AEFluidKey;",
            at = @At(value = "NEW", target = "appeng/api/stacks/AEFluidKey"), require = 0)
    private static AEFluidKey thunderbolt$reuseKey(FluidStack stack, Operation<AEFluidKey> original) {
        return KeyConstructionCache.fluid(stack, original);
    }
}
