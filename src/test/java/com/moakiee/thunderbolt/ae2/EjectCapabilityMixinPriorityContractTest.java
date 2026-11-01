package com.moakiee.thunderbolt.mixin.platform.eject;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EjectCapabilityMixinPriorityContractTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/mixin/platform/eject/EjectCapabilityMixin.java"));
    }

    @Test
    void thunderboltEjectInterceptorOverridesBlockEntityGetCapability() throws Exception {
        String source = source();

        assertTrue(source.contains("@Mixin(value = BlockEntity.class, priority = 2000, remap = false)"));
        assertTrue(source.contains("extends CapabilityProvider<BlockEntity>"));
        assertTrue(source.contains(
                "public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side)"));
        assertTrue(source.contains("super.getCapability(capability, side)"));
    }

    /**
     * A mixin on CapabilityProvider makes ItemStack/Entity/Level "inherit from mixin", which lets
     * Mixin 0.8.5 corrupt other mods' interface-super calls (SpongePowered/Mixin#504, e.g.
     * ElementalCraft's IForgeItemStack.super enchantment calls).
     */
    @Test
    void thunderboltDoesNotMixIntoCapabilityProvider() throws Exception {
        assertFalse(source().contains("@Mixin(value = CapabilityProvider.class"));
        try (var files = Files.walk(Path.of("src/main/java/com/moakiee/thunderbolt/mixin"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(file);
                String compact = text.replaceAll("\\s+", "");
                assertFalse(compact.contains("@Mixin(CapabilityProvider.class")
                                || compact.contains("@Mixin(value=CapabilityProvider.class")
                                || compact.contains("@Mixin({CapabilityProvider.class")
                                || compact.contains("@Mixin(value={CapabilityProvider.class"),
                        file + " must not target CapabilityProvider");
            }
        }
    }
}
