package com.moakiee.thunderbolt.core.keys;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeyConstructionCacheTest {
    static {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final AtomicInteger constructions = new AtomicInteger();
    private final Operation<AEItemKey> items = args -> {
        constructions.incrementAndGet();
        return AEItemKey.of((ItemStack) args[0]);
    };
    private final Operation<AEFluidKey> fluids = args -> {
        constructions.incrementAndGet();
        return AEFluidKey.of((FluidStack) args[0]);
    };

    @BeforeEach void enable() { KeyConstructionCache.configure(true); }
    @AfterEach void disable() { KeyConstructionCache.configure(false); }

    @Test void ordinaryKeysAvoidRepeatedConstructionButKeepValueEquality() {
        var input = new ItemStack(Items.IRON_INGOT);
        var first = KeyConstructionCache.item(input, items);
        assertSame(first, KeyConstructionCache.item(input.copy(), items));
        assertEquals(1, constructions.get());
        var independent = AEItemKey.of(input);
        assertNotSame(first, independent);
        assertEquals(first, independent);
        assertEquals(first.hashCode(), independent.hashCode());
    }

    @Test void componentVariantsAndRemovedDefaultsKeepNativeIdentity() {
        var plain = new ItemStack(Items.IRON_INGOT);
        var base = KeyConstructionCache.item(plain, items);
        var named = plain.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("retained"));
        var first = KeyConstructionCache.item(named, items);
        assertNotSame(first, KeyConstructionCache.item(named, items));
        assertEquals(Component.literal("retained"), first.get(DataComponents.CUSTOM_NAME));
        assertNotEquals(base, first);
        var removed = plain.copy();
        removed.remove(DataComponents.RARITY);
        assertNotEquals(base, KeyConstructionCache.item(removed, items));
    }

    @Test void countsAndAnimationsShareOneNormalizedKeyWithoutChangingInputs() {
        var canonical = KeyConstructionCache.item(new ItemStack(Items.STONE), items);
        for (int count : new int[]{1, 2, 64}) {
            var source = new ItemStack(Items.STONE, count);
            source.setPopTime(5);
            var key = KeyConstructionCache.item(source, items);
            assertSame(canonical, key);
            assertSame(key, KeyConstructionCache.findItem(source));
            assertEquals(1, key.getReadOnlyStack().getCount());
            assertEquals(0, key.getReadOnlyStack().getPopTime());
            assertEquals(count, source.getCount());
            assertEquals(5, source.getPopTime());
            assertEquals(20, key.toStack(20).getCount());
        }
        KeyConstructionCache.configure(false);
        var nativeSource = new ItemStack(Items.STONE, 64);
        nativeSource.setPopTime(5);
        var nativeKey = KeyConstructionCache.item(nativeSource, items);
        assertEquals(64, nativeKey.getReadOnlyStack().getCount());
        assertEquals(5, nativeKey.getReadOnlyStack().getPopTime());
    }

    @Test void fluidVariantsBypassAndCallerMutationDoesNotChangeExistingKeys() {
        var stack = new FluidStack(Fluids.WATER, 1);
        var first = KeyConstructionCache.fluid(stack, fluids);
        assertSame(first, KeyConstructionCache.fluid(stack.copy(), fluids));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("water"));
        var named = KeyConstructionCache.fluid(stack, fluids);
        assertNotEquals(first, named);
        assertNull(first.get(DataComponents.CUSTOM_NAME));
        assertNotSame(named, KeyConstructionCache.fluid(stack, fluids));
    }

    @Test void earlyFluidProbeKeepsAmountsEmptinessAndCallerMutationIndependent() {
        var source = new FluidStack(Fluids.WATER, 1000);
        assertNull(KeyConstructionCache.findFluid(source));
        var key = KeyConstructionCache.fluid(source.copyWithAmount(1), fluids);
        for (int amount : new int[]{1, 17, 1000, Integer.MAX_VALUE}) {
            source.setAmount(amount);
            assertSame(key, KeyConstructionCache.findFluid(source));
            assertEquals(amount, source.getAmount());
        }
        for (int amount : new int[]{0, -1}) {
            source.setAmount(amount);
            assertNull(KeyConstructionCache.findFluid(source));
        }
        assertNull(KeyConstructionCache.findFluid(FluidStack.EMPTY));
        assertNull(KeyConstructionCache.findFluid(new FluidStack(Fluids.EMPTY, 1)));
        source.setAmount(1000);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        assertNull(KeyConstructionCache.findFluid(source));
        assertNull(key.get(DataComponents.CUSTOM_NAME));
        KeyConstructionCache.clear();
        assertNull(KeyConstructionCache.findFluid(new FluidStack(Fluids.WATER, 1)));
        KeyConstructionCache.configure(false);
        assertNull(KeyConstructionCache.findFluid(new FluidStack(Fluids.WATER, 1)));
    }

    @Test void clearingDuringFluidConstructionCannotRepopulateTheNewGeneration() {
        var source = new FluidStack(Fluids.WATER, 1);
        var old = KeyConstructionCache.fluid(source, args -> {
            KeyConstructionCache.clear();
            return AEFluidKey.of((FluidStack) args[0]);
        });
        assertNull(KeyConstructionCache.findFluid(source));
        var next = KeyConstructionCache.fluid(source, fluids);
        assertNotSame(old, next);
        assertEquals(old, next);
        assertEquals(old.hashCode(), next.hashCode());
    }

    @Test void clearAndDisableNeverChangeKeyEquality() {
        var stack = new ItemStack(Items.STONE);
        var first = KeyConstructionCache.item(stack, items);
        KeyConstructionCache.clear();
        var next = KeyConstructionCache.item(stack, items);
        assertNotSame(first, next);
        assertEquals(first, next);
        KeyConstructionCache.configure(false);
        var uncached = KeyConstructionCache.item(stack, items);
        assertNotSame(uncached, KeyConstructionCache.item(stack, items));
        assertEquals(next, uncached);
        KeyConstructionCache.clear();
        assertFalse(KeyConstructionCache.enabled());
    }

    @Test void concurrentFirstMissesCanSafelyReturnDistinctEqualKeys() throws Exception {
        var gate = new CyclicBarrier(2);
        Operation<AEItemKey> concurrentConstructor = args -> {
            try { gate.await(5, TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError(e); }
            return AEItemKey.of((ItemStack) args[0]);
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> KeyConstructionCache.item(
                    new ItemStack(Items.STONE), concurrentConstructor));
            var b = executor.submit(() -> KeyConstructionCache.item(
                    new ItemStack(Items.STONE), concurrentConstructor));
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertNotSame(first, second);
            assertEquals(first, second);
            assertEquals(1, Map.of(first, 1).get(second));
        }
    }

    @Test void clearingDuringConstructionCannotRepopulateTheNewGeneration() {
        var stack = new ItemStack(Items.STONE);
        var first = KeyConstructionCache.item(stack, args -> {
            KeyConstructionCache.clear();
            return AEItemKey.of((ItemStack) args[0]);
        });
        assertNotSame(first, KeyConstructionCache.item(stack, items));
    }

    @Test void badReadOnlyStackMutationDoesNotPoisonLaterRequests() {
        var stack = new ItemStack(Items.STONE);
        var first = KeyConstructionCache.item(stack, items);
        // Third-party misuse of AE2's NEVER MUTATE THIS accessor must not spread through new hits.
        first.getReadOnlyStack().setCount(0);
        var next = KeyConstructionCache.item(stack, items);
        assertNotSame(first, next);
        assertEquals(1, next.getReadOnlyStack().getCount());
    }

    @Test void earlyProbeNeverPublishesOrRetainsTheCallerStack() {
        var input = new ItemStack(Items.STONE);
        assertNull(KeyConstructionCache.findItem(input));
        var key = KeyConstructionCache.item(input.copy(), items);
        assertSame(key, KeyConstructionCache.findItem(input));
        input.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        assertNull(KeyConstructionCache.findItem(input));
        assertNull(key.get(DataComponents.CUSTOM_NAME));
        assertSame(key, KeyConstructionCache.findItem(new ItemStack(Items.STONE)));
        KeyConstructionCache.clear();
        assertNull(KeyConstructionCache.findItem(new ItemStack(Items.STONE)));
        KeyConstructionCache.configure(false);
        assertNull(KeyConstructionCache.findItem(new ItemStack(Items.STONE)));
    }

    @Test void plainRegistrySlotsAreNotEvictedByOtherItemsOrComponents() {
        var source = new ItemStack(Items.IRON_INGOT);
        var single = KeyConstructionCache.item(source.copy(), items);
        var bulk = KeyConstructionCache.item(source.copyWithCount(64), items);
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (item == Items.AIR || item == Items.IRON_INGOT) continue;
            KeyConstructionCache.item(new ItemStack(item), items);
        }
        assertSame(single, KeyConstructionCache.findItem(source));
        assertSame(single, bulk);
        assertSame(bulk, KeyConstructionCache.findItem(source.copyWithCount(64)));
        var animated = source.copy(); animated.setPopTime(4);
        assertSame(single, KeyConstructionCache.findItem(animated));
        var named = source.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("variant"));
        KeyConstructionCache.item(named, items);
        assertSame(single, KeyConstructionCache.findItem(source));
    }

    @Test void earlyProbeRejectsIllegallyMutatedCachedContents() {
        var input = new ItemStack(Items.STONE);
        var key = KeyConstructionCache.item(input.copy(), items);
        key.getReadOnlyStack().set(DataComponents.CUSTOM_NAME, Component.literal("bad caller"));
        assertNull(KeyConstructionCache.findItem(input));
    }
}
