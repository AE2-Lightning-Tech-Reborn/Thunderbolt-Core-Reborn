package cn.dancingsnow.neoecoae.api.me;

/** Minimal older Forge protocol used to verify adapter selection and ordinary fallback. */
public interface ECOBatchCapacityProvider {
    long eco$getBatchCapacity(ECOBatchDispatchContext context);
    boolean eco$pushBatch(ECOBatchDispatchContext context, long count);
}
