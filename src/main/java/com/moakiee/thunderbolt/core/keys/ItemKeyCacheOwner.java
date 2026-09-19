package com.moakiee.thunderbolt.core.keys;

import appeng.api.stacks.AEItemKey;

/** Optional Item field pointing into the active, disposable cache generation. */
public interface ItemKeyCacheOwner {
    PlainItemKeyCache thunderbolt$plainKeyCache();
    void thunderbolt$plainKeyCache(PlainItemKeyCache cache);
    AEItemKey thunderbolt$plainKey();
    void thunderbolt$publishPlainKey(PlainItemKeyCache expected, AEItemKey key);
    void thunderbolt$clearPlainKeyCache(PlainItemKeyCache expected);
}
