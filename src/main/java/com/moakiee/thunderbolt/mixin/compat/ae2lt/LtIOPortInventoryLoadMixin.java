package com.moakiee.thunderbolt.mixin.compat.ae2lt;

import appeng.util.inv.AppEngInternalInventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moakiee.thunderbolt.compat.ae2lt.InventoryLoadSnapshot;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** AE2-GTO's sparse reader otherwise retains old matrices and filter items on repeated loads. */
@Pseudo
@Mixin(targets = "com.moakiee.ae2lt.blockentity.OverloadedIOPortBlockEntity", remap = false)
public abstract class LtIOPortInventoryLoadMixin {
    @WrapOperation(method = "loadTag", at = @At(value = "INVOKE",
            target = "Lappeng/util/inv/AppEngInternalInventory;readFromNBT(Lnet/minecraft/nbt/CompoundTag;Ljava/lang/String;)V"),
            require = 2, allow = 2)
    private void thunderbolt$replaceInventorySnapshot(AppEngInternalInventory inventory,
            CompoundTag tag, String key, Operation<Void> original) {
        original.call(inventory, InventoryLoadSnapshot.withEmptySlots(tag, key, inventory.size()), key);
    }
}
