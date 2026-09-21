package com.moakiee.thunderbolt.test;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.fml.loading.EarlyLoadingException;
import net.minecraftforge.fml.loading.LoadingModList;

/** Initializes the Forge 1.20.1 registries once before a plain JUnit test touches Level or Items. */
public final class MinecraftTestBootstrap {
    private static boolean initialized;

    @SuppressWarnings("unchecked")
    public static <T> net.minecraftforge.registries.IForgeRegistry<T> registry(String name) {
        ensureInitialized();
        try {
            var builder = new net.minecraftforge.registries.RegistryBuilder<T>()
                    .setName(new net.minecraft.resources.ResourceLocation("thunderbolt_test", name));
            var create = builder.getClass().getDeclaredMethod("create");
            create.setAccessible(true);
            return (net.minecraftforge.registries.IForgeRegistry<T>) create.invoke(builder);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private MinecraftTestBootstrap() {
    }

    public static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        LoadingModList.of(List.of(), List.of(),
                new EarlyLoadingException("test bootstrap", null, List.of()));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        initialized = true;
    }
}
