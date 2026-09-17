package com.moakiee.thunderbolt.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * GTLCore {@code @Overwrite}s {@code CraftingCalculation#run} but still calls
 * {@code computePlan()} from that body. Thunderbolt must wrap the surviving
 * method, not fight the overwrite.
 */
class CraftingCalculationMixinContractTest {
    private static final Path SOURCE = Path.of(
            "src", "main", "java", "com", "moakiee", "thunderbolt",
            "mixin", "ae2", "crafting", "CraftingCalculationMixin.java");

    @Test
    void attachesAtComputePlanInsteadOfOverwrittenRun() throws IOException {
        assertTrue(Files.exists(SOURCE), () -> "missing " + SOURCE.toAbsolutePath());
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("@WrapMethod(method = \"computePlan\")"),
                "planner must wrap computePlan so GTLCore's overwritten run() still reaches it");
        assertTrue(source.contains(
                "com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod"),
                "computePlan wrap must use MixinExtras WrapMethod, not a call-site WrapOperation");
        assertFalse(source.contains("method = \"run\""),
                "must not inject or wrap run(); GTLCore @Overwrites that method");
        assertTrue(source.contains("GtlCompat.isCraftingHandoverActive()"),
                "GTL yield must detect handover: GTLCore's simulateFor is a no-op");
        assertTrue(source.contains("LockSupport.parkNanos"),
                "GTL yield must park instead of waiting on AE2's per-tick monitor");
    }
}
