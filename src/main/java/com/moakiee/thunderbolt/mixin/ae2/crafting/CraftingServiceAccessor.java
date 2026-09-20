package com.moakiee.thunderbolt.mixin.ae2.crafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.moakiee.thunderbolt.core.crafting.support.CraftingProviderChangeTracker;
import com.moakiee.thunderbolt.core.crafting.support.CraftingProviderRevision;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceAccessor
        implements CraftingProviderChangeTracker.ProviderStateView, CraftingProviderRevision {
    @Accessor("craftingProviders")
    public abstract NetworkCraftingProviders thunderbolt$getCraftingProviders();

    @Override
    public long thunderbolt$getCraftingProvidersLastModifiedOnTick() {
        return thunderbolt$getCraftingProviders().getLastModifiedOnTick();
    }

    @Override
    public long thunderbolt$getCraftingProviderRevision() {
        return ((CraftingProviderRevision) thunderbolt$getCraftingProviders())
                .thunderbolt$getCraftingProviderRevision();
    }
}
