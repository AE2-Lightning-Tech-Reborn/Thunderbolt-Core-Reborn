package com.moakiee.thunderbolt.compat.neoeco;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import java.util.List;
class NeoEcoFastPathBatchAdapterTest {
    @Test void providerWithoutAllocatedApiRemainsOnOrdinaryDispatch() {
        ICraftingProvider provider = new ICraftingProvider() {
            public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
            public boolean isBusy() { return false; }
            public boolean pushPattern(IPatternDetails p, KeyCounter[] inputs) { return true; }
        };
        assertNull(new NeoEcoFastPathBatchAdapter().resolve(provider));
    }
}
