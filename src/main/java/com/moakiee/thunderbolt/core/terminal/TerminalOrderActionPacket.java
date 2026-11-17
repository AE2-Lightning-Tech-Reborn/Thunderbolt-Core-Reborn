package com.moakiee.thunderbolt.core.terminal;

/** Private packet state used by the optional native quantity adapters. */
public interface TerminalOrderActionPacket {
    TerminalOrderActions.Stamp thunderbolt$actionStamp();
    void thunderbolt$actionStamp(TerminalOrderActions.Stamp stamp);

    static TerminalOrderActions.Stamp captureClient() {
        return net.minecraftforge.fml.DistExecutor.unsafeRunForDist(
                () -> com.moakiee.thunderbolt.integration.jei.TerminalOrderActionClient::capture,
                () -> () -> null);
    }
}
