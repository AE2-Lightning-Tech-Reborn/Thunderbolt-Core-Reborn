package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyLongMap;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs unchanged against both forks; no synthetic key overrides the newer final hashCode. */
class BatchAe2AbiTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.ensureInitialized(); }

    @Test
    void primitiveWritesPreservePreviousValuesSaturationAndKeyIdentity() {
        var plain = AEItemKey.of(Items.STONE);
        var tag = new CompoundTag();
        tag.putInt("variant", 1);
        var variant = AEItemKey.of(Items.STONE, tag);
        var amounts = new AEKeyLongMap<AEKey>(1);
        assertEquals(0, BatchKeyAmounts.put(amounts, plain, 5));
        assertEquals(5, BatchKeyAmounts.put(amounts, plain, Long.MAX_VALUE - 2));
        assertEquals(Long.MAX_VALUE - 2, BatchKeyAmounts.addTo(amounts, plain, 10));
        assertEquals(Long.MAX_VALUE, amounts.getLong(plain));
        assertEquals(0, BatchKeyAmounts.addTo(amounts, variant, 7));
        assertEquals(7, amounts.getLong(variant));
        assertEquals(2, amounts.size());
        assertEquals(Long.MAX_VALUE, BatchKeyAmounts.put(amounts, plain, 0));
        assertEquals(0, amounts.getLong(plain));
        assertEquals(7, amounts.getLong(variant));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothExtractionPathsPreserveReservationsPartialRefundsAndCopies(boolean legacy) {
        var key = AEItemKey.of(Items.IRON_INGOT);
        var pattern = new Pattern(new Input[]{new Input(key, 2), new Input(key, 3)}, new GenericStack[0], false);
        var stock = inventory(key, 1_000);
        var result = extract(legacy, pattern, stock, 512, Map.of(key, 250L));
        assertNotNull(result);
        assertEquals(150, result.actualCopies);
        assertEquals(250, held(stock, key));
        var borrowed = ParallelBatchCpuHelper.cloneSingleCopy(result);
        borrowed[0].clear();
        assertEquals(2, ParallelBatchCpuHelper.cloneSingleCopy(result)[0].get(key));
        ParallelBatchCpuHelper.markDispatched(result, 40);
        ParallelBatchCpuHelper.reinject(result, 110, stock);
        assertEquals(800, held(stock, key));
        ParallelBatchCpuHelper.reinject(result, 110, stock);
        assertEquals(800, held(stock, key));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedExtractionRollsBackEveryKeyBeforeRetry(boolean legacy) {
        var first = AEItemKey.of(Items.IRON_INGOT);
        var second = AEItemKey.of(Items.GOLD_INGOT);
        var stock = new ListCraftingInventory(ignored -> {}) {
            private int bulkExtractions;
            @Override public long extract(AEKey key, long amount, Actionable mode) {
                if (mode == Actionable.MODULATE && amount > 5 && ++bulkExtractions == 2) {
                    return super.extract(key, amount - 1, mode);
                }
                return super.extract(key, amount, mode);
            }
        };
        stock.insert(first, 1_000, Actionable.MODULATE);
        stock.insert(second, 1_000, Actionable.MODULATE);
        var pattern = new Pattern(new Input[]{new Input(first, 2), new Input(second, 3)}, new GenericStack[0], false);
        assertNull(extract(legacy, pattern, stock, 10, Map.of()));
        assertEquals(1_000, held(stock, first));
        assertEquals(1_000, held(stock, second));
        var retry = extract(legacy, pattern, stock, 10, Map.of());
        assertNotNull(retry);
        ParallelBatchCpuHelper.reinject(retry, 10, stock);
        assertEquals(1_000, held(stock, first));
        assertEquals(1_000, held(stock, second));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void duplicateSharedOutputsUseOneAllowanceAndReturnOneSeed(boolean legacy) {
        var seed = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        var product = AEItemKey.of(Items.STONE);
        var pattern = new Pattern(new Input[]{new Input(seed, 1)}, new GenericStack[]{
                new GenericStack(seed, 1), new GenericStack(seed, 1), new GenericStack(product, 5)}, true);
        var stock = inventory(seed, 1);
        var result = extract(legacy, pattern, stock, 8, Map.of());
        assertNotNull(result);
        assertEquals(8, result.actualCopies);
        var waiting = inventory(product, 0);
        var job = new BatchJobView() {
            @Override public Level level() { return null; }
            @Override public Iterator<BatchTaskHandle> taskIterator() { return Collections.emptyIterator(); }
            @Override public ListCraftingInventory waitingFor() { return waiting; }
            @Override public UUID craftingId() { return null; }
            @Override public void addContainerMaxItems(long count, AEKeyType type) { fail("No container expected"); }
        };
        ParallelBatchCpuHelper.registerExpectedOutputs(job, pattern, result, 8);
        assertEquals(9, held(waiting, seed));
        assertEquals(40, held(waiting, product));
        ParallelBatchCpuHelper.reinject(result, 8, stock);
        assertEquals(1, held(stock, seed));
    }

    @Test
    void sharedOutputComparisonSaturatesAndDetectsDifferentTotals() {
        var seed = AEItemKey.of(Items.STONE);
        var stock = inventory(seed, 1);
        var task = new Pattern(new Input[]{new Input(seed, 1)}, new GenericStack[]{
                new GenericStack(seed, Long.MAX_VALUE - 3), new GenericStack(seed, 10)}, true);
        var result = extract(false, task, stock, 8, Map.of());
        assertNotNull(result);
        var same = new Pattern(task.inputs, new GenericStack[]{new GenericStack(seed, Long.MAX_VALUE)}, true);
        var different = new Pattern(task.inputs, new GenericStack[]{new GenericStack(seed, Long.MAX_VALUE - 1)}, true);
        assertTrue(BatchExecutor.sharedBatchSemanticsMatch(task, same, result));
        assertFalse(BatchExecutor.sharedBatchSemanticsMatch(task, different, result));
        ParallelBatchCpuHelper.reinject(result, 8, stock);
        assertEquals(1, held(stock, seed));
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

    private record Input(AEKey key, long amount) implements IPatternDetails.IInput {
        @Override public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(key, amount)}; }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey candidate, Level level) { return candidate == key; }
        @Override public AEKey getRemainingKey(AEKey consumed) { return null; }
    }

    private record Pattern(Input[] inputs, GenericStack[] outputs, boolean shared)
            implements IPatternDetails, SharedBatchInputPattern {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public GenericStack[] getOutputs() { return outputs; }
        @Override public boolean isSharedBatchInput(int slot, AEKey key) { return shared && slot == 0; }
        @Override public long sharedBatchOutputAmount(AEKey key) { return key == inputs[0].key ? 1 : 0; }
    }
}
