package com.moakiee.thunderbolt.core.storage.cell;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class IndexedCellTestFixtures {
    private IndexedCellTestFixtures() {}

    /** Isolated AE2 15 registry for standalone JUnit; restore it after the owning test class. */
    @SuppressWarnings("unchecked")
    static AutoCloseable openKeyRegistry() {
        try {
            var field = appeng.api.stacks.AEKeyTypesInternal.class.getDeclaredField("registry");
            field.setAccessible(true);
            var previous = field.get(null);
            var constructor = net.minecraftforge.registries.ForgeRegistry.class.getDeclaredConstructor(
                    net.minecraftforge.registries.RegistryManager.class,
                    ResourceLocation.class,
                    net.minecraftforge.registries.RegistryBuilder.class);
            constructor.setAccessible(true);
            var registry = (net.minecraftforge.registries.ForgeRegistry<appeng.api.stacks.AEKeyType>)
                    constructor.newInstance(net.minecraftforge.registries.RegistryManager.ACTIVE,
                            new ResourceLocation("thunderbolt_test", "cell_key_types"),
                            new net.minecraftforge.registries.RegistryBuilder<appeng.api.stacks.AEKeyType>());
            registry.register(appeng.api.stacks.AEKeyType.items().getId(), appeng.api.stacks.AEKeyType.items());
            registry.register(appeng.api.stacks.AEKeyType.fluids().getId(), appeng.api.stacks.AEKeyType.fluids());
            java.util.function.Supplier<net.minecraftforge.registries.IForgeRegistry<appeng.api.stacks.AEKeyType>>
                    supplier = () -> registry;
            field.set(null, supplier);
            return () -> field.set(null, previous);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    static IIndexedStorageCellItem definition() {
        return new IIndexedStorageCellItem() {
            public ResourceLocation storageType(ItemStack stack) {
                return new ResourceLocation("ae2lt", "infinite_cell");
            }
            public boolean supportsBigAmounts(ItemStack stack) { return true; }
            public ByteTracker createByteTracker(ItemStack stack, IndexedStorage storage) {
                var tracker = new ByteTracker(storage::getTotalTypes);
                tracker.configure(8, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
                return tracker;
            }
            public double idleDrain(ItemStack stack) { return 32; }
            public void writeSummary(ItemStack stack, IndexedCellSummary summary) {
                var tag = stack.getOrCreateTag();
                tag.putInt("ae2lt:types", summary.totalTypes());
                tag.putLong("ae2lt:bytes", summary.usedBytes());
            }
        };
    }

}
