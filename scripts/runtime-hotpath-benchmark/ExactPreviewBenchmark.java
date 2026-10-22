package com.moakiee.thunderbolt.core.crafting.planner;

import com.sun.management.ThreadMXBean;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.*;

/** Runs the actual ordinary-plan precision gate against the selected production revision. */
public final class ExactPreviewBenchmark {
    private static final long WARMUP_NS = Long.getLong("benchmark.warmupMillis", 1_000L) * 1_000_000L;
    private static final long SAMPLE_NS = Long.getLong("benchmark.sampleMillis", 100L) * 1_000_000L;
    private static volatile boolean sink;

    private record Scenario(String name, CraftPlan<AEKey> plan, boolean expected) {}

    public static void main(String[] args) {
        var scenarios = new ArrayList<Scenario>();
        scenarios.add(new Scenario("empty", plan(Map.of()), false));
        for (int count : new int[]{1, 32, 128, 1024}) {
            scenarios.add(ordinary(count, false));
            if (count > 1) scenarios.add(ordinary(count, true));
        }
        var threshold = BigInteger.valueOf(Sat.SAT);
        scenarios.add(new Scenario("wide-summary", new CraftPlan<>(true, true, Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(key("out"), Sat.SAT), 0, false), true));
        scenarios.add(new Scenario("wide-single-output", plan(Map.of(
                new CraftPattern<>(key("out"), BigInteger.TEN.pow(100), List.of(), List.of(), null), 1L)), true));
        var merged = new LinkedHashMap<CraftPattern<AEKey>, Long>();
        merged.put(new CraftPattern<>(key("out"), threshold.subtract(BigInteger.ONE), List.of(), List.of(), null), 1L);
        merged.put(new CraftPattern<>(key("other"), 1, List.of(), List.of(CraftOutput.of(key("out"), 1)), null), 1L);
        scenarios.add(new Scenario("wide-merged-output", plan(merged), true));
        var signed = new LinkedHashMap<CraftPattern<AEKey>, Long>();
        signed.put(new CraftPattern<>(key("out"), threshold, List.of(), List.of(), null), 1L);
        signed.put(new CraftPattern<>(key("out"), threshold, List.of(), List.of(), null), -1L);
        scenarios.add(new Scenario("signed-cancellation", plan(signed), false));
        for (var scenario : scenarios) measure(scenario);
    }

    private static Scenario ordinary(int size, boolean overlap) {
        var counts = new LinkedHashMap<CraftPattern<AEKey>, Long>();
        var demand = new LinkedHashMap<AEKey, Long>();
        for (int i = 0; i < size; i++) {
            AEKey key = key(overlap ? "shared" : "out" + i);
            var recipe = new CraftPattern<>(key, 7, List.of(CraftInput.of(key("raw" + i), 128)),
                    List.of(CraftOutput.of(overlap ? key : key("side" + i), 3)), null);
            counts.put(recipe, 1000L);
            demand.put(key("raw" + i), 128_000L);
        }
        return new Scenario((overlap ? "overlap-" : "distinct-") + size,
                new CraftPlan<>(true, true, counts, demand, Map.of(), Map.of(), demand, size, false), false);
    }

    private static CraftPlan<AEKey> plan(Map<CraftPattern<AEKey>, Long> counts) {
        return new CraftPlan<>(true, true, counts, Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
    }

    private static boolean execute(Scenario scenario) {
        boolean result = ExactPlanPreview.needsExact(scenario.plan());
        if (result != scenario.expected()) throw new AssertionError(scenario.name());
        sink = result;
        return result;
    }

    private static void measure(Scenario scenario) {
        long warmupEnd = System.nanoTime() + WARMUP_NS;
        while (System.nanoTime() < warmupEnd) execute(scenario);
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("No allocation counters");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int sample = 0; sample < 7; sample++) {
            long beforeBytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            long operations = 0;
            do {
                execute(scenario);
                operations++;
            } while (System.nanoTime() - start < SAMPLE_NS);
            long elapsed = System.nanoTime() - start;
            long bytes = bean.getThreadAllocatedBytes(thread) - beforeBytes;
            System.out.printf(Locale.ROOT,
                    "{\"scenario\":\"%s\",\"sample\":%d,\"operations\":%d,\"ns_per_op\":%.3f,\"bytes_per_op\":%.3f,\"signature\":\"%s\"}%n",
                    scenario.name(), sample, operations, (double) elapsed / operations,
                    (double) bytes / operations, scenario.expected());
        }
    }
    private static AEKey key(String id) { return new TestKey(id); }

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
