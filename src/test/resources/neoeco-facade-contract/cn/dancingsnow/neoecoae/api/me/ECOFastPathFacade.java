package cn.dancingsnow.neoecoae.api.me;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import cn.dancingsnow.neoecoae.api.me.provider.ECOParallelCraftingProvider;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.Level;

/** Public 1.20.1 facade signatures; not a simulation of a NeoECO machine or its material planner. */
public final class ECOFastPathFacade {
    public interface EnergyAccount { Reservation reserve(double amount); }
    public interface Reservation { void commit(); void refund(); }

    public static PreparedBatch prepareAllocated(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, long copies, Level level, UUID jobId) {
        if (!(provider instanceof Provider p)) return null;
        p.preparations++;
        p.inputs = inputs;
        p.jobId = jobId;
        if (p.prepareFailure != null) throw p.prepareFailure;
        if (p.decline) return null;
        long admitted = p.invalidCount == null ? Math.min(copies, p.capacity) : p.invalidCount;
        return new PreparedBatch(p, admitted);
    }

    public static PreparedExactBatch prepareExactAllocated(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, BigInteger copies, Level level, UUID jobId) {
        if (!(provider instanceof Provider p)) return null;
        p.preparations++;
        p.inputs = inputs;
        p.jobId = jobId;
        p.requested = copies;
        if (p.prepareFailure != null) throw p.prepareFailure;
        return p.decline ? null : new PreparedExactBatch(p, p.exactCount == null ? copies : p.exactCount);
    }

    public static boolean pushPatternForJob(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, UUID jobId) {
        var p = (BaseProvider) provider;
        p.ordinaryPushes++;
        p.inputs = inputs;
        p.jobId = jobId;
        return p.ordinaryAccept;
    }

    public static final class PreparedBatch {
        private final Provider provider;
        private final long copies;
        private boolean submitted;
        public PreparedBatch(Provider p, long copies) { this.provider = p; this.copies = copies; }
        public long craftCount() { provider.countReads++; return copies; }
        public boolean submit(EnergyAccount energy) {
            if (submitted) throw new IllegalStateException("Batch already submitted");
            submitted = true;
            var reservation = energy.reserve(0);
            provider.submissions++;
            if (provider.submitFailure != null) { reservation.refund(); throw provider.submitFailure; }
            if (!provider.accept) { reservation.refund(); return false; }
            provider.accepted += copies;
            reservation.commit();
            return true;
        }
    }

    public static final class PreparedExactBatch {
        private final Provider provider;
        private final BigInteger copies;
        private boolean submitted;
        public PreparedExactBatch(Provider p, BigInteger copies) { this.provider = p; this.copies = copies; }
        public BigInteger craftCount() { return copies; }
        public boolean submit() {
            if (submitted) throw new IllegalStateException("Exact batch already submitted");
            submitted = true;
            provider.submissions++;
            if (provider.submitFailure != null) throw provider.submitFailure;
            return provider.accept;
        }
    }

    public static class BaseProvider implements ICraftingProvider {
        public int ordinaryPushes, directPushes;
        public boolean ordinaryAccept;
        public UUID jobId;
        public KeyCounter[] inputs;
        public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        public boolean isBusy() { return false; }
        public boolean pushPattern(IPatternDetails p, KeyCounter[] inputs) { directPushes++; return ordinaryAccept; }
    }

    public static final class Provider extends BaseProvider implements ECOFastPathDispatchProvider {
        public long capacity = 8, accepted;
        public Long invalidCount;
        public int preparations, submissions, countReads;
        public boolean decline, accept = true;
        public RuntimeException prepareFailure, submitFailure;
        public BigInteger requested, exactCount;
    }

    public static final class ParallelProvider extends BaseProvider implements ECOParallelCraftingProvider {
        public int slots = 8, parallelPushes;
        public long accepted;
        public boolean accept = true;
        public RuntimeException submitFailure;
        public int eco$getAvailableParallelSlots() { return slots; }
        public boolean eco$pushPatternBatch(IPatternDetails pattern, KeyCounter[] totals, long copies, UUID jobId) {
            parallelPushes++;
            inputs = totals;
            this.jobId = jobId;
            if (submitFailure != null) throw submitFailure;
            if (accept) accepted += copies;
            return accept;
        }
    }
}
