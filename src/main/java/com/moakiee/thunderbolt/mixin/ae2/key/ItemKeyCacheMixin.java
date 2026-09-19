package com.moakiee.thunderbolt.mixin.ae2.key;

import appeng.api.stacks.AEItemKey;
import com.moakiee.thunderbolt.core.keys.ItemKeyCacheOwner;
import com.moakiee.thunderbolt.core.keys.KeyConstructionCache;
import com.moakiee.thunderbolt.core.keys.PlainItemKeyCache;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Item.class)
abstract class ItemKeyCacheMixin implements ItemKeyCacheOwner {
    @Unique private volatile PlainItemKeyCache thunderbolt$plainKeys;
    @Unique private volatile AEItemKey thunderbolt$plainKey;

    @Override public PlainItemKeyCache thunderbolt$plainKeyCache() {
        return thunderbolt$plainKeys;
    }

    @Override public synchronized void thunderbolt$plainKeyCache(PlainItemKeyCache cache) {
        // Only publication/teardown synchronize. No mod hook or key constructor runs under this lock.
        if (KeyConstructionCache.isCurrent(cache) && thunderbolt$plainKeys != cache) {
            thunderbolt$plainKey = null;
            thunderbolt$plainKeys = cache;
        }
    }

    @Override public AEItemKey thunderbolt$plainKey() {
        return thunderbolt$plainKey;
    }

    @Override public synchronized void thunderbolt$publishPlainKey(PlainItemKeyCache expected,
                                                                  AEItemKey key) {
        // A constructor can finish after a reset. It must not repopulate detached Item fields.
        if (thunderbolt$plainKeys != expected || !KeyConstructionCache.isCurrent(expected)) return;
        var stack = key.getReadOnlyStack();
        if (stack.getCount() == 1 && stack.getPopTime() == 0) thunderbolt$plainKey = key;
    }

    @Override public synchronized void thunderbolt$clearPlainKeyCache(PlainItemKeyCache expected) {
        if (thunderbolt$plainKeys == expected) {
            thunderbolt$plainKey = null;
            thunderbolt$plainKeys = null;
        }
    }
}
