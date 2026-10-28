package com.moakiee.thunderbolt.fixqa;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootDataId;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Waits for real data/EMI readiness before the existing disposable QA driver exits. */
@Mod.EventBusSubscriber(modid = "thunderbolt_fix_qa", value = Dist.CLIENT)
public final class GtoDataReadinessClientProbe {
    private static long started;
    private static long loadedAt;
    private static boolean done;

    public static void install() {
        if (Boolean.getBoolean("thunderbolt.qaDelayGtoData")) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(
                    (FMLLoadCompleteEvent event) -> event.enqueueWork(() -> {
                        System.out.println("THUNDERBOLT_DATA_QA control: delaying initial datapack load 60s");
                        try { TimeUnit.SECONDS.sleep(60); }
                        catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                    }));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(TickEvent.ClientTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END || Minecraft.getInstance().level == null) return;
        long now = System.nanoTime();
        if (started == 0) started = now;
        try {
            Throwable failure = (Throwable) Class.forName("com.gtocore.data.Data")
                    .getMethod("getThrowable").invoke(null);
            if (failure != null) throw new AssertionError("GTO data initialization failed", failure);
            boolean loaded = Boolean.TRUE.equals(Class.forName("dev.emi.emi.runtime.EmiReloadManager")
                    .getMethod("isLoaded").invoke(null));
            if (!loaded) loadedAt = 0;
            else if (loadedAt == 0) loadedAt = now;
            if (loadedAt != 0 && now - loadedAt >= TimeUnit.SECONDS.toNanos(15)) {
                if (Boolean.getBoolean("thunderbolt.qaRequireGtoGate")) verifyTrackedTask();
                verifyRewardLoot();
                System.out.println("THUNDERBOLT_DATA_QA PASS GTO data and EMI reload ready before shutdown");
                done = true;
                return;
            }
            if (now - started > TimeUnit.SECONDS.toNanos(180)) {
                throw new AssertionError("EMI reload did not become ready");
            }
            Field ticks = Class.forName("qa.GtoClientQA").getDeclaredField("ticks");
            ticks.setAccessible(true);
            ticks.setInt(null, 0);
        } catch (Throwable error) {
            done = true;
            System.out.println("THUNDERBOLT_DATA_QA FAIL " + error);
            error.printStackTrace();
        }
    }

    private static void verifyTrackedTask() throws Exception {
        var gateField = Class.forName("com.moakiee.thunderbolt.compat.gto.GtoDataInitialization")
                .getDeclaredField("GATE");
        gateField.setAccessible(true);
        Object gate = gateField.get(null);
        var completionField = gate.getClass().getDeclaredField("completion");
        completionField.setAccessible(true);
        var completion = (CompletableFuture<?>) completionField.get(gate);
        if (completion == null || !completion.isDone() || completion.isCompletedExceptionally()) {
            throw new AssertionError("actual GTO data submission was not tracked to completion");
        }
        System.out.println("THUNDERBOLT_DATA_QA PASS actual GTO async task tracked and completed");
    }

    private static void verifyRewardLoot() throws Exception {
        var loots = Class.forName("com.gtocore.common.data.GTOLoots");
        var cache = (Map<?, ?>) loots.getField("ELEMENTS_CACHE").get(null);
        if (cache == null || cache.isEmpty()) throw new AssertionError("GTO loot cache missing");
        var rewards = Class.forName("com.gtocore.data.lootTables.RewardBagLoot");
        for (String field : new String[]{"LV_REWARD_BAG_LOOT", "RUNE_REWARD_BAG1_LOOT",
                "RUNE_REWARD_BAG2_LOOT", "RUNE_REWARD_BAG3_LOOT", "RUNE_REWARD_BAG4_LOOT"}) {
            var id = (ResourceLocation) rewards.getField(field).get(null);
            Object table = cache.get(new LootDataId<>(LootDataType.TABLE, id));
            if (!(table instanceof LootTable loot) || loot == LootTable.EMPTY) {
                throw new AssertionError("GTO reward loot missing: " + id);
            }
        }
        System.out.println("THUNDERBOLT_DATA_QA PASS all 5 GTO reward-bag loot tables retained");
    }
}
