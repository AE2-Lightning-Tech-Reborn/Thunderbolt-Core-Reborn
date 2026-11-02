package com.moakiee.thunderbolt.mixin.ae2.crafting;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.core.crafting.pattern.PatternInputIdentity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEStonecuttingPattern;

/** Item-only cache entries must not be reused for capability-bearing input variants. */
@Mixin(value = {AECraftingPattern.class, AEStonecuttingPattern.class}, remap = false)
public abstract class AEPatternInputIdentityMixin {
    @WrapOperation(
            method = {"getTestResult", "setTestResult"},
            at = @At(value = "INVOKE", target = "Lappeng/api/stacks/AEItemKey;hasTag()Z"),
            require = 2)
    private boolean thunderbolt$includeCapabilityIdentity(AEItemKey key, Operation<Boolean> original) {
        return original.call(key) || PatternInputIdentity.hasSecondaryData(key);
    }
}
