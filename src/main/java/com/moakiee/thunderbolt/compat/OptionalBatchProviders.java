package com.moakiee.thunderbolt.compat;

import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapters;
import com.moakiee.thunderbolt.compat.extendedaeplus.ExtendedAePlusBatchAdapter;
import com.moakiee.thunderbolt.compat.gtl.GtlCompat;
import com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathCompat;
import com.moakiee.thunderbolt.compat.useless.UselessBatchCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Installs optional public protocols once during Thunderbolt common setup, with no LT dependency. */
public final class OptionalBatchProviders {
    private OptionalBatchProviders() {}

    public static void register() {
        // GTL owns catalyst/inflated-pattern accounting through its handover.
        if (GtlCompat.isCraftingHandoverActive()) return;
        var mods = ModList.get();
        register(id -> mods != null && mods.isLoaded(id),
                NeoEcoFastPathCompat::createAdapter,
                ExtendedAePlusBatchAdapter::createAdapter,
                UselessBatchCompat::createAdapter);
    }

    static void register(Predicate<String> isLoaded,
            Supplier<BatchProviderAdapter> neoEco, Supplier<BatchProviderAdapter> extendedAePlus,
            Supplier<BatchProviderAdapter> useless) {
        if (GtlCompat.standDown(isLoaded)) return;
        registerIfLoaded(isLoaded, "useless_mod", "useless", useless);
        registerIfLoaded(isLoaded, "neoecoae", "neoecoae", neoEco);
        registerIfLoaded(isLoaded, "extendedae_plus", "extendedae_plus", extendedAePlus);
    }

    private static void registerIfLoaded(Predicate<String> isLoaded, String modId, String name,
            Supplier<BatchProviderAdapter> factory) {
        if (isLoaded.test(modId)) register(name, factory.get());
    }

    private static void register(String name, BatchProviderAdapter adapter) {
        if (adapter != null) {
            BatchProviderAdapters.register(new ResourceLocation("thunderbolt", name), -100, adapter);
        }
    }
}
