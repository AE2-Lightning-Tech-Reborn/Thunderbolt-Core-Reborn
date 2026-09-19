package com.moakiee.thunderbolt.mixin.ae2.crafting;

import appeng.me.service.helpers.NetworkCraftingProviders;
import com.moakiee.thunderbolt.core.crafting.support.CraftingProviderRevision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AE2 calls this mutation hook after mounting or unmounting node and global providers. */
@Mixin(value = NetworkCraftingProviders.class, remap = false)
public abstract class NetworkCraftingProvidersRevisionMixin implements CraftingProviderRevision {
    @Unique
    private long thunderbolt$providerRevision;

    @Inject(method = "setLastModifiedOnTick", at = @At("RETURN"))
    private void thunderbolt$changed(CallbackInfo ci) {
        thunderbolt$providerRevision++;
    }

    @Override
    public long thunderbolt$getCraftingProviderRevision() {
        return thunderbolt$providerRevision;
    }
}
