package com.moakiee.thunderbolt.compat;

import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapters;
import com.moakiee.thunderbolt.compat.extendedaeplus.ExtendedAePlusBatchAdapter;
import com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathCompat;
import com.moakiee.thunderbolt.compat.useless.UselessBatchCompat;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModList;

/** Installs optional public protocols once during Thunderbolt common setup, with no LT dependency. */
public final class OptionalBatchProviders {
    private OptionalBatchProviders() {}

    public static void register() {
        register("useless", UselessBatchCompat.createAdapter());
        register("neoeco", NeoEcoFastPathCompat.createAdapter());
        var mods = ModList.get();
        if (mods != null && mods.isLoaded("extendedae_plus")) {
            register("extendedae_plus", ExtendedAePlusBatchAdapter.createAdapter());
        }
    }

    private static void register(String name, BatchProviderAdapter adapter) {
        if (adapter != null) {
            BatchProviderAdapters.register(Identifier.fromNamespaceAndPath("thunderbolt", name), -100, adapter);
        }
    }
}
