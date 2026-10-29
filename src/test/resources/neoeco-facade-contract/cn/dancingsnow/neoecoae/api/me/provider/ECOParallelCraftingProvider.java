package cn.dancingsnow.neoecoae.api.me.provider;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import java.util.UUID;

public interface ECOParallelCraftingProvider {
    int eco$getAvailableParallelSlots();
    boolean eco$pushPatternBatch(IPatternDetails pattern, KeyCounter[] inputTotal, long copies, UUID jobId);
}
