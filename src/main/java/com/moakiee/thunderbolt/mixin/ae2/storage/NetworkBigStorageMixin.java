package com.moakiee.thunderbolt.mixin.ae2.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import appeng.me.storage.DelegatingMEInventory;
import appeng.api.stacks.KeyCounter;

import com.moakiee.thunderbolt.api.storage.BigMEStorage;
import com.moakiee.thunderbolt.core.storage.big.BigAmounts;
import com.moakiee.thunderbolt.core.storage.big.BigStorageOps;

import org.spongepowered.asm.mixin.*;

import java.math.BigInteger;
import java.util.*;

/** Additional interface only; native AE2 method descriptors and inventory ownership stay intact. */
@Mixin(remap = false, value = NetworkStorage.class)
public abstract class NetworkBigStorageMixin implements BigMEStorage {
    @Shadow @Final private it.unimi.dsi.fastutil.objects.ObjectArrayList<?> priorityInventory;
    @Shadow private boolean mountsInUse;

    @Shadow
    private void flushQueuedOperations() {
        throw new AssertionError();
    }

    @Shadow
    private boolean isQueuedForRemoval(MEStorage inventory) {
        throw new AssertionError();
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
        // GTO owns cross-network alias tracking. Its long API must keep control of these
        // graphs: independently walking each bus would double-count SIMULATE results.
        if (thunderbolt$hasNetworkLinks()) {
            var self = (MEStorage) this;
            long amount = BigAmounts.project(n);
            return BigInteger.valueOf(insert ? self.insert(key, amount, mode, source)
                    : self.extract(key, amount, mode, source));
        }
        mountsInUse = true;
        try {
            var remaining = n;
            var visited = Collections.newSetFromMap(new IdentityHashMap<MEStorage, Boolean>());
            // Match GTO's array order exactly, including equal-priority mounts.
            int index = insert ? 0 : priorityInventory.size() - 1;
            int step = insert ? 1 : -1;
            for (; index >= 0 && index < priorityInventory.size(); index += step) {
                var storage = ((GtoStorageMountAccessor) priorityInventory.get(index)).thunderbolt$storage();
                if (!visited.add(storage) || isQueuedForRemoval(storage)) continue;
                var accepted = insert ? BigStorageOps.insert(storage, key, remaining, mode, source)
                        : BigStorageOps.extract(storage, key, remaining, mode, source);
                remaining = remaining.subtract(accepted);
                if (remaining.signum() == 0) return n;
            }
            return n.subtract(remaining);
        } finally {
            mountsInUse = false;
            flushQueuedOperations();
        }
    }

    @Unique
    private boolean thunderbolt$hasNetworkLinks() {
        for (var mount : priorityInventory) {
            MEStorage storage = ((GtoStorageMountAccessor) mount).thunderbolt$storage();
            var visited = Collections.newSetFromMap(new IdentityHashMap<MEStorage, Boolean>());
            while (storage instanceof DelegatingMEInventory delegate && visited.add(storage)) {
                storage = delegate.getDelegate();
            }
            if (storage instanceof NetworkStorage) return true;
        }
        return false;
    }

    @Override
    public Map<AEKey, BigInteger> snapshotBig(IActionSource source) {
        if (mountsInUse) return Map.of();
        if (thunderbolt$hasNetworkLinks()) {
            var self = (MEStorage) this;
            var counter = new KeyCounter();
            self.getAvailableStacks(counter);
            var result = new LinkedHashMap<AEKey, BigInteger>();
            for (var entry : counter) {
                long amount = self.extract(entry.getKey(), Math.max(0, entry.getLongValue()),
                        Actionable.SIMULATE, source);
                if (amount > 0) result.put(entry.getKey(), BigInteger.valueOf(amount));
            }
            return Map.copyOf(result);
        }
        mountsInUse = true;
        try {
            var result = new LinkedHashMap<AEKey, BigInteger>();
            var visited = Collections.newSetFromMap(new IdentityHashMap<MEStorage, Boolean>());
            for (var mount : priorityInventory) {
                var storage = ((GtoStorageMountAccessor) mount).thunderbolt$storage();
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
