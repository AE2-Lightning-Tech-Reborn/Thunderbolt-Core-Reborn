package com.moakiee.thunderbolt.neoecoqa;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootDataId;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client lifecycle checks alongside NeoECO's real workstation/EMI acceptance driver. */
@Mod.EventBusSubscriber(modid = "thunderbolt_neoeco_qa", value = Dist.CLIENT)
public final class NeoEcoGtoClientProbe {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("thunderbolt_neoeco_qa");
    private static boolean done;
    private static long readyAt;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END || Minecraft.getInstance().level == null) return;
        try {
            if (!Boolean.TRUE.equals(Class.forName("dev.emi.emi.runtime.EmiReloadManager")
                    .getMethod("isLoaded").invoke(null))) { readyAt = 0; return; }
            if (readyAt == 0) readyAt = System.nanoTime();
            if (System.nanoTime() - readyAt < 15_000_000_000L) return;
            done = true;
            var gateField = Class.forName("com.moakiee.thunderbolt.compat.gto.GtoDataInitialization")
                    .getDeclaredField("GATE");
            gateField.setAccessible(true);
            var gate = gateField.get(null);
            var completionField = gate.getClass().getDeclaredField("completion");
            completionField.setAccessible(true);
            var completion = (CompletableFuture<?>) completionField.get(gate);
            require(completion != null && completion.isDone() && !completion.isCompletedExceptionally(),
                    "renamed GTO executor was not tracked to completion");
            require(Class.forName("com.gtocore.data.Data").getMethod("getThrowable").invoke(null) == null,
                    "GTO data registration failed");
            var cache = (Map<?, ?>) Class.forName("com.gtocore.common.data.GTOLoots")
                    .getField("ELEMENTS_CACHE").get(null);
            // GTO 26.10.9 obfuscates the loot generator class; resource IDs remain public data.
            for (String name : new String[]{"lv_reward_bag_loot", "rune_reward_bag1_loot",
                    "rune_reward_bag2_loot", "rune_reward_bag3_loot", "rune_reward_bag4_loot"}) {
                var id = new ResourceLocation("gtocore", "reward_bags/" + name);
                Object loot = cache.get(new LootDataId<>(LootDataType.TABLE, id));
                require(loot instanceof LootTable && loot != LootTable.EMPTY, "reward loot missing: " + id);
            }
            var utility = Class.forName("com.gregtechceu.gtceu.utils.GTUtil");
            var names = new String[]{"isShiftDown", "isCtrlDown", "isAltDown"};
            boolean[] foreground = {Screen.hasShiftDown(), Screen.hasControlDown(), Screen.hasAltDown()};
            for (int i = 0; i < names.length; i++) require(
                    utility.getMethod(names[i]).invoke(null).equals(foreground[i]), "foreground key behavior changed");
            var failure = new AtomicReference<Throwable>();
            var worker = new Thread(() -> {
                try { backgroundKeys(utility, names); }
                catch (Throwable error) { failure.set(error); }
            }, "neoeco-gto-client-keys");
            worker.start(); worker.join(5000);
            require(!worker.isAlive() && failure.get() == null, "background key query failed: " + failure.get());
            var shutdownProof = Minecraft.getInstance().gameDirectory.toPath().resolve("thunderbolt-neoeco-shutdown.txt");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    backgroundKeys(utility, names);
                    java.nio.file.Files.writeString(shutdownProof, "PASS");
                    LOG.info("THUNDERBOLT_NEOECO_CLIENT_QA SHUTDOWN PASS");
                } catch (Throwable error) {
                    LOG.error("THUNDERBOLT_NEOECO_CLIENT_QA FAIL shutdown", error);
                }
            }, "neoeco-gto-shutdown-keys"));
            LOG.info("THUNDERBOLT_NEOECO_CLIENT_QA PASS tracked async initialization, five reward loots, EMI and modifier keys");
        } catch (Throwable error) {
            done = true;
            LOG.error("THUNDERBOLT_NEOECO_CLIENT_QA FAIL", error);
        }
    }

    private static void backgroundKeys(Class<?> utility, String[] names) throws Exception {
        for (String name : names) require(Boolean.FALSE.equals(utility.getMethod(name).invoke(null)), name);
        require(!Screen.hasShiftDown() && !Screen.hasControlDown() && !Screen.hasAltDown(), "background Screen query");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
