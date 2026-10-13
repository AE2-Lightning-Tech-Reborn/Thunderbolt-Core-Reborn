package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.api.crafting.batch.BatchJobView;
import com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle;
import com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider;
import com.moakiee.thunderbolt.api.crafting.batch.PreparedBatch;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** Exercises generic admission on Forge's real AE2 inventory, without optional addon APIs. */
class BatchAdmissionDispatchTest {
    private static final TestKey MATERIAL = new TestKey("material");
    private static final TestKey PRIMARY = new TestKey("primary");
    private static final TestKey BYPRODUCT = new TestKey("byproduct");
    private static final TestKey CONTAINER = new TestKey("container");

    @Test
    void admissionLimitsExtractionAndAccountsEveryOutputAcrossTicks() {
        var fixture = new Fixture(10_000);
        long accepted = 0;
        long[] limits = {64, 0, 128, 10_000};
        for (int tick = 0; tick < limits.length; tick++) {
            fixture.provider.limit = limits[tick];
            long before = fixture.remaining;
            var result = fixture.run(tick);
            long expected = Math.min(before, limits[tick]);
            accepted += expected;
            assertEquals(expected, result.dispatchedCopies());
            assertEquals(expected == 0 ? 0 : 1, result.consumedCpuOps());
            assertEquals(before - expected, fixture.remaining);
            assertEquals(20_000 - 2 * accepted, held(fixture.stock, MATERIAL));
            assertTrue(fixture.minimumStock >= held(fixture.stock, MATERIAL) - 2,
                    "Only the single prototype may be extracted and rolled back before admission");
            fixture.assertOutputs(accepted);
            assertNull(ParallelBatchCpuHelper.currentBatchCapacityLimiter(fixture.pattern, fixture.stock));
        }
        assertEquals(0, fixture.remaining);
        assertEquals(10_000, fixture.provider.committed);
        assertEquals(4, fixture.provider.preparations);
        assertEquals(3, fixture.provider.preparedPushes);
        assertEquals(0, fixture.provider.ordinaryPushes);
    }

    @Test
    void admissionFailureReturnsPrototypeAndCanRecoverNextTick() {
        var fixture = new Fixture(8);
        fixture.provider.admissionFailure = true;
        assertEquals(0, fixture.run(1).dispatchedCopies());
        assertEquals(16, held(fixture.stock, MATERIAL));
        assertEquals(8, fixture.remaining);
        assertNull(fixture.failure);
        fixture.assertOutputs(0);
        fixture.provider.admissionFailure = false;
        assertEquals(8, fixture.run(2).dispatchedCopies());
        assertEquals(0, held(fixture.stock, MATERIAL));
        fixture.assertOutputs(8);
    }

    @Test
    void reducedPowerBudgetSubmitsOnlyAffordableCopies() {
        var fixture = new Fixture(8);
        var one = new KeyCounter();
        one.add(MATERIAL, 2);
        fixture.availablePower = 2 * CraftingCpuHelper.calculatePatternPower(new KeyCounter[]{one});
        assertEquals(2, fixture.run(1).dispatchedCopies());
        assertEquals(8, fixture.provider.preparedCapacity);
        assertEquals(2, fixture.provider.lastOffer);
        assertEquals(12, held(fixture.stock, MATERIAL));
        assertEquals(6, fixture.remaining);
        fixture.assertOutputs(2);
    }

    @Test
    void rejectedCopiesAreReturnedWithoutOvercountingOutputs() {
        var fixture = new Fixture(8);
        fixture.provider.rejectedCopies = 5;
        assertEquals(3, fixture.run(1).dispatchedCopies());
        assertEquals(10, held(fixture.stock, MATERIAL));
        assertEquals(5, fixture.remaining);
        fixture.assertOutputs(3);
    }

    @Test
    void preparedSubmissionFailureDoesNotReplayAmbiguousOwnership() {
        var fixture = new Fixture(8);
        fixture.provider.submissionFailure = true;
        fixture.run(1);
        assertEquals("AMBIGUOUS_BATCH_PROVIDER_OWNERSHIP", fixture.failure);
        assertEquals(1, fixture.provider.preparedPushes);
        assertEquals(0, fixture.provider.ordinaryPushes);
        assertEquals(0, held(fixture.stock, MATERIAL));
        assertEquals(8, fixture.remaining);
        fixture.assertOutputs(0);
    }

