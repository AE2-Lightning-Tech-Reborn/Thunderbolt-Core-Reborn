package com.moakiee.thunderbolt.compat.neoeco;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NeoEcoFastPathCompatTest extends com.moakiee.thunderbolt.test.MinecraftComponentsTestBase {
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
