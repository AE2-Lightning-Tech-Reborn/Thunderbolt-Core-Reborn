package com.moakiee.thunderbolt.core.crafting.batch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyLongMap;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.inv.ListCraftingInventory;

import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.core.crafting.pattern.PlannedInputPattern;

public final class ParallelBatchCpuHelper {
    private static final ThreadLocal<CapacityScope> CAPACITY_LIMITER = new ThreadLocal<>();

    private ParallelBatchCpuHelper() {
    }

    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft) {
        return bulkExtract(details, inv, maxCraft, true, Collections.emptyMap());
    }

    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft,
                                         boolean allowSharedInputs, Map<AEKey, Long> reservedStock) {
        if (details instanceof PlannedInputPattern) {
            return bulkExtract(details, inv, maxCraft, allowSharedInputs, reservedStock, null);
        }
        if (maxCraft <= 0) return null;

        var inputs = details.getInputs();
        int slots = inputs.length;
        var chosenKeys = new AEKey[slots];
        var units = new long[slots];
        var available = new long[slots];
        var shared = new boolean[slots];
        var reserved = reservedStock != null ? reservedStock : Collections.<AEKey, Long>emptyMap();

        for (int slot = 0; slot < slots; slot++) {
            var input = inputs[slot];
            var possibles = input.getPossibleInputs();
            AEKey bestKey = null;
            long bestUnits = 0;
            long bestAvailable = 0;
            long bestCopies = 0;
            boolean bestShared = false;
            for (var possible : possibles) {
                if (possible.what() == null) continue;
                long perCopy = saturatingMultiply(possible.amount(), input.getMultiplier());
                if (perCopy <= 0) continue;
                long inInventory = Math.max(0L,
                        inv.extract(possible.what(), Long.MAX_VALUE, Actionable.SIMULATE)
                                - Math.max(0L, reserved.getOrDefault(possible.what(), 0L)));
                boolean isShared = allowSharedInputs
                        && SharedBatchInputs.isSharedInput(details, slot, possible.what());
                long copies = isShared ? (inInventory >= perCopy ? maxCraft : 0L) : inInventory / perCopy;
                if (copies > bestCopies) {
                    bestKey = possible.what();
                    bestUnits = perCopy;
                    bestAvailable = inInventory;
                    bestCopies = copies;
                    bestShared = isShared;
                    if (copies >= maxCraft) break;
                }
            }
            if (bestKey == null || bestCopies <= 0) return null;
            chosenKeys[slot] = bestKey;
            units[slot] = bestUnits;
            available[slot] = bestAvailable;
            shared[slot] = bestShared;
        }

        var fixedByKey = new AEKeyLongMap<AEKey>(slots);
        var variableByKey = new AEKeyLongMap<AEKey>(slots);
        var availableByKey = new AEKeyLongMap<AEKey>(slots);
        for (int slot = 0; slot < slots; slot++) {
            BatchKeyAmounts.put(availableByKey, chosenKeys[slot], available[slot]);
            BatchKeyAmounts.addTo(shared[slot] ? fixedByKey : variableByKey,
                    chosenKeys[slot], units[slot]);
        }

        long actual = maxCraft;
        for (var entry : availableByKey) {
            long fixed = fixedByKey.getLong(entry.getKey());
            long variable = variableByKey.getLong(entry.getKey());
            if (fixed > entry.getLongValue()) return null;
            if (variable > 0) actual = Math.min(actual, (entry.getLongValue() - fixed) / variable);
            if (actual <= 0) return null;
        }
        var extractedByKey = new AEKeyLongMap<AEKey>(availableByKey.size());
        for (var entry : availableByKey) {
            long need = saturatingAdd(
                    fixedByKey.getLong(entry.getKey()),
                    saturatingMultiply(variableByKey.getLong(entry.getKey()), actual));
            long got = inv.extract(entry.getKey(), need, Actionable.MODULATE);
            BatchKeyAmounts.put(extractedByKey, entry.getKey(), got);
            if (got < need) {
                for (var rollback : extractedByKey) {
                    if (rollback.getLongValue() > 0) {
                        inv.insert(rollback.getKey(), rollback.getLongValue(), Actionable.MODULATE);
                    }
                }
                return null;
            }
        }

        var scaled = new KeyCounter[slots];
        for (int slot = 0; slot < slots; slot++) {
            scaled[slot] = counterWithCapacity(1);
            long amount = shared[slot] ? units[slot] : saturatingMultiply(units[slot], actual);
            if (amount > 0) scaled[slot].add(chosenKeys[slot], amount);
        }
        return new BulkResult(scaled, actual, chosenKeys, units, shared);
    }

    /**
     * Resolves the first concrete copy with AE2's native substitution rules, then scales only that
     * homogeneous input set. Different component variants therefore become separate batches.
     */
    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft,
                                         boolean allowSharedInputs, Map<AEKey, Long> reservedStock,
                                         Level level) {
        return bulkExtract(details, inv, maxCraft, allowSharedInputs, reservedStock, level,
                currentBatchCapacityLimiter(details, inv));
    }

    @FunctionalInterface
    public interface BatchCapacityLimiter {
        long limit(KeyCounter[] oneCopy, long availableCopies);
    }

    /** Preserves the established extraction call site used by host-mod allocation Mixins. */
    public static <T> T withBatchCapacityLimiter(IPatternDetails details, ListCraftingInventory inv,
                                                BatchCapacityLimiter limiter,
                                                java.util.function.Supplier<T> extraction) {
        try (var scope = limitBatchCapacity(details, inv, limiter)) {
            return extraction.get();
        }
    }

    public static BatchCapacityScope limitBatchCapacity(IPatternDetails details, ListCraftingInventory inv,
                                                         BatchCapacityLimiter limiter) {
        return new BatchCapacityScope(new CapacityScope(details, inv, limiter));
    }

    public static final class BatchCapacityScope implements AutoCloseable {
        private final CapacityScope previous;
        private boolean closed;
        private BatchCapacityScope(CapacityScope current) {
            previous = CAPACITY_LIMITER.get();
            CAPACITY_LIMITER.set(current);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CAPACITY_LIMITER.remove();
            else CAPACITY_LIMITER.set(previous);
        }
    }

    @Nullable
    public static BatchCapacityLimiter currentBatchCapacityLimiter(IPatternDetails details, ListCraftingInventory inv) {
        var scope = CAPACITY_LIMITER.get();
        return scope != null && scope.pattern == details && scope.inventory == inv ? scope.limiter : null;
    }

    private record CapacityScope(IPatternDetails pattern, ListCraftingInventory inventory,
                                 BatchCapacityLimiter limiter) { }

    @Nullable
    public static BulkResult bulkExtract(IPatternDetails details, ListCraftingInventory inv, long maxCraft,
                                         boolean allowSharedInputs, Map<AEKey, Long> reservedStock,
                                         Level level, @Nullable BatchCapacityLimiter capacityLimiter) {
        if (maxCraft <= 0) return null;

        var guardedInventory = new ReservedInventory(inv, reservedStock);
        var resolved = extractOneCopy(details, guardedInventory, allowSharedInputs, level);
        if (resolved == null) return null;

        int slots = resolved.inputs.length;
        var scalablePerCopy = new KeyCounter[slots];
        var sharedPerBatch = new KeyCounter[slots];
        var scalableDemand = new AEKeyLongMap<AEKey>(slots);
        for (int slot = 0; slot < slots; slot++) {
            boolean hasShared = false;
            if (allowSharedInputs) {
                for (var entry : resolved.inputs[slot]) {
                    if (SharedBatchInputs.isSharedInput(details, slot, entry.getKey())) {
                        hasShared = true;
                        break;
                    }
                }
            }
            // The resolved copy is owned here. Reuse it for ordinary slots, keeping
            // provider-owned copies and the mutable scaled inventory separate.
            sharedPerBatch[slot] = hasShared
                    ? counterWithCapacity(resolved.inputs[slot].size()) : new KeyCounter();
            scalablePerCopy[slot] = hasShared
                    ? counterWithCapacity(resolved.inputs[slot].size()) : resolved.inputs[slot];
            for (var entry : resolved.inputs[slot]) {
                boolean shared = hasShared
                        && SharedBatchInputs.isSharedInput(details, slot, entry.getKey());
                if (hasShared) {
                    var target = shared ? sharedPerBatch[slot] : scalablePerCopy[slot];
                    target.add(entry.getKey(), entry.getLongValue());
                }
                if (!shared) {
                    BatchKeyAmounts.addTo(scalableDemand, entry.getKey(), entry.getLongValue());
                }
            }
        }

        long additionalCopies = maxCraft - 1;
        for (var entry : scalableDemand) {
            long available = guardedInventory.extract(entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
            additionalCopies = Math.min(additionalCopies, available / entry.getLongValue());
        }

        if (capacityLimiter != null) {
            long limitedCopies;
            try {
                limitedCopies = Math.min(additionalCopies + 1,
                        capacityLimiter.limit(resolved.inputs, additionalCopies + 1));
            } catch (RuntimeException | Error failure) {
                CraftingCpuHelper.reinjectPatternInputs(guardedInventory, resolved.inputs);
                throw failure;
            }
            if (limitedCopies <= 0) {
                CraftingCpuHelper.reinjectPatternInputs(guardedInventory, resolved.inputs);
                return null;
            }
            additionalCopies = limitedCopies - 1;
        }

        if (additionalCopies > 0) {
            var additionalExtracted = new AEKeyLongMap<AEKey>(scalableDemand.size());
            for (var entry : scalableDemand) {
                long needed = saturatingMultiply(entry.getLongValue(), additionalCopies);
                long extracted = guardedInventory.extract(entry.getKey(), needed, Actionable.MODULATE);
                BatchKeyAmounts.put(additionalExtracted, entry.getKey(), extracted);
                if (extracted < needed) {
                    for (var rollback : additionalExtracted) {
                        guardedInventory.insert(rollback.getKey(), rollback.getLongValue(), Actionable.MODULATE);
                    }
                    CraftingCpuHelper.reinjectPatternInputs(guardedInventory, resolved.inputs);
                    return null;
                }
            }
        }

        long actualCopies = additionalCopies + 1;
        var scaled = new KeyCounter[slots];
        for (int slot = 0; slot < slots; slot++) {
            scaled[slot] = counterWithCapacity(sharedPerBatch[slot].size() + scalablePerCopy[slot].size());
            scaled[slot].addAll(sharedPerBatch[slot]);
            addScaled(scaled[slot], scalablePerCopy[slot], actualCopies);
        }
        return new BulkResult(
                scaled, actualCopies, scalablePerCopy, sharedPerBatch, resolved.remainders);
    }

    @Nullable
    public static KeyCounter[] extractPatternInputs(IPatternDetails details, ICraftingInventory inventory,
                                                    Level level, KeyCounter outputs, KeyCounter containers) {
        if (!(details instanceof PlannedInputPattern)) {
            return CraftingCpuHelper.extractPatternInputs(details, inventory, level, outputs, containers);
        }
        var resolved = extractOneCopy(details, inventory, false, level);
        if (resolved == null) return null;
        for (var output : details.getOutputs()) outputs.add(output.what(), output.amount());
        for (var remainder : resolved.remainders) containers.add(remainder.key, remainder.count);
        return resolved.inputs;
    }

    @Nullable
    private static ResolvedCopy extractOneCopy(IPatternDetails details,
                                               ICraftingInventory inventory,
                                               boolean allowSharedInputs,
                                               Level level) {
        var inputs = details.getInputs();
        var resolved = new KeyCounter[inputs.length];
        ArrayList<RemainderSpec> remainders = null;
        int[] extractionOrder = null;
        if (details instanceof PlannedInputPattern planned) {
            extractionOrder = new int[inputs.length];
            int next = 0;
            // Resolve fixed allocations before dynamic slots, which may also accept those keys.
            // Keep the result indexed by the original slot so provider semantics do not change.
            for (int slot = 0; slot < inputs.length; slot++) {
                if (!planned.allocations().get(slot).isEmpty()) extractionOrder[next++] = slot;
            }
            for (int slot = 0; slot < inputs.length; slot++) {
                if (planned.allocations().get(slot).isEmpty()) extractionOrder[next++] = slot;
            }
        }
        for (int index = 0; index < inputs.length; index++) {
            int slot = extractionOrder == null ? index : extractionOrder[index];
            var input = inputs[slot];
            var holder = resolved[slot] = counterWithCapacity(1);
            var slotInventory = details instanceof PlannedInputPattern planned
                    ? planned.inventoryForSlot(slot, inventory) : inventory;
            long remainingMultiplier = input.getMultiplier();
            for (var template : CraftingCpuHelper.getValidItemTemplates(slotInventory, input, level)) {
                long extracted = CraftingCpuHelper.extractTemplates(
                        slotInventory, template, remainingMultiplier);
                if (extracted <= 0) continue;

                holder.add(template.what(), saturatingMultiply(extracted, template.amount()));
                var remaining = input.getRemainingKey(template.what());
                if (remaining != null) {
                    boolean shared = allowSharedInputs
                            && SharedBatchInputs.isSharedInput(details, slot, template.what());
                    if (remainders == null) remainders = new ArrayList<>();
                    remainders.add(new RemainderSpec(remaining, extracted, shared));
                }
                remainingMultiplier -= extracted;
                if (remainingMultiplier == 0) break;
            }
            if (remainingMultiplier > 0 || (details instanceof PlannedInputPattern planned
                    && !planned.matchesAllocation(slot, holder))) {
                CraftingCpuHelper.reinjectPatternInputs(inventory, resolved);
                return null;
            }
        }
        // This list is locally owned and never exposed to providers or callers.
        return new ResolvedCopy(resolved, remainders == null ? Collections.emptyList() : remainders);
    }

    public static void reinject(BulkResult result, long leftoverCopies, ListCraftingInventory inv) {
        if (leftoverCopies <= 0) return;
        long returnedCopies = Math.min(leftoverCopies, result.remainingCopies);
        // A completed refund also released the one shared seed. Do not repeat that transfer
        // when the caller retries with a stale leftover count.
        if (returnedCopies <= 0) return;
        for (int slot = 0; slot < result.scaledInputs.length; slot++) {
            for (var entry : result.scalablePerCopy[slot]) {
                long amount = saturatingMultiply(entry.getLongValue(), returnedCopies);
                if (amount > 0) {
                    inv.insert(entry.getKey(), amount, Actionable.MODULATE);
                    result.scaledInputs[slot].remove(entry.getKey(), amount);
                }
            }
        }
        result.remainingCopies -= returnedCopies;
        if (result.remainingCopies == 0 && !result.sharedDispatched) result.reinjectShared(inv);
    }

    public static void registerExpectedOutputs(BatchJobView job, IPatternDetails details,
                                               BulkResult result, long dispatched) {
        if (dispatched <= 0) return;
        registerPatternOutputs(job, details, dispatched, result.hasSharedInputs());
        if (result.remainders != null) {
            for (var remainder : result.remainders) {
                long copies = remainder.shared ? 1L : dispatched;
                long count = saturatingMultiply(remainder.count, copies);
                job.insertWaitingFor(remainder.key, count);
                job.addContainerMaxItems(count, remainder.key.getType());
            }
        } else {
            registerLegacyRemainders(
                    job, details, result.keys, result.sharedInputs, dispatched);
        }
    }

    public static void registerExpectedOutputs(BatchJobView job, IPatternDetails details,
                                               AEKey[] chosenKeys, long dispatched) {
        if (dispatched <= 0) return;
        boolean sharedBatch = false;
        if (chosenKeys != null) {
            for (int slot = 0; slot < chosenKeys.length; slot++) {
                if (SharedBatchInputs.isSharedInput(details, slot, chosenKeys[slot])) {
                    sharedBatch = true;
                    break;
                }
            }
        }
        registerPatternOutputs(job, details, dispatched, sharedBatch);
        registerLegacyRemainders(job, details, chosenKeys, null, dispatched);
    }

    private static void registerPatternOutputs(
            BatchJobView job, IPatternDetails details, long dispatched, boolean sharedBatch) {
        var sharedPattern = sharedBatch && details instanceof SharedBatchInputPattern pattern
                ? pattern : null;
        var outputs = details.getOutputs();
        var sharedOutputsLeft = sharedPattern == null ? null : new AEKeyLongMap<AEKey>(outputs.length);
        for (var output : outputs) {
            long sharedAmount = 0L;
            if (sharedPattern != null) {
                long remainingShared = sharedOutputsLeft.containsKey(output.what())
                        ? sharedOutputsLeft.getLong(output.what())
                        : sharedPattern.sharedBatchOutputAmount(output.what());
                sharedAmount = Math.min(output.amount(), Math.max(0L, remainingShared));
                BatchKeyAmounts.put(sharedOutputsLeft, output.what(), remainingShared - sharedAmount);
            }
            long scalable = Math.max(0L, output.amount() - sharedAmount);
            job.insertWaitingFor(output.what(), saturatingAdd(
                    sharedAmount, saturatingMultiply(scalable, dispatched)));
        }
    }

    private static void registerLegacyRemainders(BatchJobView job, IPatternDetails details,
                                                 AEKey[] chosenKeys, boolean[] shared, long dispatched) {
        var inputs = details.getInputs();
        for (int slot = 0; slot < inputs.length; slot++) {
            var input = inputs[slot];
            var possibles = input.getPossibleInputs();
            if (possibles.length == 0) continue;
            AEKey consumed = chosenKeys != null && slot < chosenKeys.length && chosenKeys[slot] != null
                    ? chosenKeys[slot] : possibles[0].what();
            AEKey remaining = input.getRemainingKey(consumed);
            if (remaining != null) {
                boolean sharedInput = shared != null && slot < shared.length
                        ? shared[slot]
                        : SharedBatchInputs.isSharedInput(details, slot, consumed);
                long copies = sharedInput ? 1L : dispatched;
                // CraftingCpuHelper registers one remainder per completed template operation;
                // the possible stack's physical amount only affects extraction, not return count.
                long perCopy = input.getMultiplier();
                long count = saturatingMultiply(perCopy, copies);
                job.insertWaitingFor(remaining, count);
                job.addContainerMaxItems(count, remaining.getType());
            }
        }
    }

    public static KeyCounter[] cloneSingleCopy(BulkResult result) {
        return copySlice(result, 1);
    }

    public static KeyCounter[] copySlice(BulkResult result, long sliceCount) {
        var slice = new KeyCounter[result.scaledInputs.length];
        for (int slot = 0; slot < slice.length; slot++) {
            if (sliceCount == 1 && result.sharedPerBatch[slot].isEmpty()) {
                slice[slot] = result.scalablePerCopy[slot].copy();
                continue;
            }
            slice[slot] = counterWithCapacity(
                    result.sharedPerBatch[slot].size() + result.scalablePerCopy[slot].size());
            slice[slot].addAll(result.sharedPerBatch[slot]);
            addScaled(slice[slot], result.scalablePerCopy[slot], Math.max(0, sliceCount));
        }
        return slice;
    }

    public static void markDispatched(BulkResult result, long dispatchedCopies) {
        if (dispatchedCopies <= 0) return;
        long accepted = Math.min(dispatchedCopies, result.remainingCopies);
        for (int slot = 0; slot < result.scaledInputs.length; slot++) {
            for (var entry : result.scalablePerCopy[slot]) {
                long amount = saturatingMultiply(entry.getLongValue(), accepted);
                if (amount > 0) {
                    result.scaledInputs[slot].remove(entry.getKey(), amount);
                }
            }
            if (!result.sharedDispatched) {
                for (var entry : result.sharedPerBatch[slot]) {
                    result.scaledInputs[slot].remove(entry.getKey(), entry.getLongValue());
                }
            }
        }
        result.sharedDispatched = true;
        result.remainingCopies -= accepted;
    }

    public static final class BulkResult {
        public final KeyCounter[] scaledInputs;
        public final long actualCopies;
        @Nullable final AEKey[] keys;
        @Nullable final long[] units;
        @Nullable final boolean[] sharedInputs;
        final KeyCounter[] scalablePerCopy;
        final KeyCounter[] sharedPerBatch;
        @Nullable
        final List<RemainderSpec> remainders;
        long remainingCopies;
        boolean sharedDispatched;

        public BulkResult(KeyCounter[] scaledInputs, long actualCopies, AEKey[] keys,
                          long[] units, boolean[] sharedInputs) {
            this.scaledInputs = scaledInputs;
            this.actualCopies = actualCopies;
            this.keys = Arrays.copyOf(keys, keys.length);
            this.units = Arrays.copyOf(units, units.length);
            this.sharedInputs = Arrays.copyOf(sharedInputs, sharedInputs.length);
            this.scalablePerCopy = new KeyCounter[scaledInputs.length];
            this.sharedPerBatch = new KeyCounter[scaledInputs.length];
            for (int slot = 0; slot < scaledInputs.length; slot++) {
                boolean populated = keys[slot] != null && units[slot] > 0;
                this.scalablePerCopy[slot] = counterWithCapacity(populated && !sharedInputs[slot] ? 1 : 0);
                this.sharedPerBatch[slot] = counterWithCapacity(populated && sharedInputs[slot] ? 1 : 0);
                if (populated) {
                    (sharedInputs[slot] ? this.sharedPerBatch[slot] : this.scalablePerCopy[slot])
                            .add(keys[slot], units[slot]);
                }
            }
            this.remainders = null;
            this.remainingCopies = actualCopies;
        }

        private BulkResult(KeyCounter[] scaledInputs, long actualCopies,
                           KeyCounter[] scalablePerCopy, KeyCounter[] sharedPerBatch,
                           List<RemainderSpec> remainders) {
            this.scaledInputs = scaledInputs;
            this.actualCopies = actualCopies;
            // Legacy metadata is unused when concrete per-slot counters/remainders are present.
            this.keys = null;
            this.units = null;
            this.sharedInputs = null;
            this.scalablePerCopy = scalablePerCopy;
            this.sharedPerBatch = sharedPerBatch;
            this.remainders = remainders;
            this.remainingCopies = actualCopies;
        }

        public boolean hasSharedInputs() {
            for (var counter : sharedPerBatch) {
                if (!counter.isEmpty()) return true;
            }
            return false;
        }

        private void reinjectShared(ListCraftingInventory inv) {
            for (int slot = 0; slot < scaledInputs.length; slot++) {
                for (var entry : sharedPerBatch[slot]) {
                    inv.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
                    scaledInputs[slot].remove(entry.getKey(), entry.getLongValue());
                }
            }
        }
    }

    private static void addScaled(KeyCounter target, KeyCounter source, long scale) {
        if (scale <= 0) return;
        for (var entry : source) {
            target.add(entry.getKey(), saturatingMultiply(entry.getLongValue(), scale));
        }
    }

    private static KeyCounter counterWithCapacity(int entries) {
        return entries == 0 ? new KeyCounter() : new KeyCounter(new AEKeyLongMap<>(entries));
    }

    private record ResolvedCopy(KeyCounter[] inputs, List<RemainderSpec> remainders) {
    }

    private record RemainderSpec(AEKey key, long count, boolean shared) {
    }

    private static final class ReservedInventory implements ICraftingInventory {
        private final ListCraftingInventory delegate;
        private final Map<AEKey, Long> reserved;

        private ReservedInventory(ListCraftingInventory delegate, Map<AEKey, Long> reserved) {
            this.delegate = delegate;
            this.reserved = reserved != null ? reserved : Collections.emptyMap();
        }

        @Override
        public void insert(AEKey what, long amount, Actionable mode) {
            delegate.insert(what, amount, mode);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode) {
            long available = delegate.extract(what, Long.MAX_VALUE, Actionable.SIMULATE);
            long protectedAmount = Math.max(0L, reserved.getOrDefault(what, 0L));
            long extractable = Math.max(0L, available - protectedAmount);
            long allowed = Math.min(Math.max(0L, amount), extractable);
            return mode == Actionable.SIMULATE
                    ? allowed : delegate.extract(what, allowed, Actionable.MODULATE);
        }

        @Override
        public Iterable<AEKey> findFuzzyTemplates(AEKey input) {
            return delegate.findFuzzyTemplates(input);
        }
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
