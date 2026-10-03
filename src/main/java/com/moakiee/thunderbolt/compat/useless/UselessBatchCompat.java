package com.moakiee.thunderbolt.compat.useless;

import appeng.api.networking.crafting.ICraftingProvider;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/** Isolates optional API classes, including installations of Useless predating the API. */
public final class UselessBatchCompat {
    private UselessBatchCompat() {
    }

    public static @Nullable BatchProviderResolver createAdapter() {
        var modList = ModList.get();
        if (modList == null || !modList.isLoaded("useless_mod")) return null;
        var loader = UselessBatchCompat.class.getClassLoader();
        var bigint = loadAdapter(loader);
        var scaled = UselessScaledBatchAdapter.loadAdapter(loader);
        if (bigint == null) return scaled;
        if (scaled == null) return bigint;
        return new BatchProviderResolver() {
            @Override public boolean cacheResolutionAcrossTicks() { return true; }
            @Override public IBatchCraftingProvider resolve(ICraftingProvider provider) {
                var endpoint = bigint.resolve(provider);
                return endpoint != null ? endpoint : scaled.resolve(provider);
            }
        };
    }

    static @Nullable BatchProviderResolver loadAdapter(ClassLoader loader) {
        try {
            return new UselessBatchAdapter(new UselessBatchApi(loader));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }
}
