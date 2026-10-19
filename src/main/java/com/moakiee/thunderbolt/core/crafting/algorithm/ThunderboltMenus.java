package com.moakiee.thunderbolt.core.crafting.algorithm;

import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.core.crafting.algorithm.menu.CraftingAlgorithmProviderMenu;

public final class ThunderboltMenus {
    public static final DeferredRegister<MenuType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, ThunderboltCore.MODID);

    public static final RegistryObject<MenuType<CraftingAlgorithmProviderMenu>>
            CRAFTING_ALGORITHM_PROVIDER = TYPES.register(
                    "crafting_algorithm_provider",
                    () -> CraftingAlgorithmProviderMenu.TYPE);

    private ThunderboltMenus() {
    }
}
