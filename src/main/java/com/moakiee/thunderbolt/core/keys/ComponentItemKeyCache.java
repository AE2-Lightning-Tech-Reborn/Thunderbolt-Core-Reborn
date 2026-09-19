package com.moakiee.thunderbolt.core.keys;

import appeng.api.stacks.AEItemKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

/** Per-item aliases and content lookup weakly reference the actual key and its existing owned snapshot. */
public final class ComponentItemKeyCache {
    private final WeakCanonicalMap<Object, AEItemKey> keys;
    private final boolean staticHooks;

    ComponentItemKeyCache(WeakCanonicalMap.IdentityTable<AEItemKey> identities, boolean staticHooks) {
        this.keys = new WeakCanonicalMap<>(identities, ComponentItemKeyCache::canonicalPatch);
        this.staticHooks = staticHooks;
    }

    void cleanUp() { keys.cleanUp(); }

    public AEItemKey findIdentity(ItemStack source, Object patch, Object prototype) {
        var key = keys.findIdentity(patch, 0);
        return key != null && matchesIdentity(key, source, prototype) ? key : null;
    }

    public AEItemKey getOrCreate(ItemStack ownedStack, Object patch, Object prototype,
                                 Operation<AEItemKey> constructor) {
        // A successful pre-copy probe passed this exact key-owned stack through the factory.
        var recent = keys.recentValue();
        if (recent != null && recent.getReadOnlyStack() == ownedStack) return recent;
        var cached = keys.findIdentity(patch, 0);
        if (cached != null && (cached.getReadOnlyStack() == ownedStack
                || matchesIdentity(cached, ownedStack, prototype))) return cached;
        var probe = new ContentProbe(ownedStack, prototype);
        return keys.intern(patch, 0, probe, () -> constructor.call(ownedStack),
                ComponentItemKeyCache::canonicalPatch, key -> probe.matches(canonicalPatch(key), key));
    }

    private boolean matchesIdentity(AEItemKey key, ItemStack source, Object prototype) {
        if (key.getItem() != source.getItem()
                || !(key.getReadOnlyStack().getComponents() instanceof SharedComponentPatch access)
                || access.thunderbolt$prototypeIdentity() != prototype) return false;
        // Snapshot identity and normalized count/animation were validated by the alias stamp.
        if (staticHooks) return true;
        var metadata = KeyConstructionCache.normalizedStack(source);
        return key.getMaxStackSize() == metadata.getMaxStackSize()
                && key.getFuzzySearchValue() == metadata.getDamageValue();
    }

    private static Object canonicalPatch(AEItemKey key) {
        var stack = key.getReadOnlyStack();
        return stack.getCount() == 1 && stack.getPopTime() == 0
                && stack.getComponents() instanceof SharedComponentPatch access
                ? access.thunderbolt$copyOnWritePatchIdentity() : null;
    }

    /** Transient query only. No fingerprint, stack wrapper or cache backlink is attached to AEItemKey. */
    private static final class ContentProbe implements WeakCanonicalMap.Lookup<Object, AEItemKey> {
        private final DataComponentPatch components;
        private final Item item;
        private final Object prototype;
        private final int maxStackSize, damage, hash;

        ContentProbe(ItemStack stack, Object prototype) {
            this.components = stack.getComponentsPatch();
            this.item = stack.getItem();
            this.prototype = prototype;
            // NeoForge item hooks are evaluated once before acquiring a writer monitor.
            this.maxStackSize = stack.getMaxStackSize();
            this.damage = stack.getDamageValue();
            int hash = 31 * components.hashCode() + System.identityHashCode(prototype);
            hash = 31 * hash + System.identityHashCode(item);
            hash = 31 * hash + maxStackSize;
            this.hash = 31 * hash + damage;
        }

        @Override public int hash() { return hash; }

        @Override public boolean matches(Object snapshot, AEItemKey key) {
            // The weak stored key is the canonical patch at insertion time. Reject stale native
            // cached metadata if an addon illegally changes the key's supposedly read-only stack.
            if (snapshot == null || canonicalPatch(key) != snapshot || key.getItem() != item
                    || key.getMaxStackSize() != maxStackSize || key.getFuzzySearchValue() != damage) return false;
            var stack = key.getReadOnlyStack();
            return stack.getComponents() instanceof SharedComponentPatch access
                    && access.thunderbolt$prototypeIdentity() == prototype
                    && components.equals(stack.getComponentsPatch());
        }
    }
}
