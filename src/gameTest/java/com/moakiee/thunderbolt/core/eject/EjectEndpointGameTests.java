package com.moakiee.thunderbolt.core.eject;

import com.moakiee.thunderbolt.api.eject.EjectCapabilityRegistry;
import com.moakiee.thunderbolt.api.eject.EjectEndpoint;
import com.moakiee.thunderbolt.api.eject.EjectOfflinePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Exercises real registration handles and SavedData in an isolated GameTest world. */
@GameTestHolder("thunderbolt_nbt")
@PrefixGameTestTemplate(false)
public final class EjectEndpointGameTests {
    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void closingOneHostKeepsOtherHostsAfterPersistenceRoundTrip(GameTestHelper helper) {
        var first = endpoint(helper, 1);
        var second = endpoint(helper, 2);
        try (var a = EjectCapabilityRegistry.register(first, (server, endpoint) -> null);
             var b = EjectCapabilityRegistry.register(second, (server, endpoint) -> null)) {
            a.close();
            checkPersisted(helper, first, false);
            checkPersisted(helper, second, true);
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void duplicateHandlesKeepPersistenceUntilTheLastHandleCloses(GameTestHelper helper) {
        var endpoint = endpoint(helper, 1);
        try (var a = EjectCapabilityRegistry.register(endpoint, (server, ignored) -> null);
             var b = EjectCapabilityRegistry.register(endpoint, (server, ignored) -> null)) {
            a.close();
            checkPersisted(helper, endpoint, true);
            b.close();
            checkPersisted(helper, endpoint, false);
        }
        helper.succeed();
    }

    @GameTest(template = "runtime_empty", timeoutTicks = 100)
    public static void anOldHandleCannotEraseAReplacementRegistration(GameTestHelper helper) {
        var endpoint = endpoint(helper, 1);
        try (var old = EjectCapabilityRegistry.register(endpoint, (server, ignored) -> null)) {
            EjectCapabilityRegistry.unregister(endpoint.interceptDimension(),
                    endpoint.interceptPos(), endpoint.interceptFace());
            try (var replacement = EjectCapabilityRegistry.register(endpoint, (server, ignored) -> null)) {
                old.close();
                checkPersisted(helper, endpoint, true);
            }
        }
        helper.succeed();
    }

    private static EjectEndpoint endpoint(GameTestHelper helper, int hostOffset) {
        var dimension = helper.getLevel().dimension();
        var position = helper.absolutePos(new BlockPos(0, 1, 0));
        return new EjectEndpoint(dimension, position, Direction.UP,
                dimension, position.offset(hostOffset, 0, 0), EjectOfflinePolicy.REJECT);
    }

    private static void checkPersisted(GameTestHelper helper, EjectEndpoint endpoint, boolean expected) {
        var saved = EjectRegistrationSavedData.get(helper.getLevel().getServer());
        var decoded = EjectRegistrationSavedData.load(saved.save(new net.minecraft.nbt.CompoundTag()));
        var registration = new EjectRegistrationSavedData.PersistentRegistration(
                endpoint.interceptDimension(), endpoint.interceptPos(), endpoint.interceptFace(),
                endpoint.hostDimension(), endpoint.hostPos());
        if (decoded.getAll().contains(registration) != expected) {
            throw new AssertionError("Endpoint persistence differs from active handles: " + registration);
        }
    }
}
