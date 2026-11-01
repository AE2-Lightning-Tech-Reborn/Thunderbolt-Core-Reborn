package com.moakiee.thunderbolt.core.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import com.moakiee.thunderbolt.compat.gto.inv.ChildCraftingSimulationState;
import com.moakiee.thunderbolt.core.crafting.pattern.FuzzyPatternInputs;
import com.moakiee.thunderbolt.test.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses interned item/NBT keys from the actual AE2 dependency in either ABI. */
class PlannerAe2AbiTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.ensureInitialized(); }

    @Test
    void lateBoundKeysAreCanonicalWithinOneCalculationAndKeepHashContracts() {
        var pool = new LateBoundOutputKey.Pool();
        var catalog = stoneVariant(1);
        var key = pool.get(catalog);
        assertSame(key, pool.get(stoneVariant(1)));
        assertSame(pool.get(AEItemKey.of(Items.STONE)), key.dropSecondary());
        assertSame(key.dropSecondary(), key.dropSecondary().dropSecondary());
        assertNotEquals(catalog, key);
        var independent = new LateBoundOutputKey.Pool().get(catalog);
        assertNotEquals(key, independent);
        assertEquals(2, new HashSet<>(List.of(key, pool.get(catalog), independent)).size());
        assertSame(catalog, LateBoundOutputKey.physical(key));
        assertThrows(IllegalStateException.class, key::toTag);
        assertThrows(IllegalArgumentException.class, () -> pool.get(key));
    }

    @Test
    void sameIdDemandReusesOneNodeButSeparatesUnitSizesAndCalculations() {
        var groups = new IdOnlyInputGroups();
        var builder = CraftGraph.<AEKey>builder();
        var first = stoneVariant(1);
        var second = stoneVariant(2);
        var one = List.of(CraftInput.<AEKey>of(first, 1), CraftInput.<AEKey>of(second, 1));
        var key = groups.register(one, builder, ignored -> {}, () -> {});
        assertNotNull(key);
        assertSame(key, groups.register(List.of(one.get(1), one.get(0)), builder, ignored -> {}, () -> {}));
        var two = groups.register(List.of(CraftInput.<AEKey>of(first, 2)), builder, ignored -> {}, () -> {});
        assertNotEquals(key, two);
        var separate = new IdOnlyInputGroups().register(one, CraftGraph.builder(), ignored -> {}, () -> {});
        assertNotEquals(key, separate);
        assertEquals(3, new HashSet<>(List.of(key, two, separate)).size());
        assertSame(key, key.dropSecondary());
        assertThrows(IllegalStateException.class, key::toTag);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lateBoundProducerFeedsOnlyDeclaredSameIdConsumers(boolean fuzzy) {
        var base = AEItemKey.of(Items.IRON_INGOT);
        var target = AEItemKey.of(Items.DIAMOND);
        var declared = stoneVariant(1);
        var produced = stoneVariant(2);
        var producer = new Pattern(produced, new Input[]{new Input(base, 1, false)}, false, true);
        var consumer = new Pattern(target, new Input[]{new Input(declared, 1, fuzzy)}, fuzzy, false);
        var service = service(Map.of(produced, List.of(producer), target, List.of(consumer)));
        var stock = new ListCraftingInventory(ignored -> {});
        stock.insert(base, 3, Actionable.MODULATE);
        var attempt = FastCraftingPlanner.tryAttempt(service,
                new ChildCraftingSimulationState(stock), null, target, 3, false);
        assertTrue(attempt.handled());
        if (fuzzy) {
            assertNotNull(attempt.plan());
            assertEquals(3, attempt.plan().patternTimes().getLong(producer));
            assertEquals(3, attempt.plan().patternTimes().getLong(consumer));
            assertEquals(3, attempt.plan().usedItems().get(base));
            assertTrue(attempt.plan().missingItems().isEmpty());
            assertSame(target, attempt.plan().finalOutput().what());
        } else {
            assertNull(attempt.plan());
            assertEquals(3, attempt.simulationFallback().missingItems().get(declared));
        }
        assertEquals(3, stock.extract(base, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    @ParameterizedTest @ValueSource(longs = {3, 4})
    void differentUnitGroupsShareOnlyPhysicalStock(long amount) {
        var target = AEItemKey.of(Items.DIAMOND);
        var consumer = new Pattern(target, new Input[]{
                new Input(stoneVariant(1), 2, true), new Input(stoneVariant(2), 1, true, 2)}, true, false);
        var stock = new ListCraftingInventory(ignored -> {});
        var available = stoneVariant(3);
        stock.insert(available, amount, Actionable.MODULATE);
        var attempt = FastCraftingPlanner.tryAttempt(service(Map.of(target, List.of(consumer))),
                new ChildCraftingSimulationState(stock), null, target, 1, false);
        if (amount < 4) {
            assertNull(attempt.plan());
        } else {
            assertNotNull(attempt.plan());
            assertEquals(4, attempt.plan().usedItems().get(available));
            assertEquals(1, attempt.plan().patternTimes().size());
            assertEquals(1, attempt.plan().patternTimes().getLong(consumer));
        }
        assertEquals(amount, stock.extract(available, Long.MAX_VALUE, Actionable.SIMULATE));
    }

    private static AEItemKey stoneVariant(int variant) {
        var tag = new CompoundTag();
        tag.putInt("variant", variant);
        return AEItemKey.of(Items.STONE, tag);
    }

    private static ICraftingService service(Map<AEKey, List<IPatternDetails>> patterns) {
        return (ICraftingService) Proxy.newProxyInstance(PlannerAe2AbiTest.class.getClassLoader(),
                new Class<?>[]{ICraftingService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCraftingFor" -> patterns.getOrDefault(args[0], List.of());
                    case "getCraftables" -> {
                        var filter = (appeng.api.storage.AEKeyFilter) args[0];
                        var result = new HashSet<AEKey>();
                        for (var key : patterns.keySet()) if (filter.matches(key)) result.add(key);
                        yield result;
                    }
                    case "getFuzzyCraftable" -> patterns.keySet().stream()
                            .filter(key -> key.dropSecondary().equals(((AEKey) args[0]).dropSecondary()))
                            .filter(key -> ((appeng.api.storage.AEKeyFilter) args[1]).matches(key))
                            .findFirst().orElse(null);
                    case "canEmitFor", "isRequesting", "isRequestingAny" -> false;
                    case "getRequestedAmount" -> 0L;
                    case "toString" -> "AE2 ABI crafting service";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("Unexpected service call: " + method);
                });
    }

    private record Input(AEKey key, long amount, boolean fuzzy, long multiplier) implements IPatternDetails.IInput {
        private Input(AEKey key, long amount, boolean fuzzy) { this(key, amount, fuzzy, 1); }
        @Override public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(key, amount)}; }
        @Override public long getMultiplier() { return multiplier; }
        @Override public boolean isValid(AEKey candidate, Level level) {
            return fuzzy ? key.dropSecondary().equals(candidate.dropSecondary()) : key == candidate;
        }
        @Override public AEKey getRemainingKey(AEKey consumed) { return null; }
    }

    private record Pattern(AEKey output, Input[] inputs, boolean fuzzyInputs, boolean fuzzyOutput)
            implements IPatternDetails, FuzzyPatternInputs {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return inputs; }
        @Override public GenericStack[] getOutputs() { return new GenericStack[]{new GenericStack(output, 1)}; }
        @Override public boolean acceptsSameIdVariants(int slot) { return fuzzyInputs; }
        @Override public boolean producesSameIdVariants(int slot) { return fuzzyOutput; }
    }
}
