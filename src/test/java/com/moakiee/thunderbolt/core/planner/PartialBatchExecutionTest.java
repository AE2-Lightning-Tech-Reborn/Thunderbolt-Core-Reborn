package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.core.crafting.batch.ParallelBatchCpuHelper;
import com.moakiee.thunderbolt.core.crafting.pattern.PlannedInputPattern;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Runs the real AE2 inventory and TB input extractor, including delayed provider returns. */
class PartialBatchExecutionTest {
    @Test void stockBackedPartialCopiesReuseTheSeedUntilTheEntireTaskCompletes() {
        for (boolean reverse : List.of(false, true)) {
            for (boolean plannedInputs : List.of(false, true)) {
                for (long dispatchCap : new long[] {1, 2, 64}) {
                    exercise(reverse, plannedInputs, dispatchCap);
                }
            }
        }
    }

    private static void exercise(boolean reverse, boolean plannedInputs, long dispatchCap) {
        AEKey seed = new TestKey("seed"), token = new TestKey("token"),
                fuel = new TestKey("fuel"), product = new TestKey("product");
        var forwardInputs = List.of(new GenericStack(seed, 1));
        var returnInputs = List.of(new GenericStack(token, 1), new GenericStack(fuel, 1));
        var forward = pattern(forwardInputs, List.of(new GenericStack(token, 1)), plannedInputs);
        var recycle = pattern(returnInputs,
                List.of(new GenericStack(product, 1), new GenericStack(seed, 1)), plannedInputs);
        var makeToken = new CraftPattern<AEKey>(token, 1, List.of(CraftInput.of(seed, 1)), forward);
        var makeProduct = new CraftPattern<AEKey>(product, 1,
                List.of(CraftInput.of(token, 1), CraftInput.of(fuel, 1)),
                List.of(CraftOutput.of(seed, 1)), recycle);
        var graph = CraftGraph.<AEKey>builder().stock(seed, 1).stock(fuel, 2)
                .pattern(makeToken).pattern(makeProduct).build();
        var plan = CraftPlannerV2.plan(graph, product, 2);
        assertTrue(plan.feasible(), plan::toString);
        assertEquals(2L, plan.firings().get(makeToken));
        assertEquals(2L, plan.firings().get(makeProduct));
        assertTrue(UnorderedByproductSafety.allOrdersFinishSmall(plan, product, 2));
        assertFalse(UnorderedByproductSafety.allBatchOrdersFinishSmall(plan, product, 2),
                "requiring both seed copies before starting incorrectly rejects this job");

        var inventory = new ListCraftingInventory(key -> {});
        plan.usedStock().forEach((key, amount) -> inventory.insert(key, amount, Actionable.MODULATE));
        var ordered = new ArrayList<>(plan.firings().entrySet());
        if (reverse) Collections.reverse(ordered);
        var tasks = new LinkedHashMap<IPatternDetails, Long>();
        ordered.forEach(entry -> tasks.put((IPatternDetails) entry.getKey().source(), entry.getValue()));
        int rounds = 0;
        while (tasks.values().stream().anyMatch(copies -> copies > 0)) {
            assertTrue(++rounds <= 8, "partial tasks must keep making progress");
            var pending = new ArrayList<GenericStack>();
            boolean progressed = false;
            for (var entry : tasks.entrySet()) {
                if (entry.getValue() <= 0) continue;
                var batch = ParallelBatchCpuHelper.bulkExtract(entry.getKey(), inventory,
                        Math.min(dispatchCap, entry.getValue()), true, Map.of(), null);
                if (batch == null) continue;
                assertEquals(1L, batch.actualCopies, "only one circulating seed is available");
                entry.setValue(entry.getValue() - batch.actualCopies);
                for (var output : entry.getKey().getOutputs())
                    pending.add(new GenericStack(output.what(), output.amount() * batch.actualCopies));
                progressed = true;
            }
            assertTrue(progressed, "real input extraction must dispatch an affordable partial batch");
            // Providers complete later; no task can spend a not-yet-returned output this round.
            pending.forEach(output -> inventory.insert(output.what(), output.amount(), Actionable.MODULATE));
        }
        assertEquals(2L, inventory.extract(product, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(1L, inventory.extract(seed, Long.MAX_VALUE, Actionable.SIMULATE));
        assertEquals(0L, inventory.extract(fuel, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    private static IPatternDetails pattern(List<GenericStack> inputs, List<GenericStack> outputs, boolean planned) {
        var details = new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return null; }
            @Override public GenericStack[] getOutputs() { return outputs.toArray(GenericStack[]::new); }
            @Override public IInput[] getInputs() {
                return inputs.stream().map(stack -> new IInput() {
                    @Override public GenericStack[] getPossibleInputs() { return new GenericStack[] {stack}; }
                    @Override public long getMultiplier() { return 1; }
                    @Override public boolean isValid(AEKey key, Level level) { return stack.what().equals(key); }
                    @Override public AEKey getRemainingKey(AEKey key) { return null; }
                }).toArray(IInput[]::new);
            }
        };
        return planned ? new PlannedInputPattern(details,
                inputs.stream().map(stack -> Map.of(stack.what(), stack.amount())).toList()) : details;
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String id;
        TestKey(String id) { this.id = id; }
        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag() {
            var tag = new CompoundTag(); tag.putString("id", id); return tag;
        }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() { return new ResourceLocation("thunderbolt_test", id); }
        @Override public void writeToPacket(FriendlyByteBuf data) {}
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {}
        @Override public boolean equals(Object other) { return other instanceof TestKey key && id.equals(key.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        TestKeyType() {
            super(new ResourceLocation("thunderbolt_test", "partial_batch_key"),
                    TestKey.class, Component.literal("partial batch key"));
        }
        @Override public AEKey readFromPacket(FriendlyByteBuf input) { return null; }
        @Override public AEKey loadKeyFromTag(CompoundTag tag) { return null; }
    }
}
