package com.moakiee.thunderbolt.core.terminal;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.helpers.WirelessTerminalMenuHost;
import appeng.menu.AEBaseMenu;
import appeng.menu.locator.MenuLocator;
import appeng.menu.me.common.MEStorageMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Server-only provenance, retained only by the current quantity/confirmation menu. */
public final class TerminalOrderBinding {
    private final ServerPlayer player;
    private final Level level;
    private final MenuLocator locator;
    private final ItemStack item;
    private final Class<?> hostType;
    private final IGrid grid;
    private final IActionSource source;
    private boolean invalid;
    private boolean submitting;

    private TerminalOrderBinding(ServerPlayer player, MEStorageMenu menu, ItemMenuHost host, IGrid grid) {
        this.player = player;
        this.level = player.level();
        this.locator = menu.getLocator();
        this.item = host.getItemStack();
        this.hostType = host.getClass();
        this.grid = grid;
        this.source = menu.getActionSource();
    }

    public static boolean supports(AEBaseMenu menu) {
        if (!(menu instanceof MEStorageMenu)) return false;
        var id = BuiltInRegistries.MENU.getKey(menu.getType());
        return id != null && id.getNamespace().equals("ae2lt")
                && (id.getPath().equals("wireless_tianshu_crafting_terminal")
                || id.getPath().equals("wireless_tianshu_pattern_encoding_terminal"));
    }

    public static TerminalOrderBinding capture(ServerPlayer player, MEStorageMenu menu) {
        if (player.containerMenu != menu || !supports(menu) || !menu.isPowered()
                || !(menu.getHost() instanceof ItemMenuHost host)
                || !(host instanceof IActionHost actionHost) || menu.getLocator() == null) return null;
        var hostNode = actionHost.getActionableNode();
        var menuNode = menu.getNetworkNode();
        var grid = grid(hostNode);
        if (grid == null || (menuNode != null && grid(menuNode) != grid)) return null;
        var binding = new TerminalOrderBinding(player, menu, host, grid);
        return binding.valid(menu) ? binding : null;
    }

    public IActionSource source() { return source; }
    public IGrid grid() { return grid; }
    public MenuLocator locator() { return locator; }

    public boolean valid(AEBaseMenu menu) {
        if (invalid || player.containerMenu != menu || menu.getPlayer() != player
                || player.level() != level || !player.isAlive() || player.hasDisconnected()
                || !menu.isValidMenu() || !menu.stillValid(player)
                || source.player().orElse(null) != player || menu.getLocator() == null) return false;
        if (menu.getLocator() != locator && !acceptsLocator(menu.getLocator())) return false;
        if (!(menu.getTarget() instanceof ItemMenuHost host) || !validHost(host)) return false;
        // Resolve the *same* opaque locator, including accessory locators. A new host is normal;
        // a replaced physical stack, selected module, routed network or missing module is not.
        var fresh = locator.locate(player, ItemMenuHost.class);
        return fresh != null && validHost(fresh)
                && (!(menu instanceof MEStorageMenu storage)
                || (storage.isPowered() && (storage.getNetworkNode() == null
                || grid(storage.getNetworkNode()) == grid)));
    }

    /** Native wireless return callbacks can reconstruct an equivalent opaque locator. */
    public boolean acceptsLocator(MenuLocator candidate) {
        if (candidate == null || invalid) return false;
        var original = locator.locate(player, ItemMenuHost.class);
        var resolved = candidate == locator ? original : candidate.locate(player, ItemMenuHost.class);
        // Keep checking the original anchor as well: moving/replacing the stack cannot
        // authorize a different slot/accessory merely by supplying a new locator.
        return original != null && resolved != null && validHost(original) && validHost(resolved);
    }

    private boolean validHost(ItemMenuHost host) {
        if (host.getClass() != hostType || host.getPlayer() != player || host.getItemStack() != item
                || item.isEmpty() || !(host instanceof IActionHost actionHost)) return false;
        var node = actionHost.getActionableNode();
        if (grid(node) != grid || !node.isActive()) return false;
        return host instanceof WirelessTerminalMenuHost wireless && wireless.rangeCheck()
                && wireless.extractAEPower(0.5, Actionable.SIMULATE, PowerMultiplier.CONFIG) >= 0.5;
    }

    private static IGrid grid(IGridNode node) { return node == null ? null : node.getGrid(); }

    public boolean beginSubmit(AEBaseMenu menu) {
        if (submitting || !valid(menu)) return false;
        submitting = true;
        return true;
    }

    public void finishSubmit(AEBaseMenu menu) {
        submitting = false;
        if (player.containerMenu != menu) invalidate();
    }

    public void invalidate() { invalid = true; }

    public static void reject(AEBaseMenu menu) {
        var state = (TerminalOrderMenu) menu;
        var binding = state.thunderbolt$orderBinding();
        if (binding != null) binding.invalidate();
        // Retain the invalid marker until this menu is closed: late native actions must not
        // fall back to an unbound request after the provenance check has failed.
        menu.setValidMenu(false);
        menu.getPlayer().displayClientMessage(Component.translatable("gui.thunderbolt.terminal_order.invalid"), false);
    }
}
