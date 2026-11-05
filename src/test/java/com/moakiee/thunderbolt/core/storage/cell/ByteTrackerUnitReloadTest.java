package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ByteTrackerUnitReloadTest {
    private static final class ReloadableType extends AEKeyType {
        int units = 8;

        ReloadableType(int id) {
            super(new ResourceLocation("test:reload_" + id), AEItemKey.class, Component.literal("reload"));
        }

        @Override public int getAmountPerByte() { return units; }
        @Override public AEKey loadKeyFromTag(net.minecraft.nbt.CompoundTag tag) { throw new UnsupportedOperationException(); }
        @Override public AEKey readFromPacket(FriendlyByteBuf input) { throw new UnsupportedOperationException(); }
    }

    @Test
    void everyLiveChannelDetectsReloadsAcrossGrowthAndSlotRemoval() {
        var tracker = new ByteTracker(() -> 9);
        tracker.configure(8, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        var types = new ReloadableType[9];
        assertTrue(tracker.hasCurrentUnits());
        for (int i = 0; i < types.length; i++) {
            types[i] = new ReloadableType(i);
            tracker.onInsert(types[i], 100, true);
            assertReloadDetected(tracker, types, 0, i + 1);
        }
        for (int i = 0; i < types.length; i++) {
            tracker.onExtract(types[i], 100, true);
            assertReloadDetected(tracker, types, i + 1, types.length);
        }
        assertTrue(tracker.hasCurrentUnits());
    }

    private static void assertReloadDetected(ByteTracker tracker, ReloadableType[] types, int from, int to) {
        assertTrue(tracker.hasCurrentUnits());
        for (int i = from; i < to; i++) {
            types[i].units = 17;
            assertFalse(tracker.hasCurrentUnits(), "Missed unit reload for channel " + i);
            types[i].units = 8;
            assertTrue(tracker.hasCurrentUnits());
        }
    }
}
