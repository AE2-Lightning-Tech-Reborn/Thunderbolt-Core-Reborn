package cn.dancingsnow.neoecoae.crafting.execution.worker;

import java.util.UUID;
import net.minecraft.world.level.Level;

public final class ECOCraftingJobLifecycle {
    public static int calls;
    public static UUID job;
    public static RuntimeException failure;
    public static void cancelAndRecover(Level level, UUID id) {
        calls++;
        job = id;
        if (failure != null) throw failure;
    }
}
