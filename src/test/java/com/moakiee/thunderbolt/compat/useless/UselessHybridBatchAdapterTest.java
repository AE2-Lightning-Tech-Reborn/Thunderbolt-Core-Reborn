package com.moakiee.thunderbolt.compat.useless;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.api.crafting.batch.BatchProviderResolver;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.Test;

class UselessHybridBatchAdapterTest {
    static {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }

    @Test
    void rawPatternsStayOnTheBigintAdapter() throws Exception {
        var provider = new Provider();
        var bigint = new RecordingResolver();
        var hybrid = new UselessHybridBatchAdapter(bigint,
                new UselessScaledBatchAdapter(Provider.class, Patterns.class));
        var endpoint = hybrid.resolve(provider);
        var raw = new Pattern(1, 100);
        assertEquals(42, endpoint.getBatchCapacity(raw));
        assertEquals(4, endpoint.pushBatch(raw, template(2), 5));
        assertEquals(1, bigint.capacityCalls);
        assertEquals(1, bigint.pushCalls);
        assertEquals(0, provider.calls, "raw patterns must not be folded through the ordinary path");
    }

    @Test
    void wrappedPatternsGoToTheScaledAdapter() throws Exception {
        var provider = new Provider();
        var bigint = new RecordingResolver();
        var hybrid = new UselessHybridBatchAdapter(bigint,
                new UselessScaledBatchAdapter(Provider.class, Patterns.class));
        var endpoint = hybrid.resolve(provider);
        var wrapped = new Pattern(4, 20);
        assertEquals(5, endpoint.getBatchCapacity(wrapped));
        var inputs = template(8);
        assertEquals(4, endpoint.pushBatch(wrapped, inputs, 9));
        assertEquals(1, provider.calls);
        assertEquals(20, ((Pattern) provider.received).factor(),
                "the accepted copies must be folded into the pushed pattern");
        assertEquals(40, provider.amount);
        assertEquals(0, bigint.capacityCalls, "wrapped patterns must not reach the bigint API");
        assertEquals(0, bigint.pushCalls);
    }

    private static KeyCounter[] template(long count) {
        var counter = new KeyCounter();
        counter.add(AEItemKey.of(Items.STONE), count);
        return new KeyCounter[] { counter };
    }

    public static final class Patterns {
        public static IPatternDetails scale(IPatternDetails p, long count) {
            var pattern = (Pattern) p;
            return new Pattern(Math.multiplyExact(pattern.factor(), count), pattern.maximum());
        }

        public static IPatternDetails unwrap(IPatternDetails p) { return p; }

        public static long operationsPerPush(IPatternDetails p) { return ((Pattern) p).factor(); }

        public static long maximumSafeMultiplier(IPatternDetails p) { return ((Pattern) p).maximum(); }
    }

    private record Pattern(long factor, long maximum) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(AEItemKey.of(Items.SAND), 1));
        }
    }

    private static final class RecordingResolver implements BatchProviderResolver {
        int capacityCalls;
        int pushCalls;

        @Override public boolean cacheResolutionAcrossTicks() { return true; }

        @Override public IBatchCraftingProvider resolve(ICraftingProvider provider) {
            return new IBatchCraftingProvider() {
                @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
                @Override public boolean isBusy() { return false; }

                @Override public long getBatchCapacity(IPatternDetails details) {
                    capacityCalls++;
                    return 42L;
                }

                @Override public long pushBatch(IPatternDetails details, KeyCounter[] oneCopyTemplate,
                                               long maxCraft) {
                    pushCalls++;
                    return maxCraft - 1L;
                }
            };
        }
    }

    private static final class Provider implements ICraftingProvider {
        int calls;
        long amount;
        IPatternDetails received;

        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return false; }

        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] input) {
            calls++;
            received = pattern;
            amount = input[0].get(AEItemKey.of(Items.STONE));
            input[0].reset();
            return true;
        }
    }
}