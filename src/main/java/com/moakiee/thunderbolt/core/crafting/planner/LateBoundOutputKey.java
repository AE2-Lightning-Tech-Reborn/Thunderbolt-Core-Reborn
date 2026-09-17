package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.List;
import java.util.Objects;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Calculation-local resource for an output whose components are only known at execution time.
 * It has no physical stock and can feed only inputs with a declared same-id closure. Keeping it
 * separate from the advertised concrete key prevents a fuzzy producer from paying exact demand.
 * This key must be projected back to its catalog entry before anything leaves the planner.
 */
final class LateBoundOutputKey extends AEKey {
    private final AEKey catalogKey;

    LateBoundOutputKey(AEKey catalogKey) {
        this.catalogKey = Objects.requireNonNull(catalogKey);
        if (catalogKey instanceof LateBoundOutputKey) throw new IllegalArgumentException("nested key");
    }

    static AEKey physical(AEKey key) {
        return key instanceof LateBoundOutputKey late ? late.catalogKey : key;
    }

    @Override public AEKeyType getType() { return catalogKey.getType(); }
    @Override public AEKey dropSecondary() { return new LateBoundOutputKey(catalogKey.dropSecondary()); }
    @Override public Object getPrimaryKey() { return catalogKey.getPrimaryKey(); }
    @Override public ResourceLocation getId() { return catalogKey.getId(); }
    @Override public boolean hasComponents() { return catalogKey.hasComponents(); }
    @Override protected Component computeDisplayName() { return catalogKey.getDisplayName(); }
    @Override public CompoundTag toTag(HolderLookup.Provider registries) {
        throw new IllegalStateException("planner-only late-bound output escaped into serialization");
    }
    @Override public void writeToPacket(RegistryFriendlyByteBuf data) {
        throw new IllegalStateException("planner-only late-bound output escaped into a packet");
    }
    @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        throw new IllegalStateException("planner-only late-bound output escaped into the world");
    }
    @Override public boolean equals(Object other) {
        return other instanceof LateBoundOutputKey late && catalogKey.equals(late.catalogKey);
    }
    @Override public int hashCode() { return 31 * catalogKey.hashCode() + 1; }
    @Override public String toString() { return "late-bound(" + catalogKey + ")"; }
}
