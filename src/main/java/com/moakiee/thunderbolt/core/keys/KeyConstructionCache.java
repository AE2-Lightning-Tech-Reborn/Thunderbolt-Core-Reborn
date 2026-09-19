package com.moakiee.thunderbolt.core.keys;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicInteger;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Reuses AE2 keys through per-Item/per-Fluid plain slots, weak component canonicalization and a bounded fluid
 * cache. Bypasses, clearing and concurrent plain misses may return equal, distinct objects. AE2's
 * value equality must remain intact. No caller-owned mutable stack is retained.
 */
public final class KeyConstructionCache {
    private static final int CAPACITY = 4096;
    private static final Object EMPTY_PATCH = new Object();
    // Disabled until the common config has loaded. Swapping tables also discards in-flight writes
    // to an old generation without locking the factory or calling mod code under a cache lock.
    private static volatile Tables tables;

    private record Tables(Object generation, AtomicReferenceArray<PlainItemKeyCache> plainItems,
                          AtomicReferenceArray<PlainFluidKeyCache> plainFluids,
                          AtomicInteger maintenanceCursor,
                          WeakCanonicalMap.IdentityTable<AEItemKey> identities,
                          AtomicReferenceArray<AEItemKey> items,
                          AtomicReferenceArray<AEFluidKey> fluids, boolean components) {
        private Tables(boolean components) {
            // Per-item holders share one normalized plain key without hash collisions.
            // Item fields are detached when a generation ends, including never-used-again Items.
            this(new Object(), new AtomicReferenceArray<>(BuiltInRegistries.ITEM.size()),
                    new AtomicReferenceArray<>(BuiltInRegistries.FLUID.size()),
                    new AtomicInteger(),
                    new WeakCanonicalMap.IdentityTable<>(4096),
                    new AtomicReferenceArray<>(CAPACITY), new AtomicReferenceArray<>(CAPACITY), components);
        }
    }

    private KeyConstructionCache() {}

    public static synchronized void configure(boolean enabled) {
        configure(enabled, true);
    }

    public static synchronized void configure(boolean enabled, boolean components) {
        var previous = tables;
        tables = enabled ? new Tables(components) : null;
        if (previous != null) {
            for (int i = 0; i < previous.plainItems.length(); i++) {
                var cached = previous.plainItems.get(i);
                if (cached != null && cached.owner != null) cached.owner.thunderbolt$clearPlainKeyCache(cached);
            }
            for (int i = 0; i < previous.plainFluids.length(); i++) {
                var cached = previous.plainFluids.get(i);
                if (cached != null) cached.owner.thunderbolt$clearFluidKey(cached);
            }
        }
    }

    public static synchronized void clear() {
        if (tables != null) configure(true, tables.components);
    }

    public static boolean enabled() {
        return tables != null;
    }

    public static boolean isCurrent(PlainItemKeyCache cache) {
        var current = tables;
        return current != null && cache.generation == current.generation;
    }

    public static boolean isCurrent(PlainFluidKeyCache cache) {
        var current = tables;
        return current != null && cache.generation == current.generation;
    }

    /** Incremental idle cleanup, shared by client/server ticks. Never scans the entire registry. */
    public static void maintain() {
        var current = tables;
        if (current == null || !current.components || current.plainItems.length() == 0) return;
        int length = current.plainItems.length();
        int start = current.maintenanceCursor.getAndAdd(32);
        for (int i = 0; i < Math.min(32, length); i++) {
            var holder = current.plainItems.get(Math.floorMod(start + i, length));
            var cache = holder != null ? holder.components(false, current.identities) : null;
            if (cache != null) cache.cleanUp();
        }
    }

