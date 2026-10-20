package cn.dancingsnow.neoecoae.api.me.provider;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import java.util.UUID;

/** Test contract only; compiled into an isolated loader, never a production dependency. */
public interface ECOJobAwareCraftingProvider extends ICraftingProvider {
    boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs, UUID jobId);
}
