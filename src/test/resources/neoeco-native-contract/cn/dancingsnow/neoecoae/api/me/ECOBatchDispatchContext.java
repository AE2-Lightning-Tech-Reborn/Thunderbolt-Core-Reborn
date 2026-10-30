package cn.dancingsnow.neoecoae.api.me;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.Level;

public record ECOBatchDispatchContext(IPatternDetails pattern, List<List<GenericStack>> inputs,
        List<GenericStack> outputs, List<GenericStack> containers, Level level, UUID craftingJobId) {
    public KeyCounter[] inputCounters() {
        return inputs.stream().map(slot -> {
            var counter = new KeyCounter();
            slot.forEach(stack -> counter.add(stack.what(), stack.amount()));
            return counter;
        }).toArray(KeyCounter[]::new);
    }
}
