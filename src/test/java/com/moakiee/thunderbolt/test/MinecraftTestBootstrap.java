package com.moakiee.thunderbolt.test;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.fml.loading.EarlyLoadingException;
import net.minecraftforge.fml.loading.LoadingModList;

/** Initializes the Forge 1.19.2 registries once before a plain JUnit test touches Level or Items. */
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
        // Plain JUnit has no ModLauncher transformer to add NetworkEvent's synthetic constructor.
        // Seed its listener list before vanilla bootstrap initializes Forge's handshake channels.
        try {
            var field = net.minecraftforge.eventbus.api.EventListenerHelper.class.getDeclaredField("listeners");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var listeners = (net.minecraftforge.eventbus.LockHelper<Class<?>, net.minecraftforge.eventbus.ListenerList>) field.get(null);
            listeners.computeIfAbsent(net.minecraftforge.network.NetworkEvent.class,
                    () -> new net.minecraftforge.eventbus.ListenerList(
                            net.minecraftforge.eventbus.api.EventListenerHelper.getListenerList(net.minecraftforge.eventbus.api.Event.class)));
            for (var nested : net.minecraftforge.network.NetworkEvent.class.getDeclaredClasses()) {
                if (net.minecraftforge.eventbus.api.Event.class.isAssignableFrom(nested)) {
                    listeners.computeIfAbsent(nested, () -> new net.minecraftforge.eventbus.ListenerList(
                            net.minecraftforge.eventbus.api.EventListenerHelper.getListenerList(net.minecraftforge.network.NetworkEvent.class)));
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not initialize the untransformed Forge test event bus", error);
        }
        Bootstrap.bootStrap();
        initialized = true;
    }
}
