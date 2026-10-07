package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

final class ExactCraftConfirmPacketMixinContractTest {
    @Test
    void wrapTargetsThePacketOwnerConfigureWrite() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/mixin/ae2/crafting/"
                        + "ExactCraftConfirmPacketMixin.java"));

        assertTrue(source.contains(
                "Lappeng/core/sync/packets/CraftConfirmPlanPacket;configureWrite("
                        + "Lnet/minecraft/network/FriendlyByteBuf;)V"));
        assertFalse(source.contains("Lappeng/core/sync/BasePacket;configureWrite("));
    }
}
