package com.moakiee.thunderbolt.core.storage.cell;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

final class IndexedCellTestFixtures {
    private IndexedCellTestFixtures() {}

    static IIndexedStorageCellItem definition() {
        return new IIndexedStorageCellItem() {
            public ResourceLocation storageType(ItemStack stack) {
                return ResourceLocation.fromNamespaceAndPath("ae2lt", "infinite_cell");
            }
            public boolean supportsBigAmounts(ItemStack stack) { return true; }
            public ByteTracker createByteTracker(ItemStack stack, IndexedStorage storage) {
                var tracker = new ByteTracker(storage::getTotalTypes);
                tracker.configure(8, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
                return tracker;
            }
            public double idleDrain(ItemStack stack) { return 32; }
            public void writeSummary(ItemStack stack, IndexedCellSummary summary) {
                CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                    tag.putInt("ae2lt:types", summary.totalTypes());
                    tag.putLong("ae2lt:bytes", summary.usedBytes());
                });
            }
        };
    }

}
