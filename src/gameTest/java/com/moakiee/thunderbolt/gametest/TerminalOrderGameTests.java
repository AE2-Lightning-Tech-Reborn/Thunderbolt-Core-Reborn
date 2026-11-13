package com.moakiee.thunderbolt.gametest;

import java.lang.reflect.Proxy;
import java.util.UUID;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.core.definitions.AEItems;
import appeng.helpers.WirelessTerminalMenuHost;
import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.locator.MenuLocator;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderBinding;
import com.moakiee.thunderbolt.core.terminal.TerminalOrderMenu;
import com.moakiee.thunderbolt.network.terminal.TerminalOrderPackets;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Transformed AE2 menus with controlled hosts; not real WTLib/JEI interaction acceptance. */
@GameTestHolder("thunderbolt_nbt")
@PrefixGameTestTemplate(false)
public final class TerminalOrderGameTests {
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void serverMenuSessionsAreUniqueAndCodecUsesTheRealAe2Registry(GameTestHelper helper) {
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        var host = new Host(player, AEItems.WIRELESS_TERMINAL.stack(), grid());
        var first = new CraftAmountMenu(4, player.getInventory(), host);
        var second = new CraftAmountMenu(4, player.getInventory(), host);
        var a = ((TerminalOrderMenu) first).thunderbolt$orderSession();
        var b = ((TerminalOrderMenu) second).thunderbolt$orderSession();
        helper.assertTrue(!a.isEmpty() && !a.equals(b), "reused container id reused its nonce");
        var state = (TerminalOrderMenu) second;
        helper.assertTrue(!state.thunderbolt$orderGate().accept(b, a, 1), "late packet claimed a reopened menu");
        helper.assertTrue(state.thunderbolt$orderGate().accept(b, b, 1)
                && !state.thunderbolt$orderGate().accept(b, b, 1), "duplicate request was admitted");
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var packet = new TerminalOrderPackets.Request(4, UUID.fromString(b), 1, AEItemKey.of(Items.IRON_INGOT));
            packet.write(buf);
            helper.assertTrue(packet.equals(TerminalOrderPackets.Request.read(buf)), "real registry packet roundtrip changed key/session");
        } finally { buf.release(); }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void provenanceRejectsReplacementNetworkPowerAndModuleChanges(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        try {
            helper.assertTrue(f.binding.valid(f.menu), "unchanged source was rejected");
            var original = f.locator.host;
            f.locator.host = new Host(f.player, original.getItemStack().copy(), f.grid);
            helper.assertTrue(!f.binding.valid(f.menu), "equal-NBT replacement stack was accepted");
            f.locator.host = new Host(f.player, original.getItemStack(), grid());
            helper.assertTrue(!f.binding.valid(f.menu), "same locator was allowed to change network");
            f.locator.host = new OtherModuleHost(f.player, original.getItemStack(), f.grid);
            helper.assertTrue(!f.binding.valid(f.menu), "selected module host was allowed to change");
            f.locator.host = original;
            original.powered = false;
            helper.assertTrue(!f.binding.valid(f.menu), "unpowered terminal was accepted");
            original.powered = true; original.inRange = false;
            helper.assertTrue(!f.binding.valid(f.menu), "disconnected terminal was accepted");
            original.inRange = true;
            helper.assertTrue(f.binding.valid(f.menu), "restored source did not validate");
            f.player.containerMenu = f.player.inventoryMenu;
            helper.assertTrue(!f.binding.valid(f.menu), "closed menu retained authority");
        } finally { f.player.containerMenu = f.player.inventoryMenu; }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void nativeConfirmationCannotPlanOrSubmitAfterSourceInvalidation(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        try {
            var confirm = new CraftConfirmMenu(6, f.player.getInventory(), f.locator.host);
            confirm.setLocator(f.locator);
            ((TerminalOrderMenu) confirm).thunderbolt$orderBinding(f.binding);
            f.player.containerMenu = confirm;
            f.locator.host = null;
            helper.assertTrue(!confirm.planJob(AEItemKey.of(Items.IRON_INGOT), 1,
                    appeng.api.networking.crafting.CalculationStrategy.CRAFT_LESS), "invalid source reached planning");
            helper.assertTrue(!confirm.isValidMenu(), "invalid request left confirmation active");
            confirm.startJob();
            confirm.startJob();
            helper.assertTrue(!f.binding.beginSubmit(confirm), "duplicate confirmation revived invalid provenance");
        } finally { f.player.containerMenu = f.player.inventoryMenu; }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void submissionGuardRejectsReentryAndFinishedMenu(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        try {
            helper.assertTrue(f.binding.beginSubmit(f.menu), "first submission was rejected");
            helper.assertTrue(!f.binding.beginSubmit(f.menu), "reentrant submission was accepted");
            f.binding.finishSubmit(f.menu);
            helper.assertTrue(f.binding.beginSubmit(f.menu), "known normal return prevented explicit retry");
            f.player.containerMenu = f.player.inventoryMenu;
            f.binding.finishSubmit(f.menu);
            f.player.containerMenu = f.menu;
            helper.assertTrue(!f.binding.beginSubmit(f.menu), "finished request could be submitted again");
        } finally { f.player.containerMenu = f.player.inventoryMenu; }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void menuOpenerCarriesBindingAndRejectsNetworkReplacement(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        var registryField = appeng.menu.MenuOpener.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var registry = (java.util.Map<net.minecraft.world.inventory.MenuType<?>, appeng.menu.MenuOpener.Opener>) registryField.get(null);
        var original = registry.get(CraftAmountMenu.TYPE);
        try {
            appeng.menu.MenuOpener.addOpener(CraftAmountMenu.TYPE, (player, locator, returning) -> {
                var amount = new CraftAmountMenu(7, player.getInventory(), f.locator.host);
                amount.setLocator(locator);
                player.containerMenu = amount;
                return true;
            });
            MenuLocator reconstructed = new MenuLocator() {
                @Override public <T> T locate(Player player, Class<T> type) { return f.locator.locate(player, type); }
            };
            helper.assertTrue(appeng.menu.MenuOpener.open(CraftAmountMenu.TYPE, f.player, reconstructed),
                    "unchanged locator did not open the quantity menu");
            var amount = (CraftAmountMenu) f.player.containerMenu;
            helper.assertTrue(amount.getLocator() == reconstructed && f.binding.valid(amount),
                    "native locator reconstruction lost the original source binding");
            helper.assertTrue(((TerminalOrderMenu) amount).thunderbolt$orderBinding() == f.binding,
                    "quantity transition lost its provenance");
            helper.assertTrue(((TerminalOrderMenu) f.menu).thunderbolt$orderBinding() == null,
                    "previous menu retained the active binding");
            helper.assertTrue(((TerminalOrderMenu) f.menu).thunderbolt$orderRetired(),
                    "old menu was allowed to fall back to an unbound order");
            f.locator.host = new Host(f.player, f.locator.host.getItemStack(), grid());
            helper.assertTrue(!appeng.menu.MenuOpener.open(CraftAmountMenu.TYPE, f.player, reconstructed),
                    "quantity transition silently changed network");
            helper.assertTrue(f.player.containerMenu == amount && !amount.isValidMenu(),
                    "rejected transition replaced or reactivated the old menu");
        } finally {
            registry.put(CraftAmountMenu.TYPE, original);
            f.player.containerMenu = f.player.inventoryMenu;
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void nativeSubmitUsesOriginalActorAndDuplicateConfirmationCannotSubmitAgain(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper);
        try {
            var confirm = new CraftConfirmMenu(8, f.player.getInventory(), f.locator.host);
            confirm.setLocator(f.locator);
            ((TerminalOrderMenu) confirm).thunderbolt$orderBinding(f.binding);
            f.player.containerMenu = confirm;
            var result = CraftConfirmMenu.class.getDeclaredField("result");
            result.setAccessible(true);
            result.set(confirm, Proxy.newProxyInstance(getClassLoader(),
                    new Class<?>[]{appeng.api.networking.crafting.ICraftingPlan.class},
                    (p, method, args) -> {
                        if (method.getName().equals("simulation")) return false;
                        throw new UnsupportedOperationException(method.getName());
                    }));
            confirm.startJob();
            helper.assertTrue(f.submits.get() == 1 && f.actor[0] == f.binding.source(),
                    "native submit changed actor or failed to submit exactly once");
            ((TerminalOrderMenu) confirm).thunderbolt$retireOrder();
            confirm.startJob();
            helper.assertTrue(f.submits.get() == 1, "repeated confirmation submitted twice");
        } finally { f.player.containerMenu = f.player.inventoryMenu; }
        helper.succeed();
    }

    private static ClassLoader getClassLoader() { return TerminalOrderGameTests.class.getClassLoader(); }

    private static final class Fixture {
        final ServerPlayer player;
        final java.util.concurrent.atomic.AtomicInteger submits = new java.util.concurrent.atomic.AtomicInteger();
        final appeng.api.networking.security.IActionSource[] actor = new appeng.api.networking.security.IActionSource[1];
        final IGrid grid = grid(submits, actor);
        final Locator locator;
        final MEStorageMenu menu;
        final TerminalOrderBinding binding;

        Fixture(GameTestHelper helper) throws Exception {
            player = FakePlayerFactory.getMinecraft(helper.getLevel());
            locator = new Locator(new Host(player, AEItems.WIRELESS_TERMINAL.stack(), grid));
            menu = new MEStorageMenu(MEStorageMenu.WIRELESS_TYPE, 5, player.getInventory(), locator.host);
            menu.setLocator(locator);
            menu.hasPower = true;
            player.containerMenu = menu;
            // Capture's registry-id filter belongs to the real two AE2LT menu types. Here we
            // exercise the same provenance against a controlled AE2 menu without impersonating AE2LT.
            var constructor = TerminalOrderBinding.class.getDeclaredConstructor(ServerPlayer.class,
                    MEStorageMenu.class, appeng.api.implementations.menuobjects.ItemMenuHost.class, IGrid.class);
            constructor.setAccessible(true);
            binding = constructor.newInstance(player, menu, locator.host, grid);
            ((TerminalOrderMenu) menu).thunderbolt$orderBinding(binding);
        }
    }

    private static final class Locator implements MenuLocator {
        Host host;
        Locator(Host host) { this.host = host; }
        @Override public <T> T locate(Player player, Class<T> type) { return host != null && type.isInstance(host) ? type.cast(host) : null; }
    }

    private static class Host extends WirelessTerminalMenuHost {
        final IGridNode node;
        boolean powered = true;
        boolean inRange = true;
        Host(Player player, ItemStack stack, IGrid grid) {
            super(player, null, stack, (p, m) -> {});
            node = (IGridNode) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IGridNode.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getGrid" -> grid;
                        case "isActive", "isPowered", "isOnline", "hasGridBooted", "meetsChannelRequirements" -> true;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
        @Override public IGridNode getActionableNode() { return node; }
        @Override public boolean rangeCheck() { return inRange; }
        @Override public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) { return powered ? amount : 0; }
        @Override public MEStorage getInventory() { return () -> Component.literal("terminal-order-test"); }
        @Override public void returnToMainMenu(Player player, ISubMenu menu) { player.containerMenu = player.inventoryMenu; }
    }

    private static final class OtherModuleHost extends Host {
        OtherModuleHost(Player player, ItemStack stack, IGrid grid) { super(player, stack, grid); }
    }

    private static IGrid grid() {
        return grid(new java.util.concurrent.atomic.AtomicInteger(), new appeng.api.networking.security.IActionSource[1]);
    }

    private static IGrid grid(java.util.concurrent.atomic.AtomicInteger submits, appeng.api.networking.security.IActionSource[] actor) {
        var service = Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{appeng.api.networking.crafting.ICraftingService.class},
                (p, method, args) -> {
                    if (method.getName().equals("submitJob")) {
                        submits.incrementAndGet();
                        actor[0] = (appeng.api.networking.security.IActionSource) args[4];
                        return Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{appeng.api.networking.crafting.ICraftingSubmitResult.class},
                                (r, resultMethod, values) -> {
                                    if (resultMethod.getName().equals("successful")) return true;
                                    throw new UnsupportedOperationException(resultMethod.getName());
                                });
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (IGrid) Proxy.newProxyInstance(TerminalOrderGameTests.class.getClassLoader(), new Class<?>[]{IGrid.class},
                (p, method, args) -> {
                    if (method.getName().equals("getCraftingService")) return service;
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
