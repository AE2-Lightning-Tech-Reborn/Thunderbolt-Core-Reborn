package com.moakiee.thunderbolt.core.crafting.batch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.api.crafting.batch.*;
import com.moakiee.thunderbolt.core.crafting.pattern.PlannedInputPattern;
import com.mojang.serialization.MapCodec;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.IntSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Real shared-input scanning and full batch dispatch with unavailable material, no world ticks. */
public final class BatchHotpathBenchmark {
    private static final long WARMUP_NS = Long.getLong("benchmark.warmupMillis", 1_000L) * 1_000_000L;
    private static final long SAMPLE_NS = Long.getLong("benchmark.sampleMillis", 100L) * 1_000_000L;
    private static volatile int sink;

    public static void main(String[] args) {
        for (int[] shape : new int[][]{{1, 1}, {9, 1}, {9, 8}, {64, 8}, {256, 16}}) {
            var plain = pattern(shape[0], shape[1], false);
            var planned = new PlannedInputPattern(plain, Collections.nCopies(shape[0], Map.of()));
            measure("inputs-" + shape[0] + "x" + shape[1] + "-planned", () -> {
                if (SharedBatchInputs.hasSharedInputs(planned)) throw new AssertionError("Unexpected shared input");
                return 0;
            });
        }
        var plain = pattern(64, 8, false);
        measure("inputs-64x8-plain", () -> {
            if (SharedBatchInputs.hasSharedInputs(plain)) throw new AssertionError("Unexpected shared input");
            return 0;
        });
        for (int count : new int[]{1, 16, 256, 1_024}) dispatch(count, true);
        for (int count : new int[]{16, 256}) dispatch(count, false);
    }

    private static void dispatch(int count, boolean shared) {
        var details = pattern(1, 1, shared);
        var random = new Random(90210);
        var providers = new ArrayList<ICraftingProvider>();
        for (int i = 0; i < count; i++) providers.add(new Provider(
                128 + random.nextInt(65_536), i % 8 == 0 ? BatchDispatchMode.UNBOUNDED : BatchDispatchMode.NORMAL));
        var schedule = new TickProviderDispatchSchedule();
        schedule.beginTick(0);
        schedule.candidates(null, 0, details, () -> providers);
        var inventory = new ListCraftingInventory(key -> {});
        var task = new BatchTaskHandle() {
            @Override public IPatternDetails details() { return details; }
            @Override public long getValue() { return 1_000_000; }
            @Override public void setValue(long value) { throw new AssertionError("Missing input was dispatched"); }
        };
        var job = new BatchJobView() {
            @Override public Level level() { return null; }
            @Override public UUID craftingId() { return null; }
            @Override public Iterator<BatchTaskHandle> taskIterator() { return List.<BatchTaskHandle>of(task).iterator(); }
            @Override public ListCraftingInventory waitingFor() { throw new AssertionError("No output expected"); }
            @Override public void addContainerMaxItems(long count, AEKeyType type) { throw new AssertionError(); }
        };
        var batched = new HashMap<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>>();
        measure("dispatch-" + count + (shared ? "-shared" : "-ordinary"), () -> {
            var result = BatchExecutor.runBatchOnly(128, BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH,
                    null, null, job, inventory, batched, () -> { throw new AssertionError("Dirty"); },
                    Map.of(), 128, 1_000_000, false, schedule);
            if (result.dispatchedCopies() != 0 || result.consumedCpuOps() != 0 || !result.sawBatchProvider()) {
                throw new AssertionError("Unexpected dispatch result");
            }
            return result.consumedCpuOps();
        });
    }

    private static IPatternDetails pattern(int slots, int variants, boolean shared) {
        var inputs = new IPatternDetails.IInput[slots];
        for (int slot = 0; slot < slots; slot++) {
            var possible = new GenericStack[variants];
            for (int variant = 0; variant < variants; variant++) {
                possible[variant] = new GenericStack(new TestKey("input_" + slot + "_" + variant), 1);
            }
            var remaining = shared ? possible[variants - 1].what() : null;
            inputs[slot] = new IPatternDetails.IInput() {
                @Override public GenericStack[] getPossibleInputs() { return possible; }
                @Override public long getMultiplier() { return 1; }
                @Override public boolean isValid(AEKey key, Level level) { return true; }
                @Override public AEKey getRemainingKey(AEKey key) { return remaining; }
            };
        }
        return new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return null; }
            @Override public IInput[] getInputs() { return inputs; }
            @Override public List<GenericStack> getOutputs() { return List.of(); }
        };
    }

    private static void measure(String name, IntSupplier operation) {
        long warmupEnd = System.nanoTime() + WARMUP_NS;
        while (System.nanoTime() < warmupEnd) sink = operation.getAsInt();
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int sample = 0; sample < 7; sample++) {
            long beforeBytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            long operations = 0;
            do {
                sink = operation.getAsInt();
                operations++;
            } while (System.nanoTime() - start < SAMPLE_NS);
            long elapsed = System.nanoTime() - start;
            long allocated = bean.getThreadAllocatedBytes(thread) - beforeBytes;
            System.out.printf(Locale.ROOT,
                    "{\"scenario\":\"%s\",\"sample\":%d,\"operations\":%d,\"ns_per_op\":%.3f,\"bytes_per_op\":%.3f}%n",
                    name, sample, operations, (double) elapsed / operations, (double) allocated / operations);
        }
    }

    private record Provider(long capacity, BatchDispatchMode mode) implements IBatchCraftingProvider {
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return false; }
        @Override public boolean supportsSharedBatchInputs() { return true; }
        @Override public long getBatchCapacity(IPatternDetails details) { return capacity; }
        @Override public BatchDispatchMode getBatchDispatchMode(IPatternDetails details) { return mode; }
        @Override public long pushBatch(IPatternDetails details, KeyCounter[] template, long copies) {
            throw new AssertionError("Unavailable material was dispatched");
        }
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String id;
        private TestKey(String id) { this.id = id; }
        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new CompoundTag(); tag.putString("id", id); return tag;
        }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() { return ResourceLocation.fromNamespaceAndPath("tb_benchmark", id); }
        @Override public void writeToPacket(RegistryFriendlyByteBuf data) {}
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {}
        @Override public boolean hasComponents() { return false; }
        @Override public boolean equals(Object other) { return other instanceof TestKey key && id.equals(key.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(ResourceLocation.fromNamespaceAndPath("tb_benchmark", "key"), TestKey.class, Component.literal("key"));
        }
        @Override public MapCodec<? extends AEKey> codec() { return null; }
        @Override public AEKey readFromPacket(RegistryFriendlyByteBuf input) { return null; }
    }
}