    @Test
    void legacyProviderKeepsOrdinaryPushBatchContract() {
        var fixture = new Fixture(8);
        fixture.provider.legacy = true;
        assertEquals(8, fixture.run(1).dispatchedCopies());
        assertEquals(1, fixture.provider.ordinaryPushes);
        assertEquals(0, fixture.provider.preparedPushes);
        assertEquals(0, held(fixture.stock, MATERIAL));
        fixture.assertOutputs(8);
    }

    @Test
    void limiterZeroOrExceptionRestoresPrototypeAndScope() {
        var fixture = new Fixture(8);
        try (var scope = ParallelBatchCpuHelper.limitBatchCapacity(fixture.pattern, fixture.stock,
                (prototype, copies) -> 0)) {
            assertNull(ParallelBatchCpuHelper.bulkExtract(
                    fixture.pattern, fixture.stock, 8, false, Map.of(), null));
        }
        assertEquals(16, held(fixture.stock, MATERIAL));
        for (boolean error : List.of(false, true)) {
            try (var scope = ParallelBatchCpuHelper.limitBatchCapacity(fixture.pattern, fixture.stock,
                    (prototype, copies) -> {
                        if (error) throw new AssertionError("limiter failed");
                        throw new IllegalStateException("limiter failed");
                    })) {
                Class<? extends Throwable> expectedType = error ? AssertionError.class : IllegalStateException.class;
                assertThrows(expectedType,
                        () -> ParallelBatchCpuHelper.bulkExtract(
                                fixture.pattern, fixture.stock, 8, false, Map.of(), null));
            }
            assertEquals(16, held(fixture.stock, MATERIAL));
            assertNull(ParallelBatchCpuHelper.currentBatchCapacityLimiter(fixture.pattern, fixture.stock));
        }
    }

    private static final class Fixture implements BatchJobView {
        final IPatternDetails pattern = pattern();
        final Provider provider = new Provider();
        final TickProviderDispatchSchedule schedule = new TickProviderDispatchSchedule();
        final ListCraftingInventory stock;
        final ListCraftingInventory waiting = new ListCraftingInventory(key -> {});
        final Map<IPatternDetails, IdentityHashMap<ICraftingProvider, Boolean>> batched = new HashMap<>();
        long remaining;
        long minimumStock = Long.MAX_VALUE;
        long containers;
        double availablePower = Double.MAX_VALUE;
        String failure;

        Fixture(long copies) {
            remaining = copies;
            stock = new ListCraftingInventory(key -> observeStock());
            stock.insert(MATERIAL, 2 * copies, Actionable.MODULATE);
        }

        private void observeStock() {
            minimumStock = Math.min(minimumStock, held(stock, MATERIAL));
        }

        BatchExecutor.BatchRunResult run(long tick) {
            minimumStock = Long.MAX_VALUE;
            schedule.beginTick(tick);
            schedule.candidates(null, tick, pattern, () -> List.of(provider));
            batched.clear();
            var energy = (IEnergyService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IEnergyService.class}, (proxy, method, args) ->
                            method.getName().equals("extractAEPower")
                                    ? Math.min((double) args[0], availablePower) : null);
            return BatchExecutor.runBatchOnly(10, BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH,
                    null, energy, this, stock, batched, () -> {}, Map.of(), 10, 10_000, false, schedule);
        }

        void assertOutputs(long copies) {
            assertEquals(copies, held(waiting, PRIMARY));
            assertEquals(2 * copies, held(waiting, BYPRODUCT));
            assertEquals(copies, held(waiting, CONTAINER));
            assertEquals(copies, containers);
        }

