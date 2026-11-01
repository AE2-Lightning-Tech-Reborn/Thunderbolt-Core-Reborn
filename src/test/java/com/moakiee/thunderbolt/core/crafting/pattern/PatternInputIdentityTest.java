package com.moakiee.thunderbolt.core.crafting.pattern;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PatternInputIdentityTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.ensureInitialized(); }

    @Test void ordinaryItemsRetainTheItemOnlyCacheRegardlessOfCount() {
        var one = AEItemKey.of(new ItemStack(Items.STONE));
        var many = AEItemKey.of(new ItemStack(Items.STONE, 64));
        assertEquals(one, many);
        assertFalse(PatternInputIdentity.hasSecondaryData(one));
        assertFalse(PatternInputIdentity.hasSecondaryData(many));
    }

    @Test void itemNbtIsPartOfIdentityAndIsSnapshotted() {
        var tag = state(7);
        var key = AEItemKey.of(Items.STONE, tag);
        tag.putInt("state", 8);
        assertTrue(PatternInputIdentity.hasSecondaryData(key));
        assertEquals(AEItemKey.of(Items.STONE, state(7)), key);
        assertNotEquals(AEItemKey.of(Items.STONE, tag), key);
        assertEquals(AEItemKey.of(Items.STONE), key.dropSecondary());
    }

    @Test void gtoDoesNotInventIdentityForCapsAbsentFromItsKeyContract() {
        // AE2-GTO 15.2610.31 stores only item and internedTag, unlike ordinary Forge AE2.
        // Its parser deliberately ignores an independent caps field; the cache follows the
        // actual key rather than inventing a different identity after stock lookup.
        var plain = AEItemKey.of(Items.STONE);
        var serialized = plain.toTag();
        var caps = new CompoundTag();
        caps.put("thunderbolt_test:state", state(7));
        serialized.put("caps", caps);
        var parsed = AEItemKey.fromTag(serialized);
        assertSame(plain, parsed);
        assertFalse(parsed.hasTag());
        assertFalse(parsed.toTag().contains("caps"));
        assertFalse(PatternInputIdentity.hasSecondaryData(parsed));
        assertSame(plain, parsed.dropSecondary());
    }

    @Test void fluidNbtAlreadyPreservesVariantIdentityAndIgnoresAmount() {
        var plain = AEFluidKey.of(Fluids.WATER);
        assertFalse(plain.hasTag());
        var stack = new FluidStack(Fluids.WATER, 1);
        stack.setTag(state(7));
        var key = AEFluidKey.of(stack);
        stack.setAmount(1000);
        assertEquals(key, AEFluidKey.of(stack));
        assertTrue(key.hasTag());
        assertNotEquals(plain, key);
        stack.getTag().putInt("state", 8);
        assertNotEquals(key, AEFluidKey.of(stack));
        assertEquals(7, key.toTag().getCompound("tag").getInt("state"));
        assertEquals(plain, key.dropSecondary());
    }

    private static CompoundTag state(int value) {
        var tag = new CompoundTag();
        tag.putInt("state", value);
        return tag;
    }
}
