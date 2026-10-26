package cn.dancingsnow.neoecoae.api.me;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.Level;

/** Isolated public API fixture. It supplies no production NeoECO machine implementation. */
public final class ECOFastPathFacade {
    public static UUID cancelled;
    public interface EnergyAccount { Reservation reserve(double amount); }
    public interface Reservation { void commit(); void refund(); }
    public static void cancelJob(Level level, UUID job) { cancelled = job; }

    public static boolean pushPatternForJob(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, UUID job) {
        if (!(provider instanceof Provider p)) return provider.pushPattern(pattern, inputs);
        p.ordinaryCalls++;
        p.ordinaryJob = job;
        if (p.failOrdinary) throw new IllegalStateException("ordinary ownership unknown");
        if (p.rejectOrdinary) return false;
        p.accepted++;
        return true;
    }

    public static PreparedBatch prepareAllocated(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, long requested, Level level, UUID job) {
        Provider p = (Provider) provider;
        p.preparations++;
        p.preparedJob = job;
        p.preparedInputs = inputs;
        if (p.failPrepare) throw new IllegalStateException("prepare failed before ownership");
        if (p.noPreparation) return null;
        long count = p.invalidCount != 0 ? p.invalidCount : Math.min(requested, p.capacity);
        return new PreparedBatch(p, count, job, inputs);
    }

    public static PreparedExactBatch prepareExactAllocated(ICraftingProvider provider, IPatternDetails pattern,
            KeyCounter[] inputs, BigInteger requested, Level level, UUID job) {
        Provider p = (Provider) provider;
        p.preparedJob = job;
        return p.noPreparation ? null : new PreparedExactBatch(p, requested, job);
    }

    public static final class PreparedBatch {
        private final Provider provider;
        private final long count;
        private final UUID job;
        private final KeyCounter[] inputs;
        PreparedBatch(Provider p, long count, UUID job, KeyCounter[] inputs) {
            provider = p; this.count = count; this.job = job; this.inputs = inputs;
        }
        public long craftCount() { return count; }
        public boolean submit(EnergyAccount energy) {
            provider.submissions++;
            provider.submittedJob = job;
            if (provider.failSubmit) throw new IllegalStateException("submission ownership unknown");
            var reservation = energy.reserve(provider.energyCharge);
            if (provider.reject) { reservation.refund(); return false; }
            reservation.commit();
            provider.accepted += count;
            provider.submittedInputs = inputs;
            return true;
        }
    }

    public static final class PreparedExactBatch {
        private final Provider provider;
        private final BigInteger count;
        private final UUID job;
        PreparedExactBatch(Provider p, BigInteger count, UUID job) {
            provider = p; this.count = count; this.job = job;
        }
        public BigInteger craftCount() { return count; }
        public boolean submit() {
            provider.submittedJob = job;
            provider.exactAccepted = provider.exactAccepted.add(count);
            return true;
        }
    }

    public static final class Provider implements ICraftingProvider, ECOFastPathDispatchProvider {
        public long capacity = 8, invalidCount, accepted;
        public int preparations, submissions, ordinaryCalls, rawCalls;
        public boolean noPreparation, failPrepare, failSubmit, failOrdinary, reject, rejectOrdinary, busy;
        public double energyCharge;
        public UUID preparedJob, submittedJob, ordinaryJob;
        public KeyCounter[] preparedInputs, submittedInputs;
        public BigInteger exactAccepted = BigInteger.ZERO;
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return busy; }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { rawCalls++; return false; }
    }
}
