package com.moakiee.thunderbolt.mixin.compatfixture;

import java.util.Map;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.extensions.IForgeItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Reproduces the interface-super call shape used by ElementalCraft, without a production dependency. */
@Mixin(value = ItemStack.class, remap = false)
public abstract class ItemStackInterfaceSuperMixin extends CapabilityProvider<ItemStack> implements IForgeItemStack {
    private ItemStackInterfaceSuperMixin(Class<ItemStack> baseClass) {
        super(baseClass);
    }

    @Override
    public int getEnchantmentLevel(Enchantment enchantment) {
        return IForgeItemStack.super.getEnchantmentLevel(enchantment);
    }

    @Override
    public Map<Enchantment, Integer> getAllEnchantments() {
        return IForgeItemStack.super.getAllEnchantments();
    }
}
