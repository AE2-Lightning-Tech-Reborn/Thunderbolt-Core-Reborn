package com.moakiee.thunderbolt.integration.jei;

import appeng.menu.AEBaseMenu;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderActions;
import net.minecraft.client.Minecraft;

/** Only invoked on the physical client's main thread while constructing a native packet. */
public final class TerminalOrderActionClient {
    private TerminalOrderActionClient() {}

    public static TerminalOrderActions.Stamp capture() {
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || minecraft.player == null
                || !(minecraft.player.containerMenu instanceof AEBaseMenu menu)) return null;
        return TerminalOrderActions.capture(menu);
    }
}
