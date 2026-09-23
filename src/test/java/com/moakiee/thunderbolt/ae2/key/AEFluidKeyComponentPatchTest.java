package com.moakiee.thunderbolt.ae2.key;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import appeng.api.stacks.AEFluidKey;

class AEFluidKeyComponentPatchTest extends com.moakiee.thunderbolt.test.MinecraftComponentsTestBase {

    @Test
    void ignoresPlainFluids() throws Exception {
        var plain = new FluidStack(Fluids.WATER, 1000);

        assertTrue(plain.getComponents().isEmpty());
        assertTrue(plain.isComponentsPatchEmpty());
        assertFalse(hasComponentPatch(plain));
    }

    @Test
    void detectsAddedComponentValues() throws Exception {
        var named = new FluidStack(Fluids.WATER, 1000);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("marked"));

        assertFalse(named.isComponentsPatchEmpty());
        assertTrue(hasComponentPatch(named));
    }

    private static boolean hasComponentPatch(FluidStack stack) {
        return AEFluidKey.of(stack).hasComponents();
    }
}
