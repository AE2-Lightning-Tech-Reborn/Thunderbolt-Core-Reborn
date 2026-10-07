package com.moakiee.thunderbolt.compat;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderAdapters;
import com.moakiee.thunderbolt.compat.gtl.GtlCompat;
import org.junit.jupiter.api.Test;

class OptionalBatchProvidersGtlTest {
    @Test
    void cpuHandoverSkipsOptionalProtocolsBeforeLoadingTheirApis() {
        String previous = System.getProperty(GtlCompat.MODE_PROPERTY);
        try {
            System.setProperty(GtlCompat.MODE_PROPERTY, "always");
            var definitions = BatchProviderAdapters.entries();
            OptionalBatchProviders.register();
            assertSame(definitions, BatchProviderAdapters.entries());
        } finally {
            if (previous == null) System.clearProperty(GtlCompat.MODE_PROPERTY);
            else System.setProperty(GtlCompat.MODE_PROPERTY, previous);
        }
    }
}
