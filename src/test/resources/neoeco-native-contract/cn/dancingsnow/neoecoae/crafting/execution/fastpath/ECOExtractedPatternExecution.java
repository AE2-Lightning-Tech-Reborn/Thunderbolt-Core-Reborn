package cn.dancingsnow.neoecoae.crafting.execution.fastpath;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Contract fixture only; does not execute a recipe or represent an ECO machine. */
public final class ECOExtractedPatternExecution {
    public static boolean emptyOutputs;
    public enum Type { NORMAL }
    public static ECOExtractedPatternExecution fromProviderPush(
            IPatternDetails pattern, KeyCounter[] inputs, Level level) {
        return new ECOExtractedPatternExecution();
    }
    public List<GenericStack> expectedOutputs() {
        return emptyOutputs ? List.of() : List.of(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 7));
    }
    public List<GenericStack> expectedContainerItems() { return List.of(); }
    public Type fastPathType() { return Type.NORMAL; }
}
