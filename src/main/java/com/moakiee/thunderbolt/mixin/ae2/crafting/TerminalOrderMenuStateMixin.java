package com.moakiee.thunderbolt.mixin.ae2.crafting;

import java.util.UUID;

import appeng.menu.AEBaseMenu;
import appeng.menu.guisync.GuiSync;
import com.moakiee.thunderbolt.core.terminal.OrderRequestGate;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderBinding;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AEBaseMenu.class, remap = false)
public abstract class TerminalOrderMenuStateMixin implements TerminalOrderMenu {
    @Unique @GuiSync(30_010) public String thunderbolt$orderSession = "";
    @Unique private long thunderbolt$orderSequence;
    @Unique private final OrderRequestGate thunderbolt$orderGate = new OrderRequestGate();
    @Unique private TerminalOrderBinding thunderbolt$orderBinding;
    @Unique private boolean thunderbolt$orderRetired;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void thunderbolt$initializeSession(CallbackInfo ci) {
        if (!((AEBaseMenu) (Object) this).isClientSide()) thunderbolt$orderSession = UUID.randomUUID().toString();
    }

    @Inject(method = "broadcastChanges", at = @At("HEAD"), remap = true)
    private void thunderbolt$validateOrder(CallbackInfo ci) {
        var menu = (AEBaseMenu) (Object) this;
        if (!menu.isClientSide() && thunderbolt$orderBinding != null && menu.isValidMenu()
                && !thunderbolt$orderBinding.valid(menu)) TerminalOrderBinding.reject(menu);
    }

    @Override public String thunderbolt$orderSession() { return thunderbolt$orderSession; }
    @Override public long thunderbolt$nextOrderRequest() { return ++thunderbolt$orderSequence; }
    @Override public OrderRequestGate thunderbolt$orderGate() { return thunderbolt$orderGate; }
    @Override public TerminalOrderBinding thunderbolt$orderBinding() { return thunderbolt$orderBinding; }
    @Override public void thunderbolt$orderBinding(TerminalOrderBinding binding) { thunderbolt$orderBinding = binding; }
    @Override public boolean thunderbolt$orderRetired() { return thunderbolt$orderRetired; }
    @Override public void thunderbolt$retireOrder() {
        thunderbolt$orderBinding = null;
        thunderbolt$orderRetired = true;
    }
}
