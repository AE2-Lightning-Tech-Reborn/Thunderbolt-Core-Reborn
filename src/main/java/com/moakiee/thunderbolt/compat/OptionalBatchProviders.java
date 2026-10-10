package com.moakiee.thunderbolt.compat;

import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapters;
import com.moakiee.thunderbolt.compat.extendedaeplus.ExtendedAePlusBatchAdapter;
import com.moakiee.thunderbolt.compat.gtl.GtlCompat;
import com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

/** Installs optional public protocols once during Thunderbolt common setup, with no LT dependency. */
public final class OptionalBatchProviders {
    private OptionalBatchProviders() {}

    public static void register() {
        // GTL owns catalyst/inflated-pattern accounting through its handover.
        if (GtlCompat.isCraftingHandoverActive()) return;
        register("neoeco", NeoEcoFastPathCompat.createAdapter());
        var mods = ModList.get();
        if (mods != null && mods.isLoaded("extendedae_plus")) {
            register("extendedae_plus", ExtendedAePlusBatchAdapter.createAdapter());
        }
    }

    private static void register(String name, BatchProviderAdapter adapter) {
        if (adapter != null) {
            BatchProviderAdapters.register(new ResourceLocation("thunderbolt", name), -100, adapter);
        }
    }
}
