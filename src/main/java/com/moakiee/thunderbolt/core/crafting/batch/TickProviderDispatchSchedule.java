package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.ArrayList;
import java.util.function.Supplier;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.me.service.CraftingService;
import com.moakiee.thunderbolt.core.crafting.support.CraftingProviderRevision;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;

/**
 * Per-physical-CPU provider schedule with revision-invalidated candidate snapshots.
 * Stable provider sets retain candidates and success affinity across ticks. Rejections expire
 * lazily on the next use in another tick. Uninstrumented services keep the one-tick cache contract.
 */
public final class TickProviderDispatchSchedule {
    static final int MAX_PATTERNS = 4_096;
    private final Reference2ObjectLinkedOpenHashMap<IPatternDetails, PatternSchedule> patterns =
            new Reference2ObjectLinkedOpenHashMap<>();
    private long tick = Long.MIN_VALUE;
    private Object serviceIdentity;
    private long providerRevision = Long.MIN_VALUE;
    private final BatchProviderResolutionCache batchProviders = new BatchProviderResolutionCache();

    public void beginTick(long currentTick) {
        if (tick == currentTick) return;
        tick = currentTick;
        batchProviders.beginTick(currentTick);
    }

    BatchProviderResolutionCache batchProviders() { return batchProviders; }

    public Iterable<ICraftingProvider> candidates(CraftingService craftingService,
            IPatternDetails providerLookupPattern, IPatternDetails canonicalPattern) {
        long revision = craftingService instanceof CraftingProviderRevision tracked
                ? tracked.thunderbolt$getCraftingProviderRevision() : tick;
        return candidates(craftingService, revision, canonicalPattern,
                () -> craftingService.getProviders(providerLookupPattern));
    }

    Iterable<ICraftingProvider> candidates(Object service, long revision,
            IPatternDetails canonicalPattern, Supplier<Iterable<ICraftingProvider>> source) {
        if (serviceIdentity != service || providerRevision != revision) {
            serviceIdentity = service;
            providerRevision = revision;
            patterns.clear();
            batchProviders.clear();
        }
        var schedule = patterns.getAndMoveToLast(canonicalPattern);
        if (schedule == null) {
            var providers = new ArrayList<ICraftingProvider>();
            source.get().forEach(providers::add);
            schedule = new PatternSchedule(providers);
            if (patterns.size() >= MAX_PATTERNS) patterns.removeFirst();
            patterns.put(canonicalPattern, schedule);
        }
        schedule.beginTick(tick);
        return schedule.candidates;
    }

    public boolean isBlocked(IPatternDetails canonicalPattern, ICraftingProvider provider) {
        var schedule = patterns.get(canonicalPattern);
        if (schedule != null) schedule.beginTick(tick);
        return schedule != null && schedule.candidates.isBlocked(provider);
    }

    public void recordFailure(IPatternDetails canonicalPattern, ICraftingProvider provider) {
        var schedule = patterns.get(canonicalPattern);
        if (schedule != null) {
            schedule.beginTick(tick);
            schedule.candidates.block(provider);
        }
    }

    public void recordSuccess(IPatternDetails canonicalPattern, ICraftingProvider provider) {
        var schedule = patterns.get(canonicalPattern);
        if (schedule != null) {
            schedule.beginTick(tick);
            schedule.candidates.markSuccess(provider);
        }
    }

    public int blockedCount(IPatternDetails canonicalPattern) {
        var schedule = patterns.get(canonicalPattern);
        if (schedule != null) schedule.beginTick(tick);
        return schedule != null ? schedule.candidates.blockedCount() : 0;
    }

    private static final class PatternSchedule {
        private final IdentityCandidateQueue<ICraftingProvider> candidates;
        private long lastTick = Long.MIN_VALUE;
        private PatternSchedule(java.util.List<ICraftingProvider> providers) {
            this.candidates = new IdentityCandidateQueue<>(providers);
        }
        private void beginTick(long tick) {
            if (lastTick == tick) return;
            candidates.restoreBlocked();
            lastTick = tick;
        }
    }
}
