package com.moakiee.thunderbolt.integration.jei;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import com.moakiee.thunderbolt.ThunderboltCore;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** Run the existing GTL handler before terminal/sorting compatibility pre-dispatch. */
@Mod.EventBusSubscriber(modid = "thunderbolt", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TerminalJeiOrderEvents {
    private static Optional<Method> forwarder;

    private TerminalJeiOrderEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.isCanceled() || event.getButton() != 2 || !TerminalJeiOrderClient.isTerminalScreen()
                || TerminalJeiOrderClient.reservesModifiers()
                || !ModList.get().isLoaded("gtlcore") || !ModList.get().isLoaded("jei")) return;
        if (forwarder == null) forwarder = resolveForwarder();
        if (forwarder.isEmpty()) return;
        try {
            // Reuse GTL's actual hover/edit/cheat filtering and the single send/cancel lambda.
            // Once handled, Forge skips its later subscriber and the sorting pre-dispatch.
            forwarder.get().invoke(null, event);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("Cannot invoke the GTL JEI input handler", impossible);
        } catch (InvocationTargetException failure) {
            // A handler might already have sent its request. Never fall back or retry here.
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("GTL JEI input handler failed", failure.getCause());
        }
    }

    private static Optional<Method> resolveForwarder() {
        try {
            var handler = Class.forName("org.gtlcore.gtlcore.client.forge.ForgeClientEventListener");
            // An unsupported GTL ABI must not be forwarded into its legacy inventory scan.
            if (Arrays.stream(handler.getDeclaredMethods()).noneMatch(
                    method -> method.getName().contains("thunderbolt$orderFromCurrentMenu"))) return Optional.empty();
            return Optional.of(handler.getMethod("onJeiWirelessOrderMousePressed", ScreenEvent.MouseButtonPressed.Pre.class));
        } catch (ReflectiveOperationException unavailable) {
            ThunderboltCore.LOGGER.warn("GTL JEI early input forwarding unavailable", unavailable);
            return Optional.empty();
        }
    }
}
