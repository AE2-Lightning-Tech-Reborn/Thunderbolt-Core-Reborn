package com.moakiee.thunderbolt.core.eject;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

class EjectRegistrationSavedDataTest {
    @Test
    void roundTripsTheLegacyAe2ltSchema() {
        var overworld = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"));
        var nether = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", "the_nether"));
        var expected = new EjectRegistrationSavedData.PersistentRegistration(
                overworld, new BlockPos(12, 64, -9), Direction.WEST,
                nether, new BlockPos(-42, 80, 7));

        var legacy = new EjectRegistrationSavedData();
        legacy.add(expected);
        CompoundTag encodedLegacyFile = legacy.save(new CompoundTag());

        var decodedByThunderbolt = EjectRegistrationSavedData.load(encodedLegacyFile);
        assertEquals(java.util.List.of(expected), decodedByThunderbolt.getAll());

        CompoundTag encodedNewFile = decodedByThunderbolt.save(new CompoundTag());
        assertEquals(java.util.List.of(expected),
                EjectRegistrationSavedData.load(encodedNewFile).getAll());
    }
}
