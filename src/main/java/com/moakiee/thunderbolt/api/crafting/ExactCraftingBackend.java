package com.moakiee.thunderbolt.api.crafting;

import java.math.BigInteger;
import java.util.concurrent.Future;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import net.minecraft.world.level.Level;

/** Optional exact-quantity implementation of an already registered planning algorithm. */
public interface ExactCraftingBackend {
    /** Checks online execution capability, without reserving a CPU or reading a recipe graph. */
    boolean isAvailable(IGrid grid);

    boolean matchesCpu(ICraftingCPU cpu);

    /** Capture live data before returning; workers must only read that captured snapshot. */
    Future<ICraftingPlan> plan(IGrid grid, Level level, AEKey target, BigInteger amount, IActionSource source);
}