        @Override public Level level() { return null; }
        @Override public UUID craftingId() { return null; }
        @Override public ListCraftingInventory waitingFor() { return waiting; }
        @Override public void addContainerMaxItems(long count, AEKeyType type) { containers += count; }
        @Override public void failDispatch(String reason, Throwable cause) { failure = reason; }
        @Override public Iterator<BatchTaskHandle> taskIterator() {
            var task = new BatchTaskHandle() {
                @Override public IPatternDetails details() { return pattern; }
                @Override public long getValue() { return remaining; }
                @Override public void setValue(long value) { remaining = value; }
            };
            return new ArrayList<BatchTaskHandle>(List.of(task)).iterator();
        }
    }

    private static final class Provider implements IBatchCraftingProvider {
        long limit = Long.MAX_VALUE;
        long committed;
        long preparedCapacity;
        long lastOffer;
        long rejectedCopies;
        int preparations;
        int preparedPushes;
        int ordinaryPushes;
        boolean admissionFailure;
        boolean submissionFailure;
        boolean legacy;

        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(); }
        @Override public boolean isBusy() { return false; }
        @Override public PreparedBatch prepareBatch(
                IPatternDetails details, KeyCounter[] prototype, long copies, BatchJobView job) {
            preparations++;
            if (admissionFailure) throw new IllegalStateException("admission failed");
            if (legacy) return null;
            assertEquals(2, prototype[0].get(MATERIAL));
            var ownedTemplate = new KeyCounter();
            ownedTemplate.addAll(prototype[0]);
            long capacity = Math.min(limit, copies);
            preparedCapacity = capacity;
            return new PreparedBatch() {
                boolean used;
                @Override public long capacity() { return capacity; }
                @Override public long push(long offered) {
                    assertFalse(used, "Each admission is used only once");
                    used = true;
                    assertTrue(offered > 0 && offered <= capacity);
                    assertEquals(2, ownedTemplate.get(MATERIAL));
                    preparedPushes++;
                    lastOffer = offered;
                    if (submissionFailure) throw new IllegalStateException("ownership uncertain");
                    long rejected = Math.min(rejectedCopies, offered);
                    committed += offered - rejected;
                    return rejected;
                }
            };
        }
        @Override public long pushBatch(IPatternDetails details, KeyCounter[] prototype, long copies) {
            ordinaryPushes++;
            assertEquals(2, prototype[0].get(MATERIAL));
            committed += copies;
            return 0;
        }
    }

    private static IPatternDetails pattern() {
        var input = new IPatternDetails.IInput() {
            @Override public GenericStack[] getPossibleInputs() {
                return new GenericStack[]{new GenericStack(MATERIAL, 2)};
            }
            @Override public long getMultiplier() { return 1; }
            @Override public boolean isValid(AEKey key, Level level) { return MATERIAL.equals(key); }
            @Override public AEKey getRemainingKey(AEKey key) { return CONTAINER; }
        };
        var outputs = List.of(new GenericStack(PRIMARY, 1), new GenericStack(BYPRODUCT, 2));
        return (IPatternDetails) Proxy.newProxyInstance(BatchAdmissionDispatchTest.class.getClassLoader(),
                new Class<?>[]{IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputs" -> new IPatternDetails.IInput[]{input};
                    case "getOutputs" -> method.getReturnType().isArray()
                            ? outputs.toArray(GenericStack[]::new) : outputs;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "generic-admission-pattern";
                    case "supportsPushInputsToExternalInventory", "canSubstitute" -> false;
                    default -> null;
                });
    }

    private static long held(ListCraftingInventory inventory, AEKey key) {
        return inventory.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String id;
        TestKey(String id) { this.id = id; }
        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag() {
            var tag = new CompoundTag();
            tag.putString("id", id);
            return tag;
        }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() { return new ResourceLocation("batch_admission_test", id); }
        @Override public void writeToPacket(FriendlyByteBuf data) { }
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean equals(Object obj) { return obj instanceof TestKey other && id.equals(other.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        TestKeyType() {
            super(new ResourceLocation("batch_admission_test", "key"), TestKey.class, Component.literal("key"));
        }
        @Override public AEKey loadKeyFromTag(CompoundTag tag) { return null; }
        @Override public AEKey readFromPacket(FriendlyByteBuf input) { return null; }
    }
}
