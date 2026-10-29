package cn.dancingsnow.neoecoae.api.me;

public interface ECOBatchCapacityProvider {
    long eco$getBatchCapacity(ECOBatchDispatchContext context);
    boolean eco$pushBatch(ECOBatchDispatchContext context, long copies);
}