    /** Read-only probe before the native factory copies its caller-owned stack. */
    public static AEItemKey findItem(ItemStack source) {
        var current = tables;
        if (current == null || source.isEmpty()) return null;
        if (source.isComponentsPatchEmpty()) {
            var plain = plainCache(current, source, false);
            if (plain != null) {
                var cached = plain.key;
                return matchesItem(cached, source, EMPTY_PATCH, current.components) ? cached : null;
            }
        }
        Object patch = source.getComponents() instanceof SharedComponentPatch access && current.components
                ? access.thunderbolt$copyOnWritePatchIdentity() : null;
        if (patch == null) return null;
        var holder = plainCache(current, source, false);
        var components = holder != null ? holder.components(false, current.identities) : null;
        if (components != null && source.getComponents() instanceof SharedComponentPatch access) {
            return components.findIdentity(source, patch, access.thunderbolt$prototypeIdentity());
        }
        var cached = current.items.get(slot(source.getItem(), patch));
        return matchesItem(cached, source, patch, current.components) ? cached : null;
    }

    /** The stack is a native copy or the exact key-owned stack supplied by the validated pre-copy probe. */
    public static AEItemKey item(ItemStack ownedStack, Operation<AEItemKey> constructor) {
        var current = tables;
        if (current == null || ownedStack.isEmpty()) return constructor.call(ownedStack);
        ownedStack = normalizedStack(ownedStack);
        Object patch = patchIdentity(ownedStack.getComponents(), ownedStack.isComponentsPatchEmpty(), current.components);
        if (patch == null) return constructor.call(ownedStack);
        if (patch != EMPTY_PATCH && ownedStack.getComponents() instanceof SharedComponentPatch access) {
            var holder = plainCache(current, ownedStack, true);
            if (holder != null) return holder.components(true, current.identities).getOrCreate(ownedStack, patch,
                    access.thunderbolt$prototypeIdentity(), constructor);
        }
        var plain = patch == EMPTY_PATCH ? plainCache(current, ownedStack, true) : null;
        if (plain != null) {
            var cached = plain.key;
            if (cached != null && (cached.getReadOnlyStack() == ownedStack
                    || matchesItem(cached, ownedStack, EMPTY_PATCH, current.components))) return cached;
            var result = constructor.call(ownedStack);
            plain.key = result;
            if (plain.staticHooks
                    && ownedStack.getComponents() instanceof SharedComponentPatch
                    && result.matches(ownedStack) && result.getReadOnlyStack().getCount() == 1
                    && result.getReadOnlyStack().getPopTime() == 0) {
                plain.owner.thunderbolt$publishPlainKey(plain, result);
            }
            return result;
        }
        int slot = slot(ownedStack.getItem(), patch);
        var cached = current.items.get(slot);
        if (cached != null && (cached.getReadOnlyStack() == ownedStack
                || matchesItem(cached, ownedStack, patch, current.components))) return cached;
        var result = constructor.call(ownedStack);
        current.items.set(slot, result);
        return result;
    }

    /** Returns a normalized view without changing the caller's stack; already normalized stacks are reused. */
    public static ItemStack normalizedStack(ItemStack source) {
        if (source.isEmpty() || source.getCount() == 1 && source.getPopTime() == 0) return source;
        var copy = source.copyWithCount(1);
        copy.setPopTime(0);
        return copy;
    }

    private static boolean matchesItem(AEItemKey cached, ItemStack ownedStack, Object patch, boolean components) {
        if (cached == null) return false;
        var metadata = normalizedStack(ownedStack);
        return cached != null && cached.getItem() == ownedStack.getItem()
                && cached.getReadOnlyStack().getCount() == 1
                && cached.getReadOnlyStack().getPopTime() == 0
                && patchIdentity(cached.getReadOnlyStack().getComponents(),
                        cached.getReadOnlyStack().isComponentsPatchEmpty(), components) == patch
                && cached.matches(ownedStack)
                // Preserve NeoForge's stack-sensitive item hooks, even for a component-free stack.
                && cached.getMaxStackSize() == metadata.getMaxStackSize()
                && cached.getFuzzySearchValue() == metadata.getDamageValue();
    }

