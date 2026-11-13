package com.moakiee.thunderbolt.core.terminal;

/** Core-private state attached to AE2 menus; not an addon API. */
public interface TerminalOrderMenu {
    String thunderbolt$orderSession();
    long thunderbolt$nextOrderRequest();
    OrderRequestGate thunderbolt$orderGate();
    TerminalOrderBinding thunderbolt$orderBinding();
    void thunderbolt$orderBinding(TerminalOrderBinding binding);
    boolean thunderbolt$orderRetired();
    void thunderbolt$retireOrder();
}
