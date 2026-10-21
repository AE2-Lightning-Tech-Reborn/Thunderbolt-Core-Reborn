package cn.dancingsnow.neoecoae.crafting.execution.fastpath;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import java.util.List;
import net.minecraft.world.level.Level;

/** Supplies a normal, nonempty context for isolated native protocol tests. */
public record ECOExtractedPatternExecution(List<GenericStack> expectedOutputs) {
    public enum RecipeType { NORMAL }
    public static ECOExtractedPatternExecution fromProviderPush(
            IPatternDetails pattern, KeyCounter[] inputs, Level level) {
        return new ECOExtractedPatternExecution(List.of(pattern.getOutputs()));
    }
    public List<GenericStack> expectedContainerItems() { return List.of(); }
    public RecipeType fastPathType() { return RecipeType.NORMAL; }
}
