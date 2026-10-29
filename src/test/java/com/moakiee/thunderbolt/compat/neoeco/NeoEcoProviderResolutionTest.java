package com.moakiee.thunderbolt.compat.neoeco;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class NeoEcoProviderResolutionTest {
    @Test void absentAllocatedApiCannotClaimOrdinaryProviders() {
        assertNull(NeoEcoAllocatedApi.load(getClass().getClassLoader()));
        assertNull(NeoEcoFastPathCompat.createAdapter());
    }
}
