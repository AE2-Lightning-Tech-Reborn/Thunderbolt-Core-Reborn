package com.moakiee.thunderbolt.core.keys;

import java.util.concurrent.atomic.AtomicReferenceArray;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponentMap;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Bounded reuse of AE2 item/fluid keys at the native constructor call site. This is a cache, not an
 * interner: collisions, eviction and concurrent misses may return equal, distinct objects. AE2's
 * value equality must remain intact. No caller-owned mutable stack is retained.
 */
public final class KeyConstructionCache {
    private static final int CAPACITY = 4096;
    private static final Object EMPTY_PATCH = new Object();
    // Disabled until the common config has loaded. Swapping tables also discards in-flight writes
    // to an old generation without locking the factory or calling mod code under a cache lock.
    private static volatile Tables tables;

    private record Tables(AtomicReferenceArray<AEItemKey> items,
                          AtomicReferenceArray<AEFluidKey> fluids, boolean components) {
        private Tables(boolean components) {
            this(new AtomicReferenceArray<>(CAPACITY), new AtomicReferenceArray<>(CAPACITY), components);
        }
    }

    private KeyConstructionCache() {}

    public static synchronized void configure(boolean enabled) {
        configure(enabled, true);
    }

    public static synchronized void configure(boolean enabled, boolean components) {
        tables = enabled ? new Tables(components) : null;
    }

    public static synchronized void clear() {
        if (tables != null) configure(true, tables.components);
    }

    public static boolean enabled() {
        return tables != null;
    }

    /** The supplied stack has already gone through AE2's normal ItemStack.copy() call. */
    public static AEItemKey item(ItemStack ownedStack, Operation<AEItemKey> constructor) {
        var current = tables;
        if (current == null || ownedStack.isEmpty()) return constructor.call(ownedStack);
        Object patch = patchIdentity(ownedStack.getComponents(), ownedStack.isComponentsPatchEmpty(), current.components);
        if (patch == null) return constructor.call(ownedStack);
        int slot = slot(ownedStack.getItem(), patch, ownedStack.getCount(), ownedStack.getPopTime());
        var cached = current.items.get(slot);
        if (cached != null && cached.getItem() == ownedStack.getItem()
                && cached.getReadOnlyStack().getCount() == ownedStack.getCount()
                && cached.getReadOnlyStack().getPopTime() == ownedStack.getPopTime()
                && patchIdentity(cached.getReadOnlyStack().getComponents(),
                        cached.getReadOnlyStack().isComponentsPatchEmpty(), current.components) == patch
                && cached.matches(ownedStack)
                // Preserve NeoForge's stack-sensitive item hooks, even for a component-free stack.
                && cached.getMaxStackSize() == ownedStack.getMaxStackSize()
                && cached.getFuzzySearchValue() == ownedStack.getDamageValue()) {
            return cached;
        }
        var result = constructor.call(ownedStack);
        current.items.set(slot, result);
        return result;
    }

    /** AE2 has already copied the fluid stack and normalized its amount to one. */
    public static AEFluidKey fluid(FluidStack ownedStack, Operation<AEFluidKey> constructor) {
        var current = tables;
        if (current == null || ownedStack.isEmpty() || ownedStack.getAmount() != 1) return constructor.call(ownedStack);
        Object patch = patchIdentity(ownedStack.getComponents(), ownedStack.isComponentsPatchEmpty(), current.components);
        if (patch == null) return constructor.call(ownedStack);
        int slot = slot(ownedStack.getFluid(), patch, 1, 0);
        var cached = current.fluids.get(slot);
        if (cached != null && (patch == EMPTY_PATCH || (Object) cached instanceof FluidKeyComponents access
                && patchIdentity(access.thunderbolt$components(), false, current.components) == patch)
                && cached.matches(ownedStack)) return cached;
        var result = constructor.call(ownedStack);
        current.fluids.set(slot, result);
        return result;
    }

    private static Object patchIdentity(DataComponentMap components, boolean empty, boolean enabled) {
        if (empty) return EMPTY_PATCH;
        return enabled && components instanceof SharedComponentPatch access
                ? access.thunderbolt$sharedPatchIdentity() : null;
    }

    private static int slot(Object primary, Object patch, int count, int popTime) {
        int hash = 31 * (31 * System.identityHashCode(primary) + count) + popTime;
        if (patch != EMPTY_PATCH) hash = 31 * hash + System.identityHashCode(patch);
        return (hash ^ (hash >>> 16)) & (CAPACITY - 1);
    }
}
