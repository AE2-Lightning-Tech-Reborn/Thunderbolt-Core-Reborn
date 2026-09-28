package com.moakiee.thunderbolt.compat.neoeco;

import org.jetbrains.annotations.Nullable;

import net.neoforged.fml.ModList;

import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** Keeps NeoECO API types outside the class-loading path when the optional mod is absent. */
public final class NeoEcoFastPathCompat {
    private static final String ADAPTER_CLASS =
            "com.moakiee.thunderbolt.compat.neoeco.NeoEcoFastPathBatchAdapter";

    private NeoEcoFastPathCompat() {
    }

    @Nullable
    public static BatchProviderAdapter createAdapter() {
        // Plain unit tests do not initialize the NeoForge mod list.
        var modList = ModList.get();
        if (modList == null || !modList.isLoaded("neoecoae")) {
            return null;
        }
        return loadAdapter(NeoEcoFastPathCompat.class.getClassLoader());
    }

    static @Nullable BatchProviderAdapter loadAdapter(ClassLoader loader) {
        try {
            // Constructing the adapter alone does not force lazy API references to link.
            // Probe before registration so old NeoECO releases retain ordinary dispatch.
            Class.forName("cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider", false, loader);
            var facade = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade", false, loader);
            var prepared = facade.getMethod("prepareAllocated", ICraftingProvider.class, IPatternDetails.class,
                    KeyCounter[].class, long.class, Level.class, UUID.class).getReturnType();
            var energy = Class.forName("cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade$EnergyAccount", false, loader);
            if (prepared.getMethod("craftCount").getReturnType() != long.class
                    || prepared.getMethod("submit", energy).getReturnType() != boolean.class) {
                throw new NoSuchMethodException("Incompatible allocated FastPath contract");
            }
            return (BatchProviderAdapter) Class.forName(ADAPTER_CLASS, true, loader)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            appeng.core.AELog.warn(
                    "[thunderbolt] NeoECO FastPath API is unavailable; CPUs will use ordinary provider dispatch. %s",
                    unavailable);
            return null;
        }
    }
}
