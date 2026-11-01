package com.moakiee.thunderbolt.gametest;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionHost;
import appeng.api.parts.IPart;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.ISubMenuHost;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.moakiee.thunderbolt.api.crafting.*;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingAlgorithmCalculationStatus;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Executes the transformed AE2 menu; the grid service is a controlled calculation boundary. */
@GameTestHolder("thunderbolt_nbt")
@PrefixGameTestTemplate(false)
public final class CraftConfirmRequestGameTests {
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void longReplanAndNativeResetPreserveRequestAndTracking(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        var menu = fixture.menu;
        var key = AEItemKey.of(Items.IRON_INGOT);
        var amount = BigInteger.valueOf(3_000_000_000_000L);
        try {
            helper.assertTrue(fixture.requests.thunderbolt$planRequest(key, amount, CalculationStrategy.REPORT_MISSING_ITEMS),
                    "long request was declined");
            helper.assertTrue(fixture.amounts.equals(List.of(amount.longValueExact())), "long request was truncated");
            var first = fixture.jobs.get(0);
            helper.assertTrue(CraftingPlanningEngines.VANILLA_ID.equals(CraftingAlgorithmCalculationStatus.selected(first)),
                    "calculation wrapper did not retain selected algorithm");
            menu.replan();
            helper.assertTrue(first.isCancelled() && CraftingAlgorithmCalculationStatus.selected(first) == null,
                    "replan retained the previous calculation");
            helper.assertTrue(fixture.amounts.equals(List.of(amount.longValueExact(), amount.longValueExact())),
                    "replan lost the extended quantity");
            helper.assertTrue(menu.planJob(key, 7, CalculationStrategy.CRAFT_LESS), "native request was declined");
            menu.replan();
            helper.assertTrue(fixture.amounts.equals(List.of(amount.longValueExact(), amount.longValueExact(), 7L, 7L)),
                    "native request inherited the previous extended amount");
            helper.assertTrue(!fixture.requests.thunderbolt$isExactRequest(), "long request selected an exact CPU");
        } finally {
            menu.removed(menu.getPlayer());
        }
        helper.assertTrue(fixture.jobs.stream().allMatch(CompletableFuture::isCancelled), "menu close left a running job");
        helper.assertTrue(fixture.jobs.stream().allMatch(job -> CraftingAlgorithmCalculationStatus.selected(job) == null),
                "menu close retained calculation tracking");
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void unavailableExactBackendLeavesCurrentLongJobIntact(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        var key = AEItemKey.of(Items.GOLD_INGOT);
        try {
            helper.assertTrue(fixture.requests.thunderbolt$planRequest(key, BigInteger.TEN, CalculationStrategy.CRAFT_LESS),
                    "initial request was declined");
            helper.assertTrue(!fixture.requests.thunderbolt$planRequest(key, BigInteger.ONE.shiftLeft(80),
                    CalculationStrategy.CRAFT_LESS), "an unavailable exact backend accepted the request");
            helper.assertTrue(!fixture.requests.thunderbolt$planRequest(key, BigInteger.ZERO, CalculationStrategy.CRAFT_LESS),
                    "zero request was accepted");
            helper.assertTrue(fixture.amounts.equals(List.of(10L)) && !fixture.jobs.get(0).isCancelled(),
                    "a declined request replaced or cancelled the active job");
        } finally {
            fixture.menu.removed(fixture.menu.getPlayer());
        }
        helper.succeed();
    }

    private static final class Fixture {
        final List<Long> amounts = new ArrayList<>();
        final List<CompletableFuture<ICraftingPlan>> jobs = new ArrayList<>();
        final CraftConfirmMenu menu;
        final CraftingRequestMenu requests;

        Fixture(GameTestHelper helper) {
            var service = proxy(ICraftingService.class, (p, method, args) -> {
                if (method.getName().equals("beginCraftingCalculation")) {
                    amounts.add((long) args[3]);
                    CraftingAlgorithmCalculationStatus.select((ICraftingSimulationRequester) args[1],
                            CraftingPlanningEngines.VANILLA_ID);
                    var job = new CompletableFuture<ICraftingPlan>();
                    jobs.add(job);
                    return job;
                }
                throw new UnsupportedOperationException(method.getName());
            });
            var planning = proxy(ICraftingPlanningService.class, (p, method, args) -> {
                if (method.getName().equals("resolve")) return List.of(PlanningChoice.VANILLA);
                throw new UnsupportedOperationException(method.getName());
            });
            var grid = proxy(IGrid.class, (p, method, args) -> switch (method.getName()) {
                case "getCraftingService" -> service;
                case "getService" -> args[0] == ICraftingPlanningService.class ? planning : null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            var node = proxy(IGridNode.class, (p, method, args) -> {
                if (method.getName().equals("getGrid")) return grid;
                throw new UnsupportedOperationException(method.getName());
            });
            var host = (ISubMenuHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ISubMenuHost.class, IActionHost.class, IPart.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "getActionableNode" -> node;
                        case "getMainMenuIcon" -> ItemStack.EMPTY;
                        case "toString" -> "craft-confirm-test-host";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            menu = new CraftConfirmMenu(1, FakePlayerFactory.getMinecraft(helper.getLevel()).getInventory(), host);
            requests = (CraftingRequestMenu) (Object) menu;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(CraftConfirmRequestGameTests.class.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
