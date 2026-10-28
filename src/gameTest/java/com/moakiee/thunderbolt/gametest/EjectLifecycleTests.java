package com.moakiee.thunderbolt.gametest;

import com.moakiee.thunderbolt.api.eject.EjectEndpoint;
import com.moakiee.thunderbolt.api.eject.EjectOfflinePolicy;
import com.moakiee.thunderbolt.core.eject.EjectEndpointIndex;
import com.moakiee.thunderbolt.core.eject.EjectRegistrationSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("thunderbolt")
@PrefixGameTestTemplate(false)
public final class EjectLifecycleTests {
    private static EjectEndpoint endpoint(GameTestHelper helper, BlockPos intercept, BlockPos host) {
        var dimension = helper.getLevel().dimension();
        return new EjectEndpoint(dimension, helper.absolutePos(intercept), Direction.NORTH,
                dimension, helper.absolutePos(host), EjectOfflinePolicy.REJECT);
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void closingOneHostPreservesAnotherOnTheSameFace(GameTestHelper helper) {
        var index = EjectEndpointIndex.INSTANCE;
        var saved = EjectRegistrationSavedData.get(helper.getLevel().getServer());
        var first = endpoint(helper, new BlockPos(1, 1, 1), new BlockPos(2, 1, 1));
        var second = endpoint(helper, new BlockPos(1, 1, 1), new BlockPos(3, 1, 1));
        int initial = saved.getAll().size();
        try (var one = index.register(first, (server, value) -> null);
                var two = index.register(second, (server, value) -> null)) {
            one.close();
            helper.assertTrue(saved.getAll().size() == initial + 1,
                    "closing one host erased another host's saved registration");
            var loaded = saved.save(new net.minecraft.nbt.CompoundTag()).getList("Entries", 10);
            helper.assertTrue(loaded.size() == initial + 1, "remaining endpoint was not serialized");
            helper.assertTrue(index.lookupByFace(second.interceptDimension(),
                    second.interceptPos().asLong(), second.interceptFace()).endpoint().equals(second),
                    "remaining runtime endpoint changed");
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void duplicateHandlesKeepPersistenceUntilTheLastClose(GameTestHelper helper) {
        var index = EjectEndpointIndex.INSTANCE;
        var saved = EjectRegistrationSavedData.get(helper.getLevel().getServer());
        var endpoint = endpoint(helper, new BlockPos(1, 1, 1), new BlockPos(2, 1, 1));
        int initial = saved.getAll().size();
        try (var one = index.register(endpoint, (server, value) -> null);
                var two = index.register(endpoint, (server, value) -> null)) {
            helper.assertTrue(saved.getAll().size() == initial + 1, "duplicate was serialized twice");
            one.close();
            helper.assertTrue(saved.getAll().size() == initial + 1,
                    "closing one duplicate erased the live registration");
            two.close();
            helper.assertTrue(saved.getAll().size() == initial, "last close leaked persistence");
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void staleHandleCannotEraseAReplacementRegistration(GameTestHelper helper) {
        var index = EjectEndpointIndex.INSTANCE;
        var saved = EjectRegistrationSavedData.get(helper.getLevel().getServer());
        var endpoint = endpoint(helper, new BlockPos(1, 1, 1), new BlockPos(2, 1, 1));
        int initial = saved.getAll().size();
        try (var old = index.register(endpoint, (server, value) -> null)) {
            index.unregister(endpoint.interceptDimension(), endpoint.interceptPos(), endpoint.interceptFace());
            try (var replacement = index.register(endpoint, (server, value) -> null)) {
                old.close();
                helper.assertTrue(saved.getAll().size() == initial + 1,
                        "stale handle erased the replacement registration");
                helper.assertTrue(index.lookupByFace(endpoint.interceptDimension(),
                        endpoint.interceptPos().asLong(), endpoint.interceptFace()) != null,
                        "stale handle erased the runtime replacement");
            }
        }
        helper.succeed();
    }
}
