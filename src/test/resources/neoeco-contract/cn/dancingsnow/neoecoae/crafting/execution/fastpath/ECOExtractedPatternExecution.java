package cn.dancingsnow.neoecoae.crafting.execution.fastpath;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import java.util.List;
import net.minecraft.world.level.Level;

/** Empty outputs deliberately decline batch preparation to exercise ordinary fallback. */
public final class ECOExtractedPatternExecution {
    public enum RecipeType { NORMAL }
    public static ECOExtractedPatternExecution fromProviderPush(
            IPatternDetails pattern, KeyCounter[] inputs, Level level) {
        return new ECOExtractedPatternExecution();
    }
    public List<GenericStack> expectedOutputs() { return List.of(); }
    public List<GenericStack> expectedContainerItems() { return List.of(); }
    public RecipeType fastPathType() { return RecipeType.NORMAL; }
}
