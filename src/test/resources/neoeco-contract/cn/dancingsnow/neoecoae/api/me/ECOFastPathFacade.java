package cn.dancingsnow.neoecoae.api.me;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import cn.dancingsnow.neoecoae.api.me.provider.ECOJobAwareCraftingProvider;
import java.math.BigInteger;
import java.util.UUID;
import net.minecraft.world.level.Level;

/** A controllable contract fixture, not an implementation of NeoECO machine behavior. */
public final class ECOFastPathFacade {
    public static UUID preparedJob, cancelledJob;
    public static BigInteger requested, exactCount;
    public static KeyCounter[] preparedInputs;
    public static long allocatedCount;
    public static boolean accepted = true;
    public static RuntimeException prepareFailure, submitFailure, cancelFailure;
    public static int preparations, submissions, cancellations;

    public static boolean pushPatternForJob(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, UUID jobId) {
        return ((ECOJobAwareCraftingProvider) provider).pushPattern(pattern, inputs, jobId);
    }

    public static Allocated prepareAllocated(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, long count, Level level, UUID jobId) {
        preparedJob = jobId;
        return allocatedCount == 0 ? null : new Allocated(allocatedCount);
    }

    public interface Reservation { void commit(); void refund(); }
    public interface EnergyAccount { Reservation reserve(double energy); }
    public record Allocated(long craftCount) {
        public boolean submit(EnergyAccount account) {
            submissions++;
            if (submitFailure != null) throw submitFailure;
            return accepted;
        }
    }

    public static Exact prepareExactAllocated(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, BigInteger count, Level level, UUID jobId) {
        preparations++;
        preparedJob = jobId;
        preparedInputs = inputs;
        requested = count;
        if (prepareFailure != null) throw prepareFailure;
        return exactCount == null ? null : new Exact(exactCount);
    }

    public record Exact(BigInteger craftCount) {
        public boolean submit() {
            submissions++;
            if (submitFailure != null) throw submitFailure;
            return accepted;
        }
    }

    public static void cancelJob(Level level, UUID jobId) {
        cancellations++;
        cancelledJob = jobId;
        if (cancelFailure != null) throw cancelFailure;
    }
}
