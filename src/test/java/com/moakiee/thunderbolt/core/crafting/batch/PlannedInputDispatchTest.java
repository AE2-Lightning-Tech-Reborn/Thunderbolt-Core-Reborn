package com.moakiee.thunderbolt.core.crafting.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.core.crafting.pattern.PlannedInputPattern;
import com.moakiee.thunderbolt.core.crafting.support.CraftingPatternDelegates;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PlannedInputDispatchTest {
    private static final TestKey A = new TestKey("a");
    private static final TestKey B = new TestKey("b");

    @Test
    void changedInputCountsCannotRestoreAnUnexecutableAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedInputPattern(pattern(input(2, A, B)), List.of(Map.of(A, 1L))));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedInputPattern(pattern(input(1, A, B)), List.of(Map.of(A, 1L, B, 1L))));
    }

    @Test
    void ambiguousBatchDoesNotReplayAndReturnsOnlyUntouchedCopies() throws Exception {
        for (String mode : List.of("throw", "invalid", "accounting")) {
            var source = pattern(input(1, A));
            var planned = new PlannedInputPattern(source, List.of(Map.of(A, 1L)));
            var stock = inventory(10, 0);
            long[] progress = {10};
            int[] calls = {0};
            String[] error = {null};
            var providerType = com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider.class;
            var provider = (com.moakiee.thunderbolt.api.crafting.batch.IBatchCraftingProvider)
                    java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {providerType},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "getBatchCapacity" -> 10L;
                                case "getBatchDispatchMode" -> com.moakiee.thunderbolt.api.crafting.batch.BatchDispatchMode.NORMAL;
                                case "isBusy", "supportsSharedBatchInputs" -> false;
                                case "pushBatch" -> {
                                    calls[0]++;
                                    if (mode.equals("throw")) throw new IllegalStateException("accepted then failed");
                                    yield mode.equals("invalid") ? -1L : 0L;
                                }
                                default -> null;
                            });
            var schedule = new TickProviderDispatchSchedule();
            var scheduleClass = Class.forName(TickProviderDispatchSchedule.class.getName() + "$PatternSchedule");
            var constructor = scheduleClass.getDeclaredConstructor(List.class);
            constructor.setAccessible(true);
            var schedules = TickProviderDispatchSchedule.class.getDeclaredField("patterns");
            schedules.setAccessible(true);
            // Two providers reserve ten copies; the first receives five. Failure must leave
            // the second half available without attempting the second provider.
            var secondProvider = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {providerType}, java.lang.reflect.Proxy.getInvocationHandler(provider));
            ((Map) schedules.get(schedule)).put(source, constructor.newInstance(List.of(provider, secondProvider)));
            var handle = new com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle() {
                public IPatternDetails details() { return planned; }
                public long getValue() { return progress[0]; }
                public void setValue(long value) {
                    if (mode.equals("accounting")) throw new IllegalStateException("accounting failed");
                    progress[0] = value;
                }
            };
            var job = new com.moakiee.thunderbolt.api.crafting.batch.BatchJobView() {
                public Level level() { return null; }
                public java.util.UUID craftingId() { return null; }
                public java.util.Iterator<com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle> taskIterator() {
                    return List.<com.moakiee.thunderbolt.api.crafting.batch.BatchTaskHandle>of(handle).iterator();
                }
                public ListCraftingInventory waitingFor() { return new ListCraftingInventory(key -> {}); }
                public void addContainerMaxItems(long count, AEKeyType type) { }
                public void failDispatch(String reason, Throwable cause) { error[0] = reason; }
            };
            var energyType = appeng.api.networking.energy.IEnergyService.class;
            var energy = (appeng.api.networking.energy.IEnergyService)
                    java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {energyType},
                            (proxy, method, args) -> method.getName().equals("extractAEPower") ? args[0] : null);
            BatchExecutor.runBatchOnly(10, BatchCpuAccounting.Mode.SUCCESSFUL_DISPATCH, null, energy,
                    job, stock, new java.util.HashMap<>(), () -> {}, Map.of(), 10, 10, false, schedule);
            assertNotNull(error[0], mode);
            assertEquals(1, calls[0], mode);
            assertEquals(5, held(stock, A), mode);
            assertEquals(10, progress[0], mode);
        }
    }

    @Test
    void concreteInputArrayCanBeWrappedWithoutChangingTheSource() {
        Input[] sourceInputs = {input(1, B, A)};
        var source = pattern(sourceInputs);
        var planned = new PlannedInputPattern(source, List.of(Map.of(A, 1L)));
        assertSame(sourceInputs, source.getInputs());
        assertSame(sourceInputs[0], source.getInputs()[0]);
        assertEquals(Input.class, source.getInputs().getClass().getComponentType());
        var inventory = inventory(1, 1);
        var extracted = ParallelBatchCpuHelper.extractPatternInputs(
                planned, inventory, null, new KeyCounter(), new KeyCounter());
        assertNotNull(extracted);
        assertEquals(1, extracted[0].get(A));
        assertEquals(1, held(inventory, B));
    }

    @Test
    void singleDispatchLeavesTheFirstCandidateForItsStrictConsumer() {
        var inventory = inventory(1, 1);
        var source = pattern(input(1, B, A));
        var planned = new PlannedInputPattern(source, List.of(Map.of(A, 1L)));
        var extracted = ParallelBatchCpuHelper.extractPatternInputs(
                planned, inventory, null, new KeyCounter(), new KeyCounter());
        assertNotNull(extracted);
        assertEquals(1, extracted[0].get(A));
        assertEquals(1, held(inventory, B));
        assertNotNull(ParallelBatchCpuHelper.extractPatternInputs(
                pattern(input(1, B)), inventory, null, new KeyCounter(), new KeyCounter()));
        assertSame(source, CraftingPatternDelegates.forProviderLookup(planned));
        assertSame(source, CraftingPatternDelegates.forBatchExecution(planned));
    }

    @Test
    void missingPlannedMaterialWaitsWithoutBorrowingASiblingAndRollsBackEarlierSlots() {
        var inventory = inventory(0, 5);
        var planned = new PlannedInputPattern(pattern(input(1, B), input(1, B, A)),
                List.of(Map.of(B, 1L), Map.of(A, 1L)));
        assertNull(ParallelBatchCpuHelper.extractPatternInputs(
                planned, inventory, null, new KeyCounter(), new KeyCounter()));
        assertEquals(5, held(inventory, B));
        assertNull(ParallelBatchCpuHelper.bulkExtract(planned, inventory, 5, false, Map.of(), null));
        assertEquals(5, held(inventory, B));
    }

    @Test
    void mixedSlotKeepsExactAmountsAcrossBulkPartialAcceptanceAndRetry() {
        for (boolean shared : List.of(false, true)) {
            var inventory = inventory(10, 20);
            var planned = new PlannedInputPattern(pattern(input(3, B, A)), List.of(Map.of(A, 1L, B, 2L)));
            var result = ParallelBatchCpuHelper.bulkExtract(planned, inventory, 4, shared, Map.of(), null);
            assertNotNull(result);
            assertEquals(4, result.actualCopies);
            assertEquals(4, result.scaledInputs[0].get(A));
            assertEquals(8, result.scaledInputs[0].get(B));
            ParallelBatchCpuHelper.markDispatched(result, 1);
            ParallelBatchCpuHelper.reinject(result, 3, inventory);
            assertEquals(9, held(inventory, A));
            assertEquals(18, held(inventory, B));
            var retry = ParallelBatchCpuHelper.bulkExtract(planned, inventory, 3, shared, Map.of(), null);
            assertNotNull(retry);
            assertEquals(3, retry.scaledInputs[0].get(A));
            assertEquals(6, retry.scaledInputs[0].get(B));
            ParallelBatchCpuHelper.reinject(retry, 3, inventory);
            assertEquals(9, held(inventory, A));
            assertEquals(18, held(inventory, B));
        }
    }

    @Test
    void singleCopyCannotSpendTheWholeMixedSlotOnOneAvailableKey() {
        var inventory = inventory(10, 10);
        var planned = new PlannedInputPattern(pattern(input(3, B, A)), List.of(Map.of(A, 2L, B, 1L)));
        var result = ParallelBatchCpuHelper.extractPatternInputs(
                planned, inventory, null, new KeyCounter(), new KeyCounter());
        assertNotNull(result);
        assertEquals(2, result[0].get(A));
        assertEquals(1, result[0].get(B));
    }

    @Test
    void independentSlotsKeepTheirOwnAllocationsEvenWhenBothAcceptTheSameKeys() {
        var inventory = inventory(1, 1);
        var planned = new PlannedInputPattern(pattern(input(1, A, B), input(1, A, B)),
                List.of(Map.of(B, 1L), Map.of(A, 1L)));
        var result = ParallelBatchCpuHelper.bulkExtract(planned, inventory, 1, false, Map.of(), null);
        assertNotNull(result);
        assertEquals(1, result.scaledInputs[0].get(B));
        assertEquals(1, result.scaledInputs[1].get(A));
    }

    @Test
    void componentVariantsRemainExactButDynamicSlotsRetainNativeMatching() {
        var encoded = new TestKey("tool", "encoded");
        var plannedKey = new TestKey("tool", "planned");
        var other = new TestKey("tool", "other");
        var fuzzy = new Input(new AEKey[] {encoded}, 1, true, null);
        var inventory = inventory(0, 0);
        inventory.insert(other, 2, Actionable.MODULATE);
        var exact = new PlannedInputPattern(pattern(fuzzy), List.of(Map.of(plannedKey, 1L)));
        assertNull(ParallelBatchCpuHelper.bulkExtract(exact, inventory, 1, false, Map.of(), null));
        inventory.insert(plannedKey, 1, Actionable.MODULATE);
        var resolved = ParallelBatchCpuHelper.bulkExtract(exact, inventory, 1, false, Map.of(), null);
        assertNotNull(resolved);
        assertEquals(1, resolved.scaledInputs[0].get(plannedKey));
        assertEquals(2, held(inventory, other));
        var dynamic = new PlannedInputPattern(pattern(fuzzy), List.of(Map.of()));
        assertNotNull(ParallelBatchCpuHelper.bulkExtract(dynamic, inventory, 2, false, Map.of(), null));
    }

    @Test
    void reservedStockAndNonUnitTemplateAmountsAreRespected() {
        var inventory = inventory(10, 20);
        var units = new IPatternDetails.IInput() {
            public GenericStack[] getPossibleInputs() { return new GenericStack[] {new GenericStack(B, 2), new GenericStack(A, 2)}; }
            public long getMultiplier() { return 3; }
            public boolean isValid(AEKey key, Level level) { return key.equals(A) || key.equals(B); }
            public AEKey getRemainingKey(AEKey key) { return null; }
        };
        var planned = new PlannedInputPattern(pattern(units), List.of(Map.of(A, 2L, B, 4L)));
        var result = ParallelBatchCpuHelper.bulkExtract(planned, inventory, 10, false, Map.of(A, 8L), null);
        assertNotNull(result);
        assertEquals(1, result.actualCopies);
        assertEquals(8, held(inventory, A));
        assertEquals(16, held(inventory, B));
    }

    @Test
    void sharedSeedAndRemaindersAreStillAccountedOncePerAcceptedBatch() {
        var inventory = inventory(1, 8);
        var planned = new PlannedInputPattern(pattern(new Input(new AEKey[] {A}, 1, false, A), input(1, B)),
                List.of(Map.of(A, 1L), Map.of(B, 1L)));
        var result = ParallelBatchCpuHelper.bulkExtract(planned, inventory, 8, true, Map.of(), null);
        assertNotNull(result);
        assertEquals(8, result.actualCopies);
        assertEquals(1, result.scaledInputs[0].get(A));
        ParallelBatchCpuHelper.markDispatched(result, 1);
        ParallelBatchCpuHelper.reinject(result, 7, inventory);
        assertEquals(0, held(inventory, A));
        assertEquals(7, held(inventory, B));
    }

    @Test
    void legacyTasksAndUnplannedPatternsRetainSubstitutionBehavior() {
        var source = pattern(input(1, B, A));
        assertSame(source, PlannedInputPattern.readFromTag(source, new CompoundTag(), null));
        var inventory = inventory(1, 1);
        var result = ParallelBatchCpuHelper.extractPatternInputs(source, inventory, null, new KeyCounter(), new KeyCounter());
        assertNotNull(result);
        assertEquals(1, result[0].get(B));
    }

    @Test
    void dynamicSlotFailureRestoresTheLaterExactSlotAndAllowsRetry() {
        var source = pattern(input(1, A, B), input(1, A));
        var planned = new PlannedInputPattern(source, List.of(Map.of(), Map.of(A, 1L)));
        var inventory = inventory(1, 0);
        assertNull(ParallelBatchCpuHelper.extractPatternInputs(
                planned, inventory, null, new KeyCounter(), new KeyCounter()));
        assertEquals(1, held(inventory, A));
        assertNull(ParallelBatchCpuHelper.bulkExtract(planned, inventory, 10, false, Map.of(), null));
        assertEquals(1, held(inventory, A));
        inventory.insert(B, 1, Actionable.MODULATE);
        var result = ParallelBatchCpuHelper.bulkExtract(planned, inventory, 10, false, Map.of(), null);
        assertNotNull(result);
        assertEquals(1, result.actualCopies);
        assertEquals(1, result.scaledInputs[0].get(B));
        assertEquals(1, result.scaledInputs[1].get(A));
        ParallelBatchCpuHelper.reinject(result, 1, inventory);
        assertEquals(1, held(inventory, A));
        assertEquals(1, held(inventory, B));
    }

    @Test
    void persistedMixedAllocationStillProtectsItsSiblingAfterReload() throws Exception {
        if (net.neoforged.fml.loading.LoadingModList.get() == null) {
            net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var registryField = appeng.api.stacks.AEKeyTypesInternal.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        var previous = registryField.get(null);
        // Resolve through the current registry even if AE2 memoizes this codec after this test.
        var typeCodec = ResourceLocation.CODEC.xmap(
                id -> appeng.api.stacks.AEKeyTypesInternal.getRegistry().get(id), AEKeyType::getId);
        var registry = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {net.minecraft.core.Registry.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "byNameCodec" -> typeCodec;
                    case "get" -> TestKey.TYPE;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        registryField.set(null, registry);
        try {
            net.minecraft.core.HolderLookup.Provider lookups = new net.minecraft.core.HolderLookup.Provider() {
                @Override public java.util.stream.Stream<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>> listRegistries() {
                    return java.util.stream.Stream.empty();
                }
                @Override public <T> java.util.Optional<net.minecraft.core.HolderLookup.RegistryLookup<T>> lookup(
                        net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<? extends T>> key) {
                    return java.util.Optional.empty();
                }
            };
            var source = pattern(input(3, B, A));
            var planned = new PlannedInputPattern(source, List.of(Map.of(A, 1L, B, 2L)));
            var tag = new CompoundTag();
            planned.writeToTag(tag, lookups);
            var restored = (PlannedInputPattern) PlannedInputPattern.readFromTag(source, tag, lookups);
            assertEquals(planned.allocations(), restored.allocations());
            var inventory = inventory(4, 10);
            var result = ParallelBatchCpuHelper.bulkExtract(restored, inventory, 4, false, Map.of(), null);
            assertNotNull(result);
            assertEquals(4, result.scaledInputs[0].get(A));
            assertEquals(8, result.scaledInputs[0].get(B));
            assertEquals(2, held(inventory, B));
            var invalid = tag.copy();
            invalid.putString(PlannedInputPattern.NBT_INPUTS, "invalid");
            assertThrows(IllegalArgumentException.class,
                    () -> PlannedInputPattern.readFromTag(source, invalid, lookups));
        } finally {
            registryField.set(null, previous);
        }
    }

    private static ListCraftingInventory inventory(long a, long b) {
        var result = new ListCraftingInventory(key -> {});
        result.insert(A, a, Actionable.MODULATE);
        result.insert(B, b, Actionable.MODULATE);
        return result;
    }
    private static long held(ListCraftingInventory inventory, AEKey key) {
        return inventory.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
    }
    private static Input input(long multiplier, AEKey... keys) { return new Input(keys, multiplier, false, null); }
    private static IPatternDetails pattern(IPatternDetails.IInput... inputs) { return new Pattern(inputs); }
    private record Pattern(IPatternDetails.IInput[] inputs) implements IPatternDetails {
        public AEItemKey getDefinition() { return null; }
        public IInput[] getInputs() { return inputs; }
        public List<GenericStack> getOutputs() { return List.of(); }
    }
    private record Input(AEKey[] keys, long multiplier, boolean fuzzy, AEKey remainder) implements IPatternDetails.IInput {
        public GenericStack[] getPossibleInputs() { return java.util.Arrays.stream(keys).map(key -> new GenericStack(key, 1)).toArray(GenericStack[]::new); }
        public long getMultiplier() { return multiplier; }
        public boolean isValid(AEKey key, Level level) { return java.util.Arrays.stream(keys).anyMatch(option -> fuzzy ? option.getPrimaryKey().equals(key.getPrimaryKey()) : option.equals(key)); }
        public AEKey getRemainingKey(AEKey key) { return remainder; }
    }
    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String primaryId;
        private final String id;

        private TestKey(String id) {
            this(id, id);
        }

        private TestKey(String primaryId, String id) {
            this.primaryId = primaryId.intern();
            this.id = id;
        }

        @Override
        public AEKeyType getType() {
            return TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new CompoundTag();
            tag.putString("id", id);
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return primaryId;
        }

        @Override
        public ResourceLocation getId() {
            return ResourceLocation.fromNamespaceAndPath("ae2lt_test", primaryId);
        }

        @Override
        public void writeToPacket(RegistryFriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean hasComponents() {
            return !primaryId.equals(id);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof TestKey other
                    && primaryId.equals(other.primaryId)
                    && id.equals(other.id);
        }

        @Override
        public int hashCode() {
            return 31 * primaryId.hashCode() + id.hashCode();
        }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(ResourceLocation.fromNamespaceAndPath("ae2lt_test", "key"), TestKey.class,
                    Component.literal("test key"));
        }

        @Override
        public MapCodec<? extends AEKey> codec() {
            return com.mojang.serialization.codecs.RecordCodecBuilder.<TestKey>mapCodec(instance -> instance.group(
                    com.mojang.serialization.Codec.STRING.fieldOf("primary").forGetter((TestKey key) -> key.primaryId),
                    com.mojang.serialization.Codec.STRING.fieldOf("id").forGetter((TestKey key) -> key.id)
            ).apply(instance, TestKey::new));
        }

        @Override
        public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
            return null;
        }
    }
}
