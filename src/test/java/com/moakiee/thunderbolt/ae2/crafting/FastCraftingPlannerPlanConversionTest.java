package com.moakiee.thunderbolt.mixin.ae2.crafting.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;

import com.moakiee.thunderbolt.core.crafting.pattern.CraftingStockPolicy;
import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPattern;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlan;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlannerV2;
import com.moakiee.thunderbolt.core.crafting.planner.FastCraftingPlanner;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FastCraftingPlannerPlanConversionTest {
    static {
        // Proxying ICraftingService loads Level through a default method; that
        // requires the Forge 1.20.1 registries even when this class is run alone.
        MinecraftTestBootstrap.ensureInitialized();
    }

    private static final AEKey A = new TestKey("a");
    private static final AEKey B = new TestKey("b");
    private static final AEKey C = new TestKey("c");
    private static final AEKey D = new TestKey("d");
    private static final AEKey E = new TestKey("e");
    private static final AEKey TARGET = new TestKey("target");

    @Test
    void mixedBatchAttemptExportsBothRegisteredPatternsAndExactStockDraw() {
        var large = new BatchPattern(TARGET, 8,
                new IPatternDetails.IInput[] {new FakeInput(new GenericStack(A, 12))});
        var small = new BatchPattern(TARGET, 2,
                new IPatternDetails.IInput[] {new FakeInput(new GenericStack(A, 3))});
        var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        inventory.insert(A, 30, Actionable.MODULATE);
        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(TARGET, List.of(large, small))),
                new ChildCraftingSimulationState(inventory), null, TARGET, 19, false);
        assertTrue(attempt.handled());
        assertFalse(attempt.plan().simulation());
        assertEquals(2L, attempt.plan().patternTimes().get(large));
        assertEquals(2L, attempt.plan().patternTimes().get(small));
        assertEquals(30L, attempt.plan().usedItems().get(A));
        assertEquals(19L, attempt.plan().finalOutput().amount());
        assertTrue(attempt.plan().missingItems().isEmpty());
        assertEquals(30L, inventory.extract(A, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @ParameterizedTest
    @CsvSource({
            "100, 0, 4, 0",
            "2, 0, 2, 2",
            "0, 0, 0, 4",
            "100, 98, 2, 2",
            "100, 100, 0, 4"
    })
    void emittableIngredientUsesOnlyPolicyAllowedStockFirst(
            long stock, long reserved, long expectedUsed, long expectedEmitted) {
        var source = new FakePattern(TARGET, new FakeInput[] {new FakeInput(new GenericStack(A, 4))});
        var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        inventory.insert(A, stock, Actionable.MODULATE);
        CraftingStockPolicy policy = reserved == 0 ? null : (key, available) -> Math.max(0, available - reserved);

        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(TARGET, List.of(source)), Set.of(A)),
                new ChildCraftingSimulationState(inventory), null, TARGET, 1, false, policy);

        assertTrue(attempt.handled());
        assertFalse(attempt.plan().simulation());
        assertEquals(expectedUsed, attempt.plan().usedItems().get(A));
        assertEquals(expectedEmitted, attempt.plan().emittedItems().get(A));
        assertTrue(attempt.plan().missingItems().isEmpty());
        assertEquals(1L, attempt.plan().patternTimes().get(source));
        assertEquals(stock, inventory.extract(A, Long.MAX_VALUE, Actionable.SIMULATE),
                "planning must not mutate physical inventory");
    }

    @Test
    void togglingEmitterKeepsSufficientStockAllocationUnchanged() {
        var source = new FakePattern(TARGET, new FakeInput[] {new FakeInput(new GenericStack(A, 4))});
        var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        inventory.insert(A, 100, Actionable.MODULATE);
        for (Set<AEKey> emitted : List.of(Set.<AEKey>of(), Set.of(A), Set.<AEKey>of())) {
            var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(TARGET, List.of(source)), emitted),
                    new ChildCraftingSimulationState(inventory), null, TARGET, 1, false);
            assertFalse(attempt.plan().simulation());
            assertEquals(4L, attempt.plan().usedItems().get(A));
            assertTrue(attempt.plan().emittedItems().isEmpty());
        }
    }

    @Test
    void emittableIngredientRespectsGroupedStockPolicy() {
        var source = new FakePattern(TARGET, new FakeInput[] {new FakeInput(new GenericStack(A, 4))});
        var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        inventory.insert(A, 100, Actionable.MODULATE);
        CraftingStockPolicy policy = new CraftingStockPolicy() {
            @Override public long usablePreexistingStock(AEKey key, long available) {
                throw new AssertionError("must use the grouped policy");
            }
            @Override public boolean groupsSecondaryVariants(AEKey key) { return true; }
            @Override public long usablePreexistingStock(AEKey key, long available, Map<AEKey, Long> group) {
                if (key.equals(A)) {
                    assertEquals(100L, group.get(A));
                    return 2;
                }
                return available;
            }
        };

        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(TARGET, List.of(source)), Set.of(A)),
                new ChildCraftingSimulationState(inventory), null, TARGET, 1, false, policy);

        assertEquals(2L, attempt.plan().usedItems().get(A));
        assertEquals(2L, attempt.plan().emittedItems().get(A));
    }

    @Test
    void sharedEmittableIngredientDoesNotSpendStockTwice() {
        var makeD = new FakePattern(D, new FakeInput[] {new FakeInput(new GenericStack(A, 3))});
        var makeE = new FakePattern(E, new FakeInput[] {new FakeInput(new GenericStack(A, 2))});
        var target = new FakePattern(TARGET, new FakeInput[] {
                new FakeInput(new GenericStack(D, 1)), new FakeInput(new GenericStack(E, 1))});
        var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        inventory.insert(A, 4, Actionable.MODULATE);

        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(
                D, List.of(makeD), E, List.of(makeE), TARGET, List.of(target)), Set.of(A)),
                new ChildCraftingSimulationState(inventory), null, TARGET, 1, false);

        assertFalse(attempt.plan().simulation());
        assertEquals(4L, attempt.plan().usedItems().get(A));
        assertEquals(1L, attempt.plan().emittedItems().get(A));
        assertTrue(attempt.plan().missingItems().isEmpty());
    }

    @Test
    void requestingEmittableOutputStillIgnoresExistingOutputStock() {
        var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        inventory.insert(TARGET, 100, Actionable.MODULATE);
        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(), Set.of(TARGET)),
                new ChildCraftingSimulationState(inventory), null, TARGET, 4, false);
        assertFalse(attempt.plan().simulation());
        assertTrue(attempt.plan().usedItems().isEmpty());
        assertEquals(4L, attempt.plan().emittedItems().get(TARGET));
    }

    @ParameterizedTest
    @CsvSource({
            "2305843009213693951, 1, 9223372036854775807, 0",
            "9223372036854775807, 3, 10, 0",
            "9223372036854775807, 3, 0, 0",
            "9223372036854775807, 3, 10, 8",
            "9223372036854775807, 3, 10, 10"
    })
    void exactEmittablePreviewReportsOnlyThePolicyAllowedShortfallAsCrafting(
            long ingredientAmount, long requested, long stock, long reserved) {
        var source = new FakePattern(TARGET, new FakeInput[] {
                new FakeInput(new GenericStack(A, ingredientAmount))});
        var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        inventory.insert(A, stock, Actionable.MODULATE);
        CraftingStockPolicy policy = (key, available) -> Math.max(0, available - reserved);
        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(TARGET, List.of(source)), Set.of(A)),
                new ChildCraftingSimulationState(inventory), null, TARGET, requested, false, policy);

        var summary = com.moakiee.thunderbolt.ae2.crafting.ThunderboltCraftingPlanSummary.fromPlan(attempt.plan());
        var report = com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.get(summary);
        BigInteger demand = BigInteger.valueOf(ingredientAmount).multiply(BigInteger.valueOf(requested));
        BigInteger fromStock = demand.min(BigInteger.valueOf(stock - reserved));
        assertTrue(attempt.plan().simulation(), "wide plans must remain non-executable previews");
        assertTrue(attempt.plan().patternTimes().isEmpty());
        assertEquals(demand, report.entries().get(A).stored());
        assertEquals(BigInteger.ZERO, report.entries().get(A).missing());
        assertEquals(demand.subtract(fromStock), report.entries().get(A).crafting());
        assertEquals(stock, inventory.extract(A, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @Test
    void feasiblePlanLeavesExecutionAllocationToTheCpu() {
        var flexible = new FakePattern(D, new FakeInput[] {
                new FakeInput(new GenericStack(B, 1), new GenericStack(A, 1))});
        var strict = new FakePattern(E, new FakeInput[] {
                new FakeInput(new GenericStack(B, 1))});
        var target = new FakePattern(TARGET, new FakeInput[] {
                new FakeInput(new GenericStack(D, 1)), new FakeInput(new GenericStack(E, 1))});
        var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        inventory.insert(A, 1, Actionable.MODULATE);
        inventory.insert(B, 1, Actionable.MODULATE);
        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(
                D, List.of(flexible), E, List.of(strict), TARGET, List.of(target))),
                new ChildCraftingSimulationState(inventory), null, TARGET, 1, false);
        assertTrue(attempt.handled());
        assertFalse(attempt.plan().simulation());
        // Registered pattern identities remain intact for native CPUs and provider lookup.
        assertEquals(1L, attempt.plan().patternTimes().get(flexible));
        assertEquals(1L, attempt.plan().patternTimes().get(strict));
        assertEquals(1L, attempt.plan().usedItems().get(A));
        assertEquals(1L, attempt.plan().usedItems().get(B));
        assertTrue(com.moakiee.thunderbolt.core.crafting.plan.PlannedInputAssignments
                .get(attempt.plan()).isEmpty());
    }

    @Test
    void mixedCopiesRetainOneRegisteredPatternWithoutFixedBindings() {
        var source = new FakePattern(TARGET, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(B, 1), new GenericStack(A, 1))});
        var inventory = new appeng.crafting.inv.ListCraftingInventory(key -> {});
        inventory.insert(A, 1, Actionable.MODULATE);
        inventory.insert(B, 1, Actionable.MODULATE);
        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(TARGET, List.of(source))),
                new ChildCraftingSimulationState(inventory), null, TARGET, 2, false);
        assertTrue(attempt.handled());
        assertFalse(attempt.plan().simulation());
        assertEquals(2L, attempt.plan().patternTimes().get(source));
        assertEquals(1L, attempt.plan().usedItems().get(A));
        assertEquals(1L, attempt.plan().usedItems().get(B));
        assertTrue(com.moakiee.thunderbolt.core.crafting.plan.PlannedInputAssignments
                .get(attempt.plan()).isEmpty());
    }

    @Test
    void unrelatedShortfallSharingAFuzzyCandidateSurvivesPlanConversion() throws Exception {
        IPatternDetails producesA = new FakePattern(A, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(C, 1))
        });
        IPatternDetails consumesFuzzy = new FakePattern(D, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(A, 1), new GenericStack(B, 1))
        });
        IPatternDetails consumesExactB = new FakePattern(E, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(B, 1))
        });
        IPatternDetails producesTarget = new FakePattern(TARGET, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(D, 1)),
                new FakeInput(new GenericStack(E, 1))
        });

        CraftPattern<AEKey> makeA = new CraftPattern<>(
                A, 1, List.of(CraftInput.of(C, 1)), producesA);
        CraftPattern<AEKey> makeDFromA = new CraftPattern<>(
                D, 1, List.of(CraftInput.of(A, 1)), consumesFuzzy);
        CraftPattern<AEKey> makeDFromB = new CraftPattern<>(
                D, 1, List.of(CraftInput.of(B, 1)), consumesFuzzy);
        CraftPattern<AEKey> makeE = new CraftPattern<>(
                E, 1, List.of(CraftInput.of(B, 1)), consumesExactB);
        CraftPattern<AEKey> makeTarget = new CraftPattern<>(
                TARGET, 1, List.of(CraftInput.of(D, 1), CraftInput.of(E, 1)), producesTarget);

        CraftPlan<AEKey> internal = CraftPlannerV2.plan(
                CraftGraph.<AEKey>builder()
                        .pattern(makeA)
                        .pattern(makeDFromA)
                        .pattern(makeDFromB)
                        .pattern(makeE)
                        .pattern(makeTarget)
                        .stock(C, 1)
                        .build(),
                TARGET,
                1);

        CraftingPlan exported = convert(internal);

        assertFalse(internal.feasible());
        assertEquals(1L, internal.missing().get(B));
        assertEquals(1L, internal.firings().get(makeA));
        assertEquals(1L, internal.firings().get(makeDFromA));
        assertEquals(0L, internal.firings().getOrDefault(makeDFromB, 0L));
        assertTrue(exported.simulation());
        assertFalse(exported.missingItems().isEmpty(),
                "the fuzzy D route must not hide B required independently by E");
        assertEquals(1L, exported.missingItems().get(B));
    }

    @Test
    void wideAttemptReturnsNonExecutableFullPreviewAndExactSummary() {
        var batch = new FakePattern(A, new IPatternDetails.IInput[] {
                new FakeInput(new GenericStack(B, Long.MAX_VALUE))});
        var service = service(Map.of(A, List.of(batch)));
        var attempt = FastCraftingPlanner.tryAttempt(service,
                new ChildCraftingSimulationState(new EmptyInventory()), null, A, 3L, false);
        assertTrue(attempt.handled());
        assertTrue(attempt.plan().simulation(), "even the non-simulated probe must be preview-only");
        assertTrue(com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.isPreview(attempt.plan()),
                "the mixin finish path skips LoopCraftingPlan wrapping when this attachment is present");
        assertEquals(3L, attempt.plan().finalOutput().amount(), "do not replace the requested amount with a smaller job");
        assertTrue(attempt.plan().patternTimes().isEmpty(), "do not export executable truncated firing counts");
        var summary = com.moakiee.thunderbolt.ae2.crafting.ThunderboltCraftingPlanSummary.fromPlan(attempt.plan());
        var report = com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.get(summary);
        assertTrue(summary.isSimulation());
        assertEquals(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.valueOf(3)),
                report.entries().get(B).missing());
        assertTrue(report.bytes().compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        var raw = summary.getEntries().stream().filter(entry -> entry.getWhat().equals(B)).findFirst().orElseThrow();
        assertEquals(Long.MAX_VALUE, raw.getMissingAmount());
        assertEquals(report.entries().get(B), com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.amounts(raw));
    }

    @Test
    void ordinaryAttemptKeepsItsExistingExecutionPath() {
        var batch = new FakePattern(A, new IPatternDetails.IInput[0]);
        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(A, List.of(batch))),
                new ChildCraftingSimulationState(new EmptyInventory()), null, A, 3L, false);
        assertTrue(attempt.handled());
        assertFalse(attempt.plan().simulation());
        assertEquals(3L, attempt.plan().patternTimes().get(batch));
        assertFalse(com.moakiee.thunderbolt.ae2.crafting.ExactPlanReports.isPreview(attempt.plan()));
    }

    private static appeng.api.networking.crafting.ICraftingService service(
            Map<AEKey, List<IPatternDetails>> patterns) {
        return service(patterns, Set.of());
    }

    private static appeng.api.networking.crafting.ICraftingService service(
            Map<AEKey, List<IPatternDetails>> patterns, Set<AEKey> emittable) {
        return (appeng.api.networking.crafting.ICraftingService) java.lang.reflect.Proxy.newProxyInstance(
                FastCraftingPlannerPlanConversionTest.class.getClassLoader(),
                new Class<?>[] {appeng.api.networking.crafting.ICraftingService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCraftingFor" -> patterns.getOrDefault(args[0], List.of());
                    case "getCraftables" -> patterns.keySet();
                    case "canEmitFor" -> emittable.contains(args[0]);
                    case "getFuzzyCraftable" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CraftingPlan convert(CraftPlan<AEKey> internal) throws Exception {
        Method method = FastCraftingPlanner.class.getDeclaredMethod(
                "toAe2Plan", AEKey.class, long.class, CraftPlan.class,
                boolean.class, boolean.class, Map.class, Map.class, Set.class,
                ChildCraftingSimulationState.class, CraftingStockPolicy.class);
        method.setAccessible(true);
        var snapshot = new ChildCraftingSimulationState(new EmptyInventory());
        return (CraftingPlan) method.invoke(
                null, TARGET, 1L, internal, true, true, Map.of(), Map.of(), Set.of(), snapshot, null);
    }

    private record BatchPattern(AEKey output, long amount, IInput[] inputs) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public GenericStack[] getOutputs() {
            return new GenericStack[] {new GenericStack(output, amount)};
        }
    }

    private record FakePattern(AEKey output, IInput[] inputs) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public GenericStack[] getOutputs() {
            return new GenericStack[] { new GenericStack(output, 1) };
        }
    }

    private record FakeInput(GenericStack... possible) implements IPatternDetails.IInput {
        @Override public GenericStack[] getPossibleInputs() { return possible; }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey key, Level level) { return true; }
        @Override public AEKey getRemainingKey(AEKey key) { return null; }
    }

    private static final class EmptyInventory implements ICraftingInventory {
        @Override public void insert(AEKey key, long amount, Actionable mode) { }
        @Override public long extract(AEKey key, long amount, Actionable mode) { return 0; }
        @Override public Iterable<AEKey> findFuzzyTemplates(AEKey key) {
            return Collections.emptyList();
        }
    }

    private static final class TestKey extends AEKey {
        private static final TestKeyType TYPE = new TestKeyType();
        private final String id;

        private TestKey(String id) {
            this.id = id;
        }

        @Override public AEKeyType getType() { return TYPE; }
        @Override public AEKey dropSecondary() { return this; }
        @Override public CompoundTag toTag() {
            var tag = new CompoundTag();
            tag.putString("id", id);
            return tag;
        }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() {
            return new ResourceLocation("thunderbolt_test", id);
        }
        @Override public void writeToPacket(FriendlyByteBuf data) { }
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(
                long amount, List<ItemStack> drops, Level level, BlockPos pos) { }
        @Override public boolean equals(Object obj) {
            return obj instanceof TestKey other && id.equals(other.id);
        }
        @Override public int hashCode() { return id.hashCode(); }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(new ResourceLocation("thunderbolt_test", "simulation_key"),
                    TestKey.class, Component.literal("simulation key"));
        }
        @Override public AEKey loadKeyFromTag(CompoundTag tag) { return null; }
        @Override public AEKey readFromPacket(FriendlyByteBuf input) { return null; }
    }
}
