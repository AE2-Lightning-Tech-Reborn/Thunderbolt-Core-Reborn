package com.moakiee.thunderbolt.core.storage.cell;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.CellState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IndexedStorageCellUnitReloadTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraftforge.fml.loading.LoadingModList.of(List.of(), List.of(),
                new net.minecraftforge.fml.loading.EarlyLoadingException("test bootstrap", null, List.of()));
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void finiteCellRecomputesCapacityAndTransfersWithoutQuantityChanges() {
        var fixture = new Fixture(false, 24);
        var inv = fixture.inventory;
        assertEquals(100, inv.insert(fixture.key, 100, Actionable.MODULATE, fixture.source));
        assertEquals(21, inv.usedBytes()); // 8 metadata + ceil(100 / 8)
        long modCount = inv.storage().getModCount();

        fixture.type.units = 17;
        assertEquals(14, inv.usedBytes());
        assertEquals(172, inv.insert(fixture.key, 1000, Actionable.SIMULATE, fixture.source));
        assertEquals(modCount, inv.storage().getModCount());
        assertEquals(100, inv.storage().getAmount(fixture.key));
        assertEquals(172, inv.insert(fixture.key, 1000, Actionable.MODULATE, fixture.source));
        assertEquals(24, inv.usedBytes());
        assertEquals(CellState.FULL, inv.getStatus());
        assertEquals(172, inv.extract(fixture.key, 172, Actionable.MODULATE, fixture.source));
        assertEquals(14, inv.usedBytes());

        fixture.type.units = 8;
        assertEquals(21, inv.usedBytes());
        assertEquals(28, inv.insert(fixture.key, 1000, Actionable.SIMULATE, fixture.source));
        assertEquals(100, inv.extract(fixture.key, 100, Actionable.MODULATE, fixture.source));
        fixture.type.units = 4;
        assertEquals(0, inv.usedBytes());
        assertEquals(CellState.EMPTY, inv.getStatus());
    }

    @Test
    void statusAndCapacityDetectReloadBeforeAnyByteQuery() {
        var fixture = new Fixture(false, 21);
        var inv = fixture.inventory;
        inv.insert(fixture.key, 100, Actionable.MODULATE, fixture.source);
        assertEquals(CellState.FULL, inv.getStatus());
        fixture.type.units = 17;
        assertEquals(CellState.NOT_EMPTY, inv.getStatus());
        assertEquals(121, inv.insert(fixture.key, 1000, Actionable.SIMULATE, fixture.source));
        fixture.type.units = 4;
        assertEquals(0, inv.insert(fixture.key, 1, Actionable.SIMULATE, fixture.source));
        assertEquals(CellState.FULL, inv.getStatus());
        assertEquals(33, inv.usedBytes());
    }

    @Test
    void persistRefreshesSummaryWithoutMutationOrSavedDataForBothCellPolicies() {
        for (boolean big : new boolean[]{false, true}) {
            var fixture = new Fixture(big, 24);
            var inv = fixture.inventory;
            inv.insert(fixture.key, 100, Actionable.MODULATE, fixture.source);
            assertEquals(21, fixture.stack.getOrCreateTag().getLong("test:bytes"));
            long modCount = inv.storage().getModCount();
            fixture.type.units = 17;
            inv.persist(); // No usedBytes/status call to mask the persist-only path.
            assertEquals(14, fixture.stack.getOrCreateTag().getLong("test:bytes"));
            assertEquals(1, fixture.stack.getOrCreateTag().getInt("test:types"));
            assertEquals(modCount, inv.storage().getModCount());
            fixture.type.units = 8;
            inv.persist();
            assertEquals(21, fixture.stack.getOrCreateTag().getLong("test:bytes"));
            assertEquals(modCount, inv.storage().getModCount());
        }
    }

    private static final class Fixture {
        final ReloadableType type = new ReloadableType();
        final AEKey key = new ReloadableKey(type);
        final ItemStack stack = new ItemStack(Items.PAPER);
        final IActionSource source = IActionSource.empty();
        final IndexedStorageCellInventory inventory;

        Fixture(boolean big, long capacity) {
            var definition = new IIndexedStorageCellItem() {
                @Override public ResourceLocation storageType(ItemStack stack) {
                    return new ResourceLocation("thunderbolt_test", "unit_reload");
                }
                @Override public ByteTracker createByteTracker(ItemStack stack, IndexedStorage storage) {
                    var tracker = new ByteTracker(storage::getTotalTypes);
                    tracker.configure(8, 10, capacity, 0);
                    return tracker;
                }
                @Override public boolean supportsBigAmounts(ItemStack stack) { return big; }
                @Override public double idleDrain(ItemStack stack) { return 0; }
                @Override public void writeSummary(ItemStack stack, IndexedCellSummary summary) {
                    stack.getOrCreateTag().putLong("test:bytes", summary.usedBytes());
                    stack.getOrCreateTag().putInt("test:types", summary.totalTypes());
                }
            };
            inventory = new IndexedStorageCellInventory(stack, definition, null, null);
        }
    }

    private static final class ReloadableType extends AEKeyType {
        int units = 8;
        ReloadableType() {
            super(new ResourceLocation("thunderbolt_test", "unit_reload"),
                    ReloadableKey.class, Component.literal("reload"));
        }
        @Override public int getAmountPerByte() { return units; }
        @Override public AEKey loadKeyFromTag(CompoundTag tag) { throw new UnsupportedOperationException(); }
        @Override public AEKey readFromPacket(FriendlyByteBuf input) { throw new UnsupportedOperationException(); }
    }

    private static final class ReloadableKey extends AEKey {
        private final ReloadableType type;
        ReloadableKey(ReloadableType type) { this.type = type; }
        @Override public AEKeyType getType() { return type; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public Object getPrimaryKey() { return type; }
        @Override public ResourceLocation getId() { return type.getId(); }
        @Override public CompoundTag toTag() { return new CompoundTag(); }
        @Override public void writeToPacket(FriendlyByteBuf output) {}
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {}
        @Override protected Component computeDisplayName() { return Component.literal("reload"); }
    }
}
