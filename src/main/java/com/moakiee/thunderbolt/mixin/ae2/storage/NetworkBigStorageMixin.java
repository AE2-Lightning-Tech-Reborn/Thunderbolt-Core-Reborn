package com.moakiee.thunderbolt.mixin.ae2.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;

import com.moakiee.thunderbolt.api.storage.BigMEStorage;
import com.moakiee.thunderbolt.core.storage.big.BigAmounts;
import com.moakiee.thunderbolt.core.storage.big.BigStorageOps;

import org.spongepowered.asm.mixin.*;

import java.math.BigInteger;
import java.util.*;

/** Additional interface only; native AE2 method descriptors and inventory ownership stay intact. */
@Mixin(NetworkStorage.class)
public abstract class NetworkBigStorageMixin implements BigMEStorage {
    @Shadow @Final private NavigableMap<Integer, List<MEStorage>> priorityInventory;
    @Shadow private boolean mountsInUse;

    @Shadow
    private void flushQueuedOperations() {
        throw new AssertionError();
    }

    @Shadow
    private boolean isQueuedForRemoval(MEStorage inventory) {
        throw new AssertionError();
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "getAvailableStacks",
            at =
                    @org.spongepowered.asm.mixin.injection.At(
                            value = "INVOKE",
                            target =
                                    "Lappeng/api/storage/MEStorage;getAvailableStacks(Lappeng/api/stacks/KeyCounter;)V"))
    private void thunderbolt$projectAggregate(
            MEStorage storage,
            appeng.api.stacks.KeyCounter out,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var local = new appeng.api.stacks.KeyCounter();
        original.call(storage, local);
        for (var entry : local)
            out.set(
                    entry.getKey(),
                    com.google.common.math.LongMath.saturatedAdd(
                            out.get(entry.getKey()), entry.getLongValue()));
    }

    @Override
    public BigInteger insertBig(AEKey key, BigInteger n, Actionable mode, IActionSource source) {
        return thunderbolt$transfer(key, n, mode, source, true);
    }

    @Override
    public BigInteger extractBig(AEKey key, BigInteger n, Actionable mode, IActionSource source) {
        return thunderbolt$transfer(key, n, mode, source, false);
    }

    @Unique
    private BigInteger thunderbolt$transfer(
            AEKey key, BigInteger n, Actionable mode, IActionSource source, boolean insert) {
        BigAmounts.nonNegative(n);
        if (mountsInUse || n.signum() == 0) return BigInteger.ZERO;
        mountsInUse = true;
        try {
            var remaining = n;
            var visited = Collections.newSetFromMap(new IdentityHashMap<MEStorage, Boolean>());
            var priorities = insert ? priorityInventory : priorityInventory.descendingMap();
            for (var inventories : priorities.values()) {
                var deferred = new ArrayList<MEStorage>();
                for (var storage : inventories) {
                    if (!visited.add(storage) || isQueuedForRemoval(storage)) continue;
                    if (insert && !storage.isPreferredStorageFor(key, source)) {
                        deferred.add(storage);
                        continue;
                    }
                    var accepted =
                            insert
                                    ? BigStorageOps.insert(storage, key, remaining, mode, source)
                                    : BigStorageOps.extract(storage, key, remaining, mode, source);
                    remaining = remaining.subtract(accepted);
                    if (remaining.signum() == 0) return n;
                }
                for (var storage : deferred) {
                    if (isQueuedForRemoval(storage)) continue;
                    remaining =
                            remaining.subtract(
                                    BigStorageOps.insert(storage, key, remaining, mode, source));
                    if (remaining.signum() == 0) return n;
                }
            }
            return n.subtract(remaining);
        } finally {
            mountsInUse = false;
            flushQueuedOperations();
        }
    }

    @Override
    public Map<AEKey, BigInteger> snapshotBig(IActionSource source) {
        if (mountsInUse) return Map.of();
        mountsInUse = true;
        try {
            var result = new LinkedHashMap<AEKey, BigInteger>();
            var visited = Collections.newSetFromMap(new IdentityHashMap<MEStorage, Boolean>());
            for (var inventories : priorityInventory.values())
                for (var storage : inventories) {
                    if (!visited.add(storage) || isQueuedForRemoval(storage)) continue;
                    BigStorageOps.snapshot(storage, source)
                            .forEach((key, n) -> result.merge(key, n, BigInteger::add));
                }
            return Map.copyOf(result);
        } finally {
            mountsInUse = false;
            flushQueuedOperations();
        }
    }
}
