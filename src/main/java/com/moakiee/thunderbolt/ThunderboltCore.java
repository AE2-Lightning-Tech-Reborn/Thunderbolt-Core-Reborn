package com.moakiee.thunderbolt;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import appeng.api.networking.GridServices;
import appeng.api.storage.StorageCells;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngines;
import com.moakiee.thunderbolt.api.crafting.ICraftingPlanningService;
import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.config.ThunderboltCommonConfig;
import com.moakiee.thunderbolt.core.crafting.algorithm.CraftingPlanningService;
import com.moakiee.thunderbolt.core.crafting.algorithm.ThunderboltMenus;
import com.moakiee.thunderbolt.core.crafting.planner.CpSatPlanningEngine;
import com.moakiee.thunderbolt.core.crafting.planner.ThunderboltV2PlanningEngine;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;
import com.moakiee.thunderbolt.core.eject.ThunderboltBlockEntities;
import com.moakiee.thunderbolt.core.storage.cell.IndexedCellStorageRegistry;
import com.moakiee.thunderbolt.core.storage.cell.IndexedStorageCellHandler;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import com.moakiee.thunderbolt.core.keys.ResourceConstructionCache;
import com.moakiee.thunderbolt.core.keys.ObjectReuseOptions;

/** Entry point for Thunderbolt Core Reborn's shared AE2 optimization and extension layer. */
@Mod(ThunderboltCore.MODID)
public final class ThunderboltCore {
    public static final String MODID = "thunderbolt";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ThunderboltCore(IEventBus modEventBus, ModContainer modContainer) {
        EjectCapabilityRegistry.installRuntime(EjectEndpointIndex.INSTANCE);
        ThunderboltBlockEntities.TYPES.register(modEventBus);
        ThunderboltMenus.TYPES.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onConfigChanged);
        modContainer.registerConfig(
                ModConfig.Type.COMMON, ThunderboltCommonConfig.SPEC, "thunderbolt-common.toml");
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        LOGGER.info("[Thunderbolt Core Reborn] initialized");
    }

    private void onServerStarting(ServerStartingEvent event) {
        EjectEndpointIndex.INSTANCE.onServerStart(event.getServer());
        IndexedCellStorageRegistry.get(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        EjectEndpointIndex.INSTANCE.onServerStop();
        KeyConstructionCache.clear();
        ResourceConstructionCache.clear();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        KeyConstructionCache.maintain();
    }

    private void onConfigChanged(ModConfigEvent event) {
        if (event.getConfig().getSpec() == ThunderboltCommonConfig.SPEC) {
            boolean loaded = !(event instanceof ModConfigEvent.Unloading);
            KeyConstructionCache.configure(loaded && ThunderboltCommonConfig.reuseAeKeys(),
                    loaded && ThunderboltCommonConfig.reuseComponentKeys());
            ResourceConstructionCache.configure(loaded && ThunderboltCommonConfig.reuseResourceLocations(),
                    loaded && ThunderboltCommonConfig.reuseTagKeys());
            ObjectReuseOptions.cacheHashes = loaded && ThunderboltCommonConfig.cacheResourceHashes();
            ObjectReuseOptions.fastNbtCopies = loaded && ThunderboltCommonConfig.fastNbtCopies();
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            StorageCells.addCellHandler(IndexedStorageCellHandler.INSTANCE);
            GridServices.register(ICraftingPlanningService.class, CraftingPlanningService.class);
            CraftingPlanningEngines.register(
                    ThunderboltV2PlanningEngine.INSTANCE, 1_000, false);
            if (ThunderboltCommonConfig.enableCpSatPlanner()) {
                LOGGER.info("[Thunderbolt Core Reborn] CP-SAT enabled; preparing native runtime");
                var cacheRoot = FMLPaths.GAMEDIR.get()
                        .resolve(".cache")
                        .resolve(MODID)
                        .resolve("cp-sat");
                if (CpSatPlanningEngine.INSTANCE.initialize(cacheRoot)) {
                    CraftingPlanningEngines.register(
                            CpSatPlanningEngine.INSTANCE, 900, false);
                    LOGGER.info("[Thunderbolt Core Reborn] CP-SAT planner ready");
                } else {
                    LOGGER.warn(
                            "[Thunderbolt Core Reborn] CP-SAT native runtime unavailable; "
                                    + "continuing without the CP-SAT planner",
                            CpSatPlanningEngine.INSTANCE.availabilityFailure());
                }
            }
        });
    }
}
