package com.moakiee.thunderbolt.mixin.ae2.crafting;

import appeng.menu.AEBaseMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocator;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderBinding;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = MenuOpener.class, remap = false)
public abstract class TerminalOrderMenuOpenerMixin {
    @WrapMethod(method = "open(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/world/entity/player/Player;Lappeng/menu/locator/MenuLocator;Z)Z")
    private static boolean thunderbolt$carryOrder(MenuType<?> type, Player player, MenuLocator locator,
            boolean returning, Operation<Boolean> original) {
        if (!(player.containerMenu instanceof AEBaseMenu previous) || previous.isClientSide())
            return original.call(type, player, locator, returning);
        var previousState = (TerminalOrderMenu) previous;
        if (previousState.thunderbolt$orderRetired()) return false;
        var binding = previousState.thunderbolt$orderBinding();
        if (binding == null) return original.call(type, player, locator, returning);
        if (!binding.valid(previous) || !binding.acceptsLocator(locator)) {
            TerminalOrderBinding.reject(previous);
            return false;
        }
        boolean opened = original.call(type, player, locator, returning);
        if (opened && player.containerMenu instanceof AEBaseMenu next && next != previous) {
            if (next instanceof CraftAmountMenu || next instanceof CraftConfirmMenu) {
                ((TerminalOrderMenu) next).thunderbolt$orderBinding(binding);
                if (!binding.valid(next)) {
                    TerminalOrderBinding.reject(next);
                    return false;
                }
            }
            // Returning to the terminal starts a new session. Never carry a completed order
            // into unrelated screens or retain a global player/grid cache.
            previousState.thunderbolt$retireOrder();
        }
        return opened;
    }
}
