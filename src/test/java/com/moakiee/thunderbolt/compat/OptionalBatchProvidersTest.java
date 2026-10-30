package com.moakiee.thunderbolt.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapters;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OptionalBatchProvidersTest {
    private static final ResourceLocation NEOECO = new ResourceLocation("thunderbolt", "neoeco");
    private static final ResourceLocation EAEP = new ResourceLocation("thunderbolt", "extendedae_plus");
    private static final ResourceLocation USELESS = new ResourceLocation("thunderbolt", "useless");
    private static final BatchProviderAdapter ADAPTER = (provider, pattern, job) -> null;

    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.ensureInitialized(); }

    @AfterEach void cleanup() {
        BatchProviderAdapters.unregister(NEOECO);
        BatchProviderAdapters.unregister(EAEP);
        BatchProviderAdapters.unregister(USELESS);
    }

    @Test void absentModDoesNotInvokeFactoryOrChangeTheRegistry() {
        var before = BatchProviderAdapters.entries();
        Supplier<BatchProviderAdapter> forbidden = () -> { throw new AssertionError("optional factory loaded"); };
        var checked = new ArrayList<String>();
        OptionalBatchProviders.register(id -> { checked.add(id); return false; }, forbidden, forbidden, forbidden);
        assertEquals(List.of("useless_mod", "neoecoae", "extendedae_plus"), checked);
        assertSame(before, BatchProviderAdapters.entries());
    }

    @Test void extendedAePlusRegistersOnlyWhenItsActualModIdIsLoaded() {
        OptionalBatchProviders.register("extendedae_plus"::equals,
                () -> { throw new AssertionError("NeoECO is absent"); }, () -> ADAPTER,
                () -> { throw new AssertionError("Useless is absent"); });
        assertRegistration(EAEP);
    }

    @Test void neoEcoRegistersOnlyWhenItsActualModIdIsLoaded() {
        OptionalBatchProviders.register("neoecoae"::equals, () -> ADAPTER,
                () -> { throw new AssertionError("EAEP is absent"); },
                () -> { throw new AssertionError("Useless is absent"); });
        assertRegistration(NEOECO);
    }

    @Test void uselessRegistersOnlyWhenItsActualModIdIsLoaded() {
        Supplier<BatchProviderAdapter> forbidden = () -> { throw new AssertionError("optional factory loaded"); };
        OptionalBatchProviders.register("useless_mod"::equals, forbidden, forbidden, () -> ADAPTER);
        assertRegistration(USELESS);
    }

    @Test void registryNameDoesNotStandInForTheUselessModId() {
        var before = BatchProviderAdapters.entries();
        Supplier<BatchProviderAdapter> forbidden = () -> { throw new AssertionError("optional factory loaded"); };
        OptionalBatchProviders.register("useless"::equals, forbidden, forbidden, forbidden);
        assertSame(before, BatchProviderAdapters.entries());
    }

    @Test void unsupportedOptionalApiDoesNotChangeTheRegistry() {
        var before = BatchProviderAdapters.entries();
        OptionalBatchProviders.register(id -> true, () -> null, () -> null, () -> null);
        assertSame(before, BatchProviderAdapters.entries());
    }

    @Test void loadedAdapterKeepsDuplicateIdProtection() {
        OptionalBatchProviders.register("extendedae_plus"::equals, () -> null, () -> ADAPTER, () -> null);
        assertRegistration(EAEP);
        var before = BatchProviderAdapters.entries();
        assertThrows(IllegalArgumentException.class,
                () -> OptionalBatchProviders.register("extendedae_plus"::equals, () -> null, () -> ADAPTER, () -> null));
        assertSame(before, BatchProviderAdapters.entries());
    }

    private static void assertRegistration(ResourceLocation id) {
        var entry = BatchProviderAdapters.entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        assertEquals(-100, entry.priority());
        assertSame(ADAPTER, entry.adapter());
    }
}
