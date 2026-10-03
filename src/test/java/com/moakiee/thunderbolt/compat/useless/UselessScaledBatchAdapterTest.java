package com.moakiee.thunderbolt.compat.useless;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.LoadingModList;
import org.junit.jupiter.api.Test;

class UselessScaledBatchAdapterTest {
    static {
        LoadingModList.of(java.util.List.of(), java.util.List.of(), null);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }
    @Test void nestedMultiplierPartialAcceptanceAndBorrowedInputs() throws Exception {
        var provider = new Provider();
        var endpoint = new UselessScaledBatchAdapter(Provider.class, Patterns.class).resolve(provider);
        var pattern = new Pattern(4, 20);
        assertEquals(5, endpoint.getBatchCapacity(pattern));
        var inputs = template(8);
        assertEquals(4, endpoint.pushBatch(pattern, inputs, 9));
        assertEquals(20, ((Pattern) provider.received).factor());
        assertEquals(40, provider.amount);
        assertEquals(8, inputs[0].get(AEItemKey.of(Items.STONE)));
        assertEquals(1, provider.calls);
        provider.busy = true;
        assertEquals(0, endpoint.getBatchCapacity(pattern));
        assertEquals(9, endpoint.pushBatch(pattern, inputs, 9));
        assertEquals(1, provider.calls);
    }
    @Test void actualInputOverflowLimitsTheBatchAndRejectionKeepsOwnership() throws Exception {
        var provider = new Provider();
        var endpoint = new UselessScaledBatchAdapter(Provider.class, Patterns.class).resolve(provider);
        assertEquals(7, endpoint.pushBatch(new Pattern(1, Long.MAX_VALUE), template(Long.MAX_VALUE / 2), 9));
        provider.accept = false;
        assertEquals(9, endpoint.pushBatch(new Pattern(1, 20), template(1), 9));
        provider.fail = true;
        assertThrows(IllegalStateException.class, () -> endpoint.pushBatch(new Pattern(1, 20), template(1), 9));
        assertEquals(3, provider.calls, "a throwing push must not be retried");
    }
    private static KeyCounter[] template(long count) {
        var counter = new KeyCounter(); counter.add(AEItemKey.of(Items.STONE), count);
        return new KeyCounter[] { counter };
    }
    public static final class Patterns {
        public static IPatternDetails scale(IPatternDetails p, long count) {
            var pattern = (Pattern) p; return new Pattern(Math.multiplyExact(pattern.factor(), count), pattern.maximum());
        }
        public static IPatternDetails unwrap(IPatternDetails p) { return p; }
        public static long operationsPerPush(IPatternDetails p) { return ((Pattern) p).factor(); }
        public static long maximumSafeMultiplier(IPatternDetails p) { return ((Pattern) p).maximum(); }
    }
    private record Pattern(long factor, long maximum) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public GenericStack[] getOutputs() { return new GenericStack[] {new GenericStack(AEItemKey.of(Items.SAND), 1)}; }
    }
    private static final class Provider implements ICraftingProvider {
        boolean busy, fail, accept = true; int calls; long amount; IPatternDetails received;
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return busy; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] input) {
            calls++; received = pattern; amount = input[0].get(AEItemKey.of(Items.STONE));
            input[0].reset();
            if (fail) throw new IllegalStateException("ownership unknown");
            return accept;
        }
    }
}
