package cn.dancingsnow.neoecoae.blocks.entity.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import cn.dancingsnow.neoecoae.api.me.ECOBatchCapacityProvider;
import cn.dancingsnow.neoecoae.api.me.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.ECOExactBatchProvider;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import cn.dancingsnow.neoecoae.api.me.provider.ECOJobAwareCraftingProvider;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/** Controllable public shape of the native bus, compiled only by contract tests. */
public class ECOCraftingPatternBusBlockEntity implements ECOBatchCapacityProvider, ECOExactBatchProvider,
        ECOJobAwareCraftingProvider, ECOFastPathDispatchProvider {
    public UUID ordinaryJob;
    public ECOBatchDispatchContext context;
    public BigInteger requested, admitted = BigInteger.ONE;
    public int ordinaryCalls, directCalls, preparations, submissions;
    public boolean accepted = true, decline, missingDispatch;
    public Throwable ordinaryFailure, prepareFailure, submitFailure;

    public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
    public boolean isBusy() { return false; }
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
        directCalls++;
        return accepted;
    }
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs, UUID jobId) {
        ordinaryCalls++;
        ordinaryJob = jobId;
        fail(ordinaryFailure);
        if (accepted && inputs.length > 0) inputs[0].reset();
        return accepted;
    }
    public long eco$getBatchCapacity(ECOBatchDispatchContext context) { return 0; }
    public boolean eco$pushBatch(ECOBatchDispatchContext context, long count) {
        throw new AssertionError("Zero capacity must use ordinary dispatch");
    }
    public Preparation eco$prepareExactBatch(ECOBatchDispatchContext context, BigInteger requested) {
        preparations++;
        this.context = context;
        this.requested = requested;
        fail(prepareFailure);
        if (decline) return null;
        return new Preparation(admitted, missingDispatch ? null : () -> {
            submissions++;
            fail(submitFailure);
            return accepted;
        });
    }
    private static void fail(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
}
