package cn.dancingsnow.neoecoae.blocks.entity.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import cn.dancingsnow.neoecoae.api.me.ECOBatchCapacityProvider;
import cn.dancingsnow.neoecoae.api.me.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.ECOExactBatchProvider;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** Minimal signatures used by the ordinary Forge upstream adapter. */
public final class ECOCraftingPatternBusBlockEntity
        implements ICraftingProvider, ECOBatchCapacityProvider, ECOExactBatchProvider {
    public int directPushes, nativePushes, preparations, submissions;
    public UUID ordinaryJob;
    public KeyCounter[] ordinaryInputs;
    public ECOBatchDispatchContext context;
    public BigInteger requested, admitted = BigInteger.TEN;
    public boolean accept = true, decline, missingDispatch;
    public RuntimeException ordinaryFailure, prepareFailure, submitFailure;

    public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
    public boolean isBusy() { return false; }
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
        directPushes++;
        return accept;
    }
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs, UUID job) {
        nativePushes++;
        ordinaryJob = job;
        ordinaryInputs = inputs;
        if (ordinaryFailure != null) throw ordinaryFailure;
        return accept;
    }
    public long eco$getBatchCapacity(ECOBatchDispatchContext context) { return 0; }
    public boolean eco$pushBatch(ECOBatchDispatchContext context, long copies) {
        throw new AssertionError("Zero-capacity provider must not receive a batch");
    }
    public Preparation eco$prepareExactBatch(ECOBatchDispatchContext context, BigInteger requested) {
        preparations++;
        this.context = context;
        this.requested = requested;
        if (prepareFailure != null) throw prepareFailure;
        if (decline) return null;
        return new Preparation(admitted, missingDispatch ? null : () -> {
            submissions++;
            if (submitFailure != null) throw submitFailure;
            return accept;
        });
    }
}
