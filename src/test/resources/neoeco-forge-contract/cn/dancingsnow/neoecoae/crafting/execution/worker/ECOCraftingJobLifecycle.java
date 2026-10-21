package cn.dancingsnow.neoecoae.crafting.execution.worker;

import java.util.UUID;
import net.minecraft.world.level.Level;

/** Records native lifecycle notifications without running NeoECO workers. */
public final class ECOCraftingJobLifecycle {
    public static UUID cancelledJob;
    public static int cancellations;
    public static RuntimeException cancelFailure;
    public static void cancelAndRecover(Level level, UUID jobId) {
        cancellations++;
        cancelledJob = jobId;
        if (cancelFailure != null) throw cancelFailure;
    }
}
