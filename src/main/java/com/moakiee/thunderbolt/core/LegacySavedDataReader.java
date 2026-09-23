package com.moakiee.thunderbolt.core;

import java.io.IOException;
import java.nio.file.Files;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/** Reads the flat data files written before Minecraft 26.1 changed saved-data identifiers. */
public final class LegacySavedDataReader {
    private LegacySavedDataReader() {}

    public static CompoundTag read(MinecraftServer server, String name) {
        return readFile(server.getWorldPath(LevelResource.DATA).resolve(name + ".dat"));
    }

    public static CompoundTag read(ServerLevel level, String name) {
        var dataDirectory = DimensionType.getStorageFolder(
                level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
        return readFile(dataDirectory.resolve(name + ".dat"));
    }

    private static CompoundTag readFile(java.nio.file.Path file) {
        if (!Files.exists(file)) return null;
        try {
            var root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            return root.getCompound("data").orElseThrow(
                    () -> new IllegalStateException("Missing data payload in " + file));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read legacy saved data " + file, error);
        }
    }
}
