package com.moakiee.thunderbolt.network.terminal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import appeng.api.stacks.AEItemKey;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class TerminalOrderCodecTest {
    private static java.lang.reflect.Field registryField;
    private static Object previousRegistry;
    @BeforeAll static void bootstrap() throws Exception {
        MinecraftTestBootstrap.ensureInitialized();
        registryField = appeng.api.stacks.AEKeyTypesInternal.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        previousRegistry = registryField.get(null);
        var registry = MinecraftTestBootstrap.<appeng.api.stacks.AEKeyType>registry("terminal_order_keys");
        registry.register(appeng.api.stacks.AEKeyType.items().getId(), appeng.api.stacks.AEKeyType.items());
        appeng.api.stacks.AEKeyTypesInternal.setRegistry(() -> registry);
    }
    @AfterAll static void restoreRegistry() throws Exception { registryField.set(null, previousRegistry); }

    @Test void roundTripPreservesSessionRequestAndNbtKey() {
        var stack = new ItemStack(Items.IRON_INGOT);
        stack.getOrCreateTag().putString("variant", "terminal-order");
        var request = new TerminalOrderPackets.Request(17, UUID.randomUUID(), Long.MAX_VALUE, AEItemKey.of(stack));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            request.write(buf);
            assertEquals(request, TerminalOrderPackets.Request.read(buf));
            assertFalse(buf.isReadable());
        } finally { buf.release(); }
    }

    @Test void oversizedKeysAreRejectedBeforeAe2Decoding() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeZero(TerminalOrderPackets.MAX_KEY_BYTES + 30);
            assertThrows(DecoderException.class, () -> TerminalOrderPackets.Request.read(buf));
            assertEquals(0, buf.readerIndex(), "oversized input must not enter the key codec");
        } finally { buf.release(); }
    }

    @Test void malformedEnvelopeCannotReachKeyCodec() {
        for (long sequence : new long[]{0, -1, Long.MIN_VALUE}) {
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeVarInt(1); buf.writeUUID(UUID.randomUUID()); buf.writeLong(sequence);
                assertThrows(DecoderException.class, () -> TerminalOrderPackets.Request.read(buf));
            } finally { buf.release(); }
        }
    }

    @Test void trailingDataAndTruncatedKeysAreRejected() {
        var request = new TerminalOrderPackets.Request(1, UUID.randomUUID(), 1, AEItemKey.of(Items.STONE));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            request.write(buf); buf.writeByte(0);
            assertThrows(DecoderException.class, () -> TerminalOrderPackets.Request.read(buf));
            buf.clear(); buf.writeVarInt(1); buf.writeUUID(UUID.randomUUID()); buf.writeLong(1);
            assertThrows(RuntimeException.class, () -> TerminalOrderPackets.Request.read(buf));
        } finally { buf.release(); }
    }
}
