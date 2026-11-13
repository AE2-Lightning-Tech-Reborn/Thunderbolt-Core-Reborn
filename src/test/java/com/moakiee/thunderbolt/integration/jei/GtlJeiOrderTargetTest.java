package com.moakiee.thunderbolt.integration.jei;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipFile;
import com.moakiee.thunderbolt.mixin.OptionalMixinSelector;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class GtlJeiOrderTargetTest {
    @Test void optionalHookRequiresBothGtlcoreAndJei() {
        String mixin = "com.moakiee.thunderbolt.mixin.compat.gtl.GtlJeiOrderMixin";
        assertFalse(OptionalMixinSelector.shouldApply(mixin, x -> false));
        assertFalse(OptionalMixinSelector.shouldApply(mixin, "jei"::equals));
        assertFalse(OptionalMixinSelector.shouldApply(mixin, "gtlcore"::equals));
        assertTrue(OptionalMixinSelector.shouldApply(mixin, Set.of("gtlcore", "jei")::contains));
    }

    @Test void fixedFix3InputStillHasExactlyOneSendAndOneCancellation() throws Exception {
        var target = System.getProperty("thunderbolt.terminalOrderTargetJar", "");
        assumeTrue(!target.isEmpty(), "fixed GTLCore runtime artifact was not supplied");
        var path = Path.of(target);
        assertEquals("6dae8e3f6b9b495b8c7c368f8a6e019d3dec182b06d14ff159d5e18f61b97a51",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        try (var zip = new ZipFile(path.toFile())) {
            try (var bytes = zip.getInputStream(zip.getEntry(
                    "org/gtlcore/gtlcore/client/forge/ForgeClientEventListener.class"))) {
                assertTrue(GtlJeiOrderCapability.supports(bytes), "discovered mod resource must be readable without transformation");
            }
            var node = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(
                    "org/gtlcore/gtlcore/client/forge/ForgeClientEventListener.class"))).accept(node, 0);
            assertTrue(GtlJeiOrderCapability.supports(node));
            var event = node.methods.stream().filter(m -> m.name.equals("onJeiWirelessOrderMousePressed")).findFirst().orElseThrow();
            var annotation = event.visibleAnnotations.stream()
                    .filter(a -> a.desc.endsWith("/SubscribeEvent;")).findFirst().orElseThrow();
            assertEquals("priority", annotation.values.get(0));
            assertArrayEquals(new String[]{"Lnet/minecraftforge/eventbus/api/EventPriority;", "HIGHEST"},
                    (String[]) annotation.values.get(1));
            var lambda = node.methods.stream().filter(m -> m.name.equals("lambda$onJeiWirelessOrderMousePressed$1"))
                    .findFirst().orElseThrow();
            int sends = 0, cancels = 0;
            for (var instruction : lambda.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.name.equals("sendRequest")) {
                        assertEquals("org/gtlcore/gtlcore/integration/ae2/wireless/JeiWirelessTerminalOrderPackets", call.owner);
                        assertEquals("(Lappeng/api/stacks/AEKey;)V", call.desc);
                        sends++;
                    }
                    if (call.name.equals("setCanceled")) cancels++;
                }
            }
            assertEquals(1, sends);
            assertEquals(1, cancels);
            lambda.name = "changed-input-handler";
            assertFalse(GtlJeiOrderCapability.supports(node), "an unknown input ABI must not be injected");
        }
    }
}
