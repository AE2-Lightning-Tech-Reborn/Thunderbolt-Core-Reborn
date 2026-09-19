package com.moakiee.thunderbolt.mixin.ae2.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.cells.StorageCell;
import appeng.me.storage.DriveWatcher;

import com.moakiee.thunderbolt.api.storage.BigMEStorage;
import com.moakiee.thunderbolt.core.storage.big.BigAmounts;
import com.moakiee.thunderbolt.core.storage.big.BigStorageOps;

import org.spongepowered.asm.mixin.*;

import java.math.BigInteger;
import java.util.*;

@Mixin(DriveWatcher.class)
public abstract class DriveBigStorageMixin implements BigMEStorage {
    @Shadow
    public abstract StorageCell getCell();

    @Shadow @Final private Runnable activityCallback;

    @Override
    public BigInteger insertBig(AEKey key, BigInteger n, Actionable mode, IActionSource source) {
        BigAmounts.nonNegative(n);
        var self = (DriveWatcher) (Object) this;
        if (n.signum() == 0 || self.insert(key, 1, Actionable.SIMULATE, source) == 0)
            return BigInteger.ZERO;
        var result = BigStorageOps.insert(getCell(), key, n, mode, source);
        if (mode == Actionable.MODULATE && result.signum() > 0) activityCallback.run();
        return result;
    }

    @Override
    public BigInteger extractBig(AEKey key, BigInteger n, Actionable mode, IActionSource source) {
        BigAmounts.nonNegative(n);
        var self = (DriveWatcher) (Object) this;
        if (n.signum() == 0 || self.extract(key, 1, Actionable.SIMULATE, source) == 0)
            return BigInteger.ZERO;
        var result = BigStorageOps.extract(getCell(), key, n, mode, source);
        if (mode == Actionable.MODULATE && result.signum() > 0) activityCallback.run();
        return result;
    }

    @Override
    public Map<AEKey, BigInteger> snapshotBig(IActionSource source) {
        var self = (DriveWatcher) (Object) this;
        var result = new LinkedHashMap<AEKey, BigInteger>();
        BigStorageOps.snapshot(getCell(), source)
                .forEach(
                        (key, n) -> {
                            if (self.extract(key, 1, Actionable.SIMULATE, source) > 0)
                                result.put(key, n);
                        });
        return Map.copyOf(result);
    }
}
