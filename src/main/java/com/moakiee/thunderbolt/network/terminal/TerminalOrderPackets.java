package com.moakiee.thunderbolt.network.terminal;

import java.util.UUID;
import java.util.function.Supplier;

import appeng.api.stacks.AEKey;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.crafting.CraftAmountMenu;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderBinding;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderMenu;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

@Mod.EventBusSubscriber(modid = "thunderbolt", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class TerminalOrderPackets {
    public static final int MAX_KEY_BYTES = 16 * 1024;
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation("thunderbolt", "terminal_order"))
            .networkProtocolVersion(() -> "1")
            .clientAcceptedVersions("1"::equals).serverAcceptedVersions("1"::equals).simpleChannel();

    private TerminalOrderPackets() {}

    @SubscribeEvent
    public static void register(FMLCommonSetupEvent event) {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::write).decoder(Request::read)
                .consumerMainThread(TerminalOrderPackets::handle).add();
    }

    public static void send(Request request) { CHANNEL.sendToServer(request); }

    public record Request(int containerId, UUID session, long requestId, AEKey key) {
        public void write(FriendlyByteBuf out) {
            out.writeVarInt(containerId);
            out.writeUUID(session);
            out.writeLong(requestId);
            AEKey.writeKey(out, key);
        }

        public static Request read(FriendlyByteBuf in) {
            // Bound the entire key payload before invoking AE2's key/NBT codecs.
            if (in.readableBytes() > MAX_KEY_BYTES + 29) throw new DecoderException("Terminal order too large");
            int container = in.readVarInt();
            UUID session = in.readUUID();
            long request = in.readLong();
            if (container < 0 || request <= 0 || in.readableBytes() > MAX_KEY_BYTES)
                throw new DecoderException("Invalid terminal order envelope");
            var key = AEKey.readKey(in);
            if (key == null || in.isReadable()) throw new DecoderException("Invalid terminal order key");
            return new Request(container, session, request, key);
        }
    }

    private static void handle(Request request, Supplier<NetworkEvent.Context> context) {
        var ctx = context.get();
        var player = ctx.getSender();
        ctx.setPacketHandled(true);
        if (player == null || !(player.containerMenu instanceof MEStorageMenu menu)
                || menu.containerId != request.containerId() || !TerminalOrderBinding.supports(menu)) return;
        var state = (TerminalOrderMenu) menu;
        if (!state.thunderbolt$orderGate().accept(state.thunderbolt$orderSession(),
                request.session().toString(), request.requestId())) return;
        var binding = TerminalOrderBinding.capture(player, menu);
        if (binding == null) {
            TerminalOrderBinding.reject(menu);
            return;
        }
        if (!binding.grid().getCraftingService().isCraftable(request.key())) {
            player.displayClientMessage(Component.translatable("gui.thunderbolt.terminal_order.not_craftable"), false);
            return;
        }
        state.thunderbolt$orderBinding(binding);
        CraftAmountMenu.open(player, menu.getLocator(), request.key(), 1);
        if (!(player.containerMenu instanceof CraftAmountMenu amount)
                || ((TerminalOrderMenu) amount).thunderbolt$orderBinding() != binding || !binding.valid(amount)) {
            binding.invalidate();
            if (player.containerMenu instanceof appeng.menu.AEBaseMenu current) TerminalOrderBinding.reject(current);
            else player.displayClientMessage(Component.translatable("gui.thunderbolt.terminal_order.invalid"), false);
        }
    }
}
