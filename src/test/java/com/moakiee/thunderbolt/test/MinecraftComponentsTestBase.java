package com.moakiee.thunderbolt.test;

import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.WorldDataConfiguration;

/** Binds registry defaults before a test class initializes its item stacks. */
public abstract class MinecraftComponentsTestBase {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var packs = ServerPacksSource.createVanillaTrustedRepository();
        var config = new WorldLoader.InitConfig(
                new WorldLoader.PackConfig(packs, WorldDataConfiguration.DEFAULT, false, false),
                Commands.CommandSelection.DEDICATED,
                PermissionSet.ALL_PERMISSIONS);
        WorldLoader.load(config,
                data -> new WorldLoader.DataLoadOutput<>(null, data.datapackDimensions()),
                (resources, managers, registries, cookie) -> {
                    resources.close();
                    return true;
                }, Runnable::run, Runnable::run).join();
    }
}
