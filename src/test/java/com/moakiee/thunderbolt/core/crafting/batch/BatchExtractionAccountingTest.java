package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;

import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle;

class BatchExtractionAccountingTest {
    private static final AEKey MATERIAL = new TestKey("material");
    private static final AEKey SECOND = new TestKey("second");
    private static final AEKey THIRD = new TestKey("third");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedSlotsKeepReservationsAndIndependentProviderCopies(boolean legacy) {
        var pattern = pattern(input(MATERIAL, 2), input(MATERIAL, 3));
        var stock = inventory(MATERIAL, 1_000);
        var result = extract(legacy, pattern, stock, 512, Map.of(MATERIAL, 250L));
        assertNotNull(result);
        assertEquals(150, result.actualCopies);
        assertEquals(250, held(stock, MATERIAL));
        assertEquals(300, result.scaledInputs[0].get(MATERIAL));
        assertEquals(450, result.scaledInputs[1].get(MATERIAL));

        var providerCopy = ParallelBatchCpuHelper.cloneSingleCopy(result);
        assertEquals(2, providerCopy[0].get(MATERIAL));
        assertEquals(3, providerCopy[1].get(MATERIAL));
        providerCopy[0].clear();
        providerCopy[1].add(MATERIAL, 100);
        assertEquals(2, ParallelBatchCpuHelper.cloneSingleCopy(result)[0].get(MATERIAL));
        assertEquals(450, result.scaledInputs[1].get(MATERIAL));

        ParallelBatchCpuHelper.markDispatched(result, 40);
        ParallelBatchCpuHelper.reinject(result, 110, stock);
        assertEquals(800, held(stock, MATERIAL));
        assertEquals(0, result.remainingCopies);
        ParallelBatchCpuHelper.reinject(result, 110, stock);
        assertEquals(800, held(stock, MATERIAL), "Already refunded copies cannot be returned twice");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shortExtractionRestoresEveryKeyAndCanRetry(boolean legacy) {
        var pattern = pattern(input(MATERIAL, 2), input(SECOND, 3), input(THIRD, 5));
        var stock = new ShortInventory();
        for (var key : List.of(MATERIAL, SECOND, THIRD)) stock.insert(key, 1_000, Actionable.MODULATE);

        assertNull(extract(legacy, pattern, stock, 10, Collections.emptyMap()));
        assertEquals(2, stock.bulkAttempts, "Failure follows a successful extraction of a different key");
        for (var key : List.of(MATERIAL, SECOND, THIRD)) assertEquals(1_000, held(stock, key));

        var retry = extract(legacy, pattern, stock, 10, Collections.emptyMap());
        assertNotNull(retry);
        assertEquals(10, retry.actualCopies);
        assertEquals(980, held(stock, MATERIAL));
        assertEquals(970, held(stock, SECOND));
        assertEquals(950, held(stock, THIRD));
        ParallelBatchCpuHelper.reinject(retry, 10, stock);
        for (var key : List.of(MATERIAL, SECOND, THIRD)) assertEquals(1_000, held(stock, key));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void distinctKeysFollowTheGtoInventoryIdentityContract(boolean legacy) {
        var other = new TestKey("material");
        assertEquals(MATERIAL, other);
        assertNotSame(MATERIAL, other);
        var stock = inventory(MATERIAL, 80);
        stock.insert(other, 200, Actionable.MODULATE);
        var result = extract(legacy, pattern(input(MATERIAL, 2), input(other, 5)),
                stock, 100, Collections.emptyMap());
        assertNotNull(result);
        assertEquals(40, result.actualCopies);
        assertEquals(80, result.scaledInputs[0].get(MATERIAL));
        assertEquals(200, result.scaledInputs[1].get(other));
        assertEquals(0, held(stock, MATERIAL));
        assertEquals(0, held(stock, other));
        ParallelBatchCpuHelper.reinject(result, 40, stock);
        assertEquals(80, held(stock, MATERIAL));
        assertEquals(200, held(stock, other));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateOutputsSpendTheSharedAllowanceOnce(boolean legacy) {
        var pattern = new Pattern(new IPatternDetails.IInput[]{input(MATERIAL, 1)},
                new GenericStack[]{new GenericStack(MATERIAL, 1), new GenericStack(MATERIAL, 1),
                        new GenericStack(SECOND, 2), new GenericStack(SECOND, 3)}, true);
        var stock = inventory(MATERIAL, 1);
        var result = extract(legacy, pattern, stock, 8, Collections.emptyMap());
        assertNotNull(result);
        assertEquals(8, result.actualCopies);
        var waiting = new ListCraftingInventory(ignored -> {});
        var job = new BatchJobView() {
            @Override public Level level() { return null; }
            @Override public Iterator<BatchTaskHandle> taskIterator() { return Collections.emptyIterator(); }
            @Override public ListCraftingInventory waitingFor() { return waiting; }
            @Override public UUID craftingId() { return null; }
            @Override public void addContainerMaxItems(long count, AEKeyType type) { fail("No container expected"); }
        };
        ParallelBatchCpuHelper.registerExpectedOutputs(job, pattern, result, 8);
        assertEquals(9, held(waiting, MATERIAL));
        assertEquals(40, held(waiting, SECOND));
        assertEquals(0, held(stock, MATERIAL));
        ParallelBatchCpuHelper.reinject(result, 8, stock);
        assertEquals(1, held(stock, MATERIAL));
    }

    @Test
    void sharedSemanticComparisonAggregatesAndSaturatesDuplicateOutputs() {
        var task = new Pattern(new IPatternDetails.IInput[]{input(MATERIAL, 1)},
                new GenericStack[]{new GenericStack(MATERIAL, Long.MAX_VALUE - 3),
                        new GenericStack(MATERIAL, 10), new GenericStack(SECOND, 4)}, true);
        var execution = new Pattern(task.inputs(),
                new GenericStack[]{new GenericStack(SECOND, 4), new GenericStack(MATERIAL, Long.MAX_VALUE)}, true);
        assertTrue(BatchExecutor.sharedBatchSemanticsMatch(task, execution, sharedResult()));
        var missing = new Pattern(execution.inputs(), new GenericStack[]{new GenericStack(MATERIAL, Long.MAX_VALUE)}, true);
        assertFalse(BatchExecutor.sharedBatchSemanticsMatch(task, missing, sharedResult()));
        var wrongCount = new Pattern(execution.inputs(),
                new GenericStack[]{new GenericStack(MATERIAL, Long.MAX_VALUE), new GenericStack(SECOND, 5)}, true);
        assertFalse(BatchExecutor.sharedBatchSemanticsMatch(task, wrongCount, sharedResult()));
    }

    @Test
    void sharedSemanticComparisonPreservesDistinctOutputIdentities() {
        var other = new TestKey("material");
        var task = new Pattern(new IPatternDetails.IInput[]{input(MATERIAL, 1)},
                new GenericStack[]{new GenericStack(MATERIAL, 2)}, true);
        var execution = new Pattern(task.inputs(), new GenericStack[]{new GenericStack(other, 2)}, true);
        assertFalse(BatchExecutor.sharedBatchSemanticsMatch(task, execution, sharedResult()));
    }

    private static ParallelBatchCpuHelper.BulkResult sharedResult() {
        var counter = new KeyCounter();
        counter.add(MATERIAL, 1);
        return new ParallelBatchCpuHelper.BulkResult(new KeyCounter[]{counter}, 8,
                new AEKey[]{MATERIAL}, new long[]{1}, new boolean[]{true});
    }

    private static ParallelBatchCpuHelper.BulkResult extract(boolean legacy, IPatternDetails pattern,
            ListCraftingInventory stock, long copies, Map<AEKey, Long> reserved) {
        return legacy ? ParallelBatchCpuHelper.bulkExtract(pattern, stock, copies, true, reserved)
                : ParallelBatchCpuHelper.bulkExtract(pattern, stock, copies, true, reserved, null);
    }

    private static ListCraftingInventory inventory(AEKey key, long amount) {
        var stock = new ListCraftingInventory(ignored -> {});
        stock.insert(key, amount, Actionable.MODULATE);
        return stock;
    }

    private static long held(ListCraftingInventory stock, AEKey key) {
        return stock.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    private static Input input(AEKey key, long amount) {
        return new Input(new GenericStack[]{new GenericStack(key, amount)});
    }

    private static Pattern pattern(IPatternDetails.IInput... inputs) {
        return new Pattern(inputs, new GenericStack[0], false);
    }

    private record Input(GenericStack[] templates) implements IPatternDetails.IInput {
        @Override public GenericStack[] getPossibleInputs() { return templates; }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey key, Level level) { return key == templates[0].what(); }
        @Override public AEKey getRemainingKey(AEKey key) { return null; }
    }

    private record Pattern(IInput[] inputs, GenericStack[] outputs, boolean shared)
            implements IPatternDetails, SharedBatchInputPattern {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public GenericStack[] getOutputs() { return outputs; }
        @Override public boolean isSharedBatchInput(int slot, AEKey key) { return shared && slot == 0; }
        @Override public long sharedBatchOutputAmount(AEKey key) { return key == MATERIAL ? 1 : 0; }
    }

    private static final class ShortInventory extends ListCraftingInventory {
        int bulkAttempts;
        boolean failOnce = true;

        ShortInventory() { super(ignored -> {}); }

        @Override public long extract(AEKey key, long amount, Actionable mode) {
            if (mode == Actionable.MODULATE && amount > 5 && failOnce && ++bulkAttempts == 2) {
                failOnce = false;
                return super.extract(key, amount - 1, mode);
            }
            return super.extract(key, amount, mode);
        }
    }

    private static final class TestKey extends AEKey {
        private static final AEKeyType TYPE = new AEKeyType(
                new ResourceLocation("batch_accounting_test", "key"), TestKey.class, Component.literal("key")) {
            @Override public AEKey loadKeyFromTag(CompoundTag tag) { return null; }
            @Override public AEKey readFromPacket(FriendlyByteBuf input) { return null; }
        };
        private final String id;

        TestKey(String id) { this.id = id; }
        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag() { return new CompoundTag(); }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() { return new ResourceLocation("batch_accounting_test", id); }
        @Override public void writeToPacket(FriendlyByteBuf data) { }
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean equals(Object other) { return other instanceof TestKey key && id.equals(key.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }
}
