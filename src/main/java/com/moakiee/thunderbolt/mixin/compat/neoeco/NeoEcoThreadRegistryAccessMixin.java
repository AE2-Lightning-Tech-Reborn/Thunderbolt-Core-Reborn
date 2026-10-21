package com.moakiee.thunderbolt.mixin.compat.neoeco;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Forge loads block entity NBT before assigning its level. Keep worker custody decodable. */
@Pseudo
@Mixin(targets = "cn.dancingsnow.neoecoae.crafting.execution.worker.ECOCraftingThread", remap = false)
public abstract class NeoEcoThreadRegistryAccessMixin {
    @WrapOperation(
            method = {"serializeNBT()Lnet/minecraft/nbt/CompoundTag;", "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;)V"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;registryAccess()Lnet/minecraft/core/RegistryAccess;",
                    remap = true),
            remap = false,
            require = 0)
    private RegistryAccess thunderbolt$registriesBeforeLevelAssignment(Level level, Operation<RegistryAccess> original) {
        if (level != null) return original.call(level);
        var server = ServerLifecycleHooks.getCurrentServer();
        // The Forge 1.20.1 codec uses static item/key registries; the provider is unused on clients.
        return server == null ? RegistryAccess.EMPTY : server.registryAccess();
    }
}
