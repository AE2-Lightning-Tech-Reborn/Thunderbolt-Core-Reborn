package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NeoEcoFastPathCompatTest {
    @Test void unrelatedProvidersKeepTheDirectOrdinaryCall() {
        int[] calls = {0};
        var provider = new appeng.api.networking.crafting.ICraftingProvider() {
            public java.util.List<appeng.api.crafting.IPatternDetails> getAvailablePatterns() { return java.util.List.of(); }
            public boolean isBusy() { return false; }
            public boolean pushPattern(appeng.api.crafting.IPatternDetails pattern, appeng.api.stacks.KeyCounter[] inputs) {
                calls[0]++;
                return true;
            }
        };
        assertTrue(NeoEcoFastPathCompat.pushPattern(provider, null,
                new appeng.api.stacks.KeyCounter[0], java.util.UUID.randomUUID()));
        assertEquals(1, calls[0]);
        assertNull(NeoEcoFastPathCompat.prepareExact(provider, null,
                new appeng.api.stacks.KeyCounter[0], java.math.BigInteger.TEN, null, java.util.UUID.randomUUID()));
        assertDoesNotThrow(() -> NeoEcoFastPathCompat.cancelJob(null, java.util.UUID.randomUUID()));
    }

    @Test void absentPlatformApiRetainsOrdinaryDispatch() {
        assertNull(NeoEcoFastPathCompat.loadAdapter(getClass().getClassLoader()));
    }
    @Test void oldOrAbsentApiIsDeclinedBeforeTheFirstCpuDispatch() {
        for (var missing : new String[] {"ECOFastPathFacade", "ECOFastPathDispatchProvider", "ECOFastPathFacade$EnergyAccount"}) {
            var loader = new ClassLoader(getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.endsWith("." + missing)) throw new ClassNotFoundException(name);
                    return super.loadClass(name, resolve);
                }
            };
            assertNull(NeoEcoFastPathCompat.loadAdapter(loader));
        }
    }
}
