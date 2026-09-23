package com.moakiee.thunderbolt.ae2.key;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import appeng.api.stacks.AEItemKey;

class AEItemKeyComponentPatchTest extends com.moakiee.thunderbolt.test.MinecraftComponentsTestBase {

    @Test
    void ignoresDefaultItemComponents() throws Exception {
        var plain = new ItemStack(Items.STONE);

        assertFalse(plain.getComponents().isEmpty(),
                "vanilla defaults make the full component map unsuitable for this check");
        assertTrue(plain.isComponentsPatchEmpty());
        assertFalse(hasComponentPatch(plain));
    }

    @Test
    void detectsAddedComponentValues() throws Exception {
        var named = new ItemStack(Items.STONE);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("marked"));

        assertFalse(named.isComponentsPatchEmpty());
        assertTrue(hasComponentPatch(named));
    }

    @Test
    void detectsRemovedDefaultComponents() throws Exception {
        var changed = new ItemStack(Items.STONE);
        changed.remove(DataComponents.RARITY);

        assertFalse(changed.isComponentsPatchEmpty());
        assertTrue(hasComponentPatch(changed));
    }

    private static boolean hasComponentPatch(ItemStack stack) {
        return AEItemKey.of(stack).hasComponents();
    }
}
