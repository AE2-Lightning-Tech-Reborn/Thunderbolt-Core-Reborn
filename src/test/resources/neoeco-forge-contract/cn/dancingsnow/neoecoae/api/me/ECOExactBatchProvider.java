package cn.dancingsnow.neoecoae.api.me;

import java.math.BigInteger;
import java.util.function.BooleanSupplier;

/** Forge contract fixture only; this is not a machine implementation. */
public interface ECOExactBatchProvider {
    Preparation eco$prepareExactBatch(ECOBatchDispatchContext context, BigInteger requested);
    record Preparation(BigInteger craftCount, BooleanSupplier dispatch) {}
}
