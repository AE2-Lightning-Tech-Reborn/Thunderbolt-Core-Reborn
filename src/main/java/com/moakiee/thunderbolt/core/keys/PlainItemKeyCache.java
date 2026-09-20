package com.moakiee.thunderbolt.core.keys;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IItemExtension;

/** Owned by one cache generation; the Item field is explicitly detached when that generation ends. */
public final class PlainItemKeyCache {
    private static final ClassValue<Boolean> STATIC_DAMAGE_HOOKS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getMaxDamage", ItemStack.class).getDeclaringClass() == IItemExtension.class
                        && type.getMethod("getDamage", ItemStack.class).getDeclaringClass() == IItemExtension.class;
            } catch (ReflectiveOperationException | SecurityException ignored) { return false; }
        }
    };
    final Object generation;
    final ItemKeyCacheOwner owner;
    final boolean staticDamageHooks;
    volatile AEItemKey key;
    private volatile ComponentItemKeyCache components;

    PlainItemKeyCache(Object generation, ItemKeyCacheOwner owner) {
        this.generation = generation;
        this.owner = owner;
        this.staticDamageHooks = owner != null && STATIC_DAMAGE_HOOKS.get(owner.getClass());
    }

    ComponentItemKeyCache components(boolean create, WeakCanonicalMap.IdentityTable<AEItemKey> identities) {
        var cache = components;
        if (cache == null && create) {
            synchronized (this) {
                cache = components;
                if (cache == null) components = cache = new ComponentItemKeyCache(identities, staticDamageHooks);
            }
        }
        return cache;
    }
}
