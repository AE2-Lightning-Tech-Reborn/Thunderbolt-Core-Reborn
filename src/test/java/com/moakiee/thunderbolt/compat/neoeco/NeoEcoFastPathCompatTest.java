package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NeoEcoFastPathCompatTest {
    @Test void exactReceiptPreservesAtomicAcceptanceAndSubmissionFailures() throws Exception {
        var submit = java.util.function.BooleanSupplier.class.getMethod("getAsBoolean");
        var count = java.math.BigInteger.ONE.shiftLeft(100);
        assertTrue(new NeoEcoFastPathCompat.ExactBatch(count,
                (java.util.function.BooleanSupplier) () -> true, submit).submit());
        assertFalse(new NeoEcoFastPathCompat.ExactBatch(count,
                (java.util.function.BooleanSupplier) () -> false, submit).submit());
        var failure = new IllegalStateException("ownership uncertain");
        var receipt = new NeoEcoFastPathCompat.ExactBatch(count,
                (java.util.function.BooleanSupplier) () -> { throw failure; }, submit);
        assertSame(failure, assertThrows(IllegalStateException.class, receipt::submit));
    }

    @Test void absentExactApiRetainsProviderInputs() {
        var provider = (appeng.api.networking.crafting.ICraftingProvider) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{appeng.api.networking.crafting.ICraftingProvider.class},
                (proxy, method, args) -> { throw new AssertionError("Provider must not be called"); });
        assertNull(NeoEcoFastPathCompat.prepareExact(provider, null, new appeng.api.stacks.KeyCounter[0],
                java.math.BigInteger.TEN, null, java.util.UUID.randomUUID()));
        assertDoesNotThrow(() -> NeoEcoFastPathCompat.cancelJob(null, java.util.UUID.randomUUID()));
    }

    @Test void absentPlatformApiRetainsOrdinaryDispatch() {
        assertNull(NeoEcoFastPathCompat.loadAdapter(getClass().getClassLoader()));
    }

    @Test void deniedOptionalApiIsDeclinedBeforeTheFirstCpuDispatch() {
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("cn.dancingsnow.neoecoae.")) throw new SecurityException("denied optional API");
                return super.loadClass(name, resolve);
            }
        };
        assertNull(NeoEcoBatchApi.load(loader));
        assertNull(NeoEcoAllocatedApi.load(loader));
        assertNull(NeoEcoFastPathCompat.loadAdapter(loader));
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
