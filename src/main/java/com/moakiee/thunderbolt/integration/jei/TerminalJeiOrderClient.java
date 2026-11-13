package com.moakiee.thunderbolt.integration.jei;

import java.util.UUID;

import appeng.api.stacks.AEKey;
import appeng.menu.AEBaseMenu;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderBinding;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderMenu;
import com.moakiee.thunderbolt.network.terminal.TerminalOrderPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/** Loaded only through the optional, client-only GTLCore input adapter. */
public final class TerminalJeiOrderClient {
    private TerminalJeiOrderClient() {}

    public static boolean isTerminalScreen() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() == mc.player.containerMenu
                && screen.getMenu() instanceof AEBaseMenu menu && TerminalOrderBinding.supports(menu);
    }

    public static boolean reservesModifiers() {
        return isTerminalScreen() && (Screen.hasShiftDown() || Screen.hasControlDown());
    }

    /** True means the current terminal owns the action, including a failed/unsynced action. */
    public static boolean route(AEKey key) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        // JEI's recipes screen can sit above the terminal while its menu remains active.
        // The active menu still owns that request; never re-scan a different wireless item.
        if (player == null || mc.screen == null || !(player.containerMenu instanceof AEBaseMenu menu)
                || !TerminalOrderBinding.supports(menu)
                || (mc.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() != menu)) return false;
        var state = (TerminalOrderMenu) menu;
        var session = state.thunderbolt$orderSession();
        if (session.isEmpty()) {
            player.displayClientMessage(Component.translatable("gui.thunderbolt.terminal_order.wait"), false);
            return true;
        }
        TerminalOrderPackets.send(new TerminalOrderPackets.Request(menu.containerId,
                UUID.fromString(session), state.thunderbolt$nextOrderRequest(), key));
        return true;
    }
}
