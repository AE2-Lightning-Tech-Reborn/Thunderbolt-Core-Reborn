package com.moakiee.thunderbolt.compat.gto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Client key policy shared by GTUtil's guarded client branch and Screen's client-only Mixin. */
public final class ClientModifierKeys {
    private ClientModifierKeys() {
    }

    public static boolean canPoll() {
        var client = Minecraft.getInstance();
        return client != null && client.isSameThread() && client.isRunning();
    }

    public static boolean isShiftDown() {
        return canPoll() && Screen.hasShiftDown();
    }

    public static boolean isCtrlDown() {
        return canPoll() && Screen.hasControlDown();
    }

    public static boolean isAltDown() {
        return canPoll() && Screen.hasAltDown();
    }
}
