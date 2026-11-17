package com.moakiee.thunderbolt.core.terminal;

import java.util.UUID;

import appeng.menu.AEBaseMenu;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/** Private envelope on native actions. No addon API or persisted terminal data. */
public final class TerminalOrderActions {
    private static final int MAGIC = 0x54424f41;
    private static final int STAMP_BYTES = 48;

    private TerminalOrderActions() {}

    public record Stamp(int containerId, UUID menu, UUID request, long sequence) {
        public void write(FriendlyByteBuf out) {
            out.writeInt(MAGIC);
            out.writeInt(containerId);
            out.writeUUID(menu);
            out.writeUUID(request);
            out.writeLong(sequence);
        }
    }

    public static Stamp capture(AEBaseMenu menu) {
        var state = (TerminalOrderMenu) menu;
        if (state.thunderbolt$orderRetired() || state.thunderbolt$orderRequestId().isEmpty()
                || state.thunderbolt$orderSession().isEmpty()) return null;
        return new Stamp(menu.containerId, UUID.fromString(state.thunderbolt$orderSession()),
                UUID.fromString(state.thunderbolt$orderRequestId()), state.thunderbolt$nextOrderRequest());
    }

    /** Optional for ordinary menus; a bound receiver always requires a stamp. */
    public static Stamp read(FriendlyByteBuf in) {
        if (in.readableBytes() < 4 || in.getInt(in.readerIndex()) != MAGIC) return null;
        if (in.readableBytes() < STAMP_BYTES) throw new DecoderException("Truncated terminal action stamp");
        in.readInt();
        int container = in.readInt();
        var menu = in.readUUID();
        var request = in.readUUID();
        long sequence = in.readLong();
        if (container < 0 || sequence <= 0) throw new DecoderException("Invalid terminal action stamp");
        return new Stamp(container, menu, request, sequence);
    }

    public static boolean bound(AEBaseMenu menu) {
        var state = (TerminalOrderMenu) menu;
        return state.thunderbolt$orderBinding() != null || state.thunderbolt$orderRetired();
    }

    public static boolean dispatch(AEBaseMenu menu, Stamp stamp, Runnable action) {
        var state = (TerminalOrderMenu) menu;
        var binding = state.thunderbolt$orderBinding();
        if (stamp == null || state.thunderbolt$orderRetired() || binding == null
                || menu.containerId != stamp.containerId()
                || !state.thunderbolt$orderRequestId().equals(stamp.request().toString())
                || !binding.request().equals(stamp.request().toString())
                || !binding.acceptsAction(menu) || state.thunderbolt$orderActionAuthorized()
                || !state.thunderbolt$orderGate().accept(state.thunderbolt$orderSession(),
                        stamp.menu().toString(), stamp.sequence())) return false;
        state.thunderbolt$orderActionAuthorized(true);
        try { action.run(); }
        finally { state.thunderbolt$orderActionAuthorized(false); }
        return true;
    }
}
