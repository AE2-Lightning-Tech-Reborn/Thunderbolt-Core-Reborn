package com.moakiee.thunderbolt.compat.neoeco;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class NeoEcoProviderResolutionTest {
    @Test void absentAllocatedApiCannotClaimOrdinaryProviders() {
        assertFalse(NeoEcoAllocatedApi.available());
        assertFalse(NeoEcoAllocatedApi.supports(new Object()));
        assertNull(NeoEcoFastPathCompat.createAdapter());
    }
}