    private static PlainItemKeyCache plainCache(Tables current, ItemStack stack, boolean create) {
        ItemKeyCacheOwner owner = (Object) stack.getItem() instanceof ItemKeyCacheOwner access ? access : null;
        if (owner != null) {
            var cached = owner.thunderbolt$plainKeyCache();
            if (cached != null && cached.generation == current.generation) return cached;
        }
        int id = BuiltInRegistries.ITEM.getId(stack.getItem());
        // Unknown or late-registered items keep the bounded generic-cache path.
        if (id < 0 || id >= current.plainItems.length()) return null;
        var cached = current.plainItems.get(id);
        if (cached == null && create) {
            var fresh = new PlainItemKeyCache(current.generation, owner);
            var witness = current.plainItems.compareAndExchange(id, null, fresh);
            cached = witness == null ? fresh : witness;
        }
        if (cached != null && owner != null) owner.thunderbolt$plainKeyCache(cached);
        return cached;
    }

    /** Read-only fluid probe: amounts are not part of an AE key, but empty stacks are invalid. */
    public static AEFluidKey findFluid(FluidStack source) {
        var current = tables;
        if (current == null || source.isEmpty()) return null;
        Object patch = source.isComponentsPatchEmpty() ? EMPTY_PATCH
                : (Object) source.getComponents() instanceof SharedComponentPatch access && current.components
                        ? access.thunderbolt$copyOnWritePatchIdentity() : null;
        if (patch == null) return null;
        var cached = current.fluids.get(slot(source.getFluid(), patch));
        return matchesFluid(cached, source, patch, current.components) ? cached : null;
    }

    /** AE2 has already copied the fluid stack and normalized its amount to one. */
    public static AEFluidKey fluid(FluidStack ownedStack, Operation<AEFluidKey> constructor) {
        var current = tables;
        if (current == null || ownedStack.isEmpty() || ownedStack.getAmount() != 1) return constructor.call(ownedStack);
        Object patch = patchIdentity(ownedStack.getComponents(), ownedStack.isComponentsPatchEmpty(), current.components);
        if (patch == null) return constructor.call(ownedStack);
        int slot = slot(ownedStack.getFluid(), patch);
        var cached = current.fluids.get(slot);
        if (matchesFluid(cached, ownedStack, patch, current.components)) return cached;
        var result = constructor.call(ownedStack);
        current.fluids.set(slot, result);
        if (patch == EMPTY_PATCH && isPlainFluidStack(ownedStack)
                && result != null && result.matches(ownedStack)
                && (Object) result instanceof FluidKeyComponents access
                && access.thunderbolt$components().isEmpty()) {
            publishFluid(current, ownedStack, result);
        }
        return result;
    }

    /** A custom prototype with an empty patch is not necessarily an ordinary fluid. */
    public static boolean isPlainFluidStack(FluidStack source) {
        return source.isComponentsPatchEmpty()
                && (Object) source.getComponents() instanceof SharedComponentPatch access
                && access.thunderbolt$prototypeIdentity() == DataComponentMap.EMPTY;
    }

    private static void publishFluid(Tables current, FluidStack stack, AEFluidKey key) {
        if (!((Object) stack.getFluid() instanceof FluidKeyCacheOwner owner)) return;
        int id = BuiltInRegistries.FLUID.getId(stack.getFluid());
        if (id < 0 || id >= current.plainFluids.length()) return;
        var holder = current.plainFluids.get(id);
        if (holder == null) {
            var fresh = new PlainFluidKeyCache(current.generation, owner);
            var witness = current.plainFluids.compareAndExchange(id, null, fresh);
            holder = witness == null ? fresh : witness;
        }
        owner.thunderbolt$publishFluidKey(holder, key);
    }

    private static boolean matchesFluid(AEFluidKey cached, FluidStack source, Object patch, boolean components) {
        return cached != null && (patch == EMPTY_PATCH || (Object) cached instanceof FluidKeyComponents access
                && patchIdentity(access.thunderbolt$components(), false, components) == patch)
                && cached.matches(source);
    }

    private static Object patchIdentity(DataComponentMap components, boolean empty, boolean enabled) {
        if (empty) return EMPTY_PATCH;
        return enabled && components instanceof SharedComponentPatch access
                ? access.thunderbolt$sharedPatchIdentity() : null;
    }

    private static int slot(Object primary, Object patch) {
        int hash = System.identityHashCode(primary);
        if (patch != EMPTY_PATCH) hash = 31 * hash + System.identityHashCode(patch);
        return (hash ^ (hash >>> 16)) & (CAPACITY - 1);
    }
}
