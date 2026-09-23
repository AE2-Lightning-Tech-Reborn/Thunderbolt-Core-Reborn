package com.moakiee.thunderbolt.core.crafting.pattern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ICraftingInventory;
import com.moakiee.thunderbolt.core.crafting.batch.BatchCopyLimitPattern;
import com.moakiee.thunderbolt.core.crafting.batch.SharedBatchInputPattern;
import com.moakiee.thunderbolt.core.crafting.loop.CraftingTaskPersistenceDefinition;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

/** A CPU task's concrete per-copy input allocation, independent of its registered provider pattern. */
public final class PlannedInputPattern implements IPatternDetails, IProviderLookupPattern,
        IBatchExecutionPattern, SharedBatchInputPattern, BatchCopyLimitPattern,
        CraftingTaskPersistenceDefinition {
    public static final String NBT_INPUTS = "#plannedInputs";
    private final IPatternDetails delegate;
    private final List<Map<AEKey, Long>> slots;
    private final IInput[] inputs;

    public PlannedInputPattern(IPatternDetails delegate, List<Map<AEKey, Long>> slots) {
        this.delegate = Objects.requireNonNull(delegate);
        var sourceInputs = delegate.getInputs();
        // clone() preserves concrete array types such as AEProcessingPattern.Input[].
        // Allocate an interface array so replacement inputs can use our allocation wrapper.
        this.inputs = java.util.Arrays.copyOf(sourceInputs, sourceInputs.length, IInput[].class);
        if (slots.size() != inputs.length) throw new IllegalArgumentException("input slot count changed");
        var copied = new ArrayList<Map<AEKey, Long>>(slots.size());
        for (int slot = 0; slot < slots.size(); slot++) {
            var allocation = Map.copyOf(slots.get(slot));
            if (allocation.values().stream().anyMatch(amount -> amount <= 0)) {
                throw new IllegalArgumentException("input allocations must be positive");
            }
            copied.add(allocation);
            if (!allocation.isEmpty()) inputs[slot] = new AllocatedInput(inputs[slot], allocation);
        }
        this.slots = List.copyOf(copied);
    }

    public List<Map<AEKey, Long>> allocations() { return slots; }
    @Override public IInput[] getInputs() { return inputs.clone(); }
    @Override public List<GenericStack> getOutputs() { return delegate.getOutputs(); }
    @Override public AEItemKey getDefinition() { return delegate.getDefinition(); }
    @Override public IPatternDetails providerLookupPattern() { return delegate; }
    @Override public IPatternDetails batchExecutionPattern() { return delegate; }
    @Override public AEItemKey craftingTaskPersistenceDefinition() {
        return delegate instanceof CraftingTaskPersistenceDefinition persistent
                ? persistent.craftingTaskPersistenceDefinition() : delegate.getDefinition();
    }
    @Override public long maxBatchCopies() {
        return delegate instanceof BatchCopyLimitPattern limited ? limited.maxBatchCopies() : Long.MAX_VALUE;
    }
    @Override public boolean isSharedBatchInput(int slot, AEKey key) {
        return delegate instanceof SharedBatchInputPattern shared && shared.isSharedBatchInput(slot, key);
    }
    @Override public long sharedBatchOutputAmount(AEKey key) {
        return delegate instanceof SharedBatchInputPattern shared ? shared.sharedBatchOutputAmount(key) : 0L;
    }

    /** A fresh quota for one slot of one copy. Rollback always goes directly to the backing inventory. */
    public ICraftingInventory inventoryForSlot(int slot, ICraftingInventory inventory) {
        var allocation = slots.get(slot);
        if (allocation.isEmpty()) return inventory;
        var remaining = new LinkedHashMap<>(allocation);
        return new ICraftingInventory() {
            @Override public void insert(AEKey key, long amount, Actionable mode) {
                inventory.insert(key, amount, mode);
            }
            @Override public long extract(AEKey key, long amount, Actionable mode) {
                long limit = remaining.getOrDefault(key, 0L);
                long extracted = inventory.extract(key, Math.min(Math.max(0L, amount), limit), mode);
                if (mode == Actionable.MODULATE && extracted > 0) remaining.put(key, limit - extracted);
                return extracted;
            }
            @Override public Iterable<AEKey> findFuzzyTemplates(AEKey key) {
                // The planned key itself is the template, even if the source has other component variants.
                return remaining.containsKey(key) ? List.of(key) : List.of();
            }
        };
    }

    public boolean matchesAllocation(int slot, KeyCounter extracted) {
        var allocation = slots.get(slot);
        if (allocation.isEmpty()) return true;
        for (var entry : allocation.entrySet()) {
            if (extracted.get(entry.getKey()) != entry.getValue()) return false;
        }
        for (var entry : extracted) {
            if (entry.getLongValue() != allocation.getOrDefault(entry.getKey(), 0L)) return false;
        }
        return true;
    }

    public void writeToTag(CompoundTag task, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var slot : slots) {
            var slotTag = new CompoundTag();
            var stacks = new ListTag();
            for (var entry : slot.entrySet()) {
                var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                        net.minecraft.util.ProblemReporter.DISCARDING, registries);
                GenericStack.writeTag(output, new GenericStack(entry.getKey(), entry.getValue()));
                stacks.add(output.buildResult());
            }
            slotTag.put("stacks", stacks);
            list.add(slotTag);
        }
        task.put(NBT_INPUTS, list);
    }

    public static IPatternDetails readFromTag(IPatternDetails delegate, CompoundTag task,
                                               HolderLookup.Provider registries) {
        if (!task.contains(NBT_INPUTS)) return delegate; // Legacy tasks retain their original semantics.
        var list = task.getList(NBT_INPUTS).orElseThrow(() -> new IllegalArgumentException("invalid planned inputs"));
        var slots = new ArrayList<Map<AEKey, Long>>();
        for (int i = 0; i < list.size(); i++) {
            var slot = new LinkedHashMap<AEKey, Long>();
            var slotTag = list.getCompoundOrEmpty(i);
            var stacks = slotTag.getList("stacks").orElseThrow(() -> new IllegalArgumentException("missing input slot"));
            for (int j = 0; j < stacks.size(); j++) {
                var stack = GenericStack.readTag(net.minecraft.world.level.storage.TagValueInput.create(
                        net.minecraft.util.ProblemReporter.DISCARDING, registries, stacks.getCompoundOrEmpty(j)));
                if (stack == null || stack.amount() <= 0 || slot.put(stack.what(), stack.amount()) != null) {
                    throw new IllegalArgumentException("invalid input allocation");
                }
            }
            slots.add(slot);
        }
        return new PlannedInputPattern(delegate, slots);
    }

    private static final class AllocatedInput implements IInput {
        private final IInput delegate;
        private final Map<AEKey, Long> allocation;
        private final GenericStack[] possible;

        private AllocatedInput(IInput delegate, Map<AEKey, Long> allocation) {
            this.delegate = delegate;
            this.allocation = allocation;
            var templates = new ArrayList<GenericStack>();
            var allocatedUnits = java.math.BigInteger.ZERO;
            for (var key : allocation.keySet()) {
                GenericStack anchor = null;
                for (var candidate : delegate.getPossibleInputs()) {
                    if (Objects.equals(candidate.what().getPrimaryKey(), key.getPrimaryKey())) {
                        anchor = candidate;
                        if (candidate.what().equals(key)) break;
                    }
                }
                if (anchor == null) throw new IllegalArgumentException("planned input no longer matches pattern");
                long amount = allocation.get(key);
                if (anchor.amount() <= 0 || amount % anchor.amount() != 0) {
                    throw new IllegalArgumentException("planned input has invalid template units");
                }
                allocatedUnits = allocatedUnits.add(java.math.BigInteger.valueOf(amount / anchor.amount()));
                templates.add(new GenericStack(key, anchor.amount()));
            }
            if (!allocatedUnits.equals(java.math.BigInteger.valueOf(delegate.getMultiplier()))) {
                throw new IllegalArgumentException("planned input quantity no longer matches pattern");
            }
            this.possible = templates.toArray(GenericStack[]::new);
        }
        @Override public GenericStack[] getPossibleInputs() { return possible.clone(); }
        @Override public long getMultiplier() { return delegate.getMultiplier(); }
        @Override public boolean isValid(AEKey key, Level level) {
            return allocation.containsKey(key) && delegate.isValid(key, level);
        }
        @Override public AEKey getRemainingKey(AEKey key) { return delegate.getRemainingKey(key); }
    }
}
